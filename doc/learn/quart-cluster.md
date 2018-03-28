---
title: quartz集群原理
tags: 
notebook: java·Quartz
---
# 总述
首先quartz的调度线程都是从`JobStore`获得任务`job`进行调度的,而获取任务的过程也是对集群调度进行加锁同步的过程.

`JobStoreTX`的本质是通过给`QRTZ_LOCKS`表的一个记录数据获取和释放行锁(在普通的sql语句后面加`FOR UPDATE`)来进行同步,行锁同步方式在1.6版本后就不推荐了.

`JobStoreCMT`把数据源的的事务管理交由容器完成.

xxl-job用的是`LocalDataSourceJobStore`(`JobStoreCMT`的子类),原因是spring实现的`SchedulerFactoryBean`在初始化quartz的调度器工厂时,只要用户配了数据源spring都会重新设置`org.quartz.jobStore.class`配置属性为`LocalDataSourceJobStore`.它使用的是数据库锁(`StdRowLockSemaphore`标准的行锁信号量)

集群同步中还有两个关键的类(线程):`ClusterManager`集群管理线程,`MisfireHandler`错失调度处理器线程

## `ClusterManager`
集群管理线程主要的管理逻辑位于它的`manage()`方法调用的`doCheckin()`方法.它只有配置属性`org.quartz.jobStore.isClustered`为`true`的时候才会开启.

调度:
- `manage()`方法的第一次调度发生在quartz调度器的开启方法(启动线程)被`SchedulerFactoryBean`调用的时候,在它构造函数的里面会调用这个方法.
- 其他调用都是在线程`run()`方法里面循环执行.

`doCheckin()`方法的主要检入逻辑(第一次检入):
- 在第一次检入的情况下,首先会通过锁处理器`DBSemaphore`(又名数据库信号量)的`obtainLock`方法获取状态访问行锁`STATE_ACCESS`,锁获取成功后会缓存到一个`HashSet`中.(下面逻辑都是在调度状态同步的情况下进行)
- 判断是否为第一次检入,若是则调用`clusterCheckIn()`方法检入调度状态记录`SchedulerStateRecord`,否则调用`findFailedInstances()`获取失败的调度状态记录.
- 对获得的调度状态记录进行处理前会先通过`obtainLock()`获取触发器访问行锁`TRIGGER_ACCESS`(下面逻辑都是在触发器调度同步的情况下进行)
- 调用`clusterRecover()`集群恢复处理方法(处理调度状态)
- 最后调用jdbc的`conn.commit()`方法执行提交(释放数据库的行锁),并清掉缓存的锁.


`DBSemaphore`的`obtainLock()`方法的主要逻辑:
- 判断自己是否已经拥有对应的行锁,有则结束
- 执行`for update`sql语句获取行锁(会阻塞直到获得锁)
- 缓存已经获得的行锁


`JobStoreSupport`进行集群检入`clusterCheckIn()`的主要逻辑
- 调用`findFailedInstances()`方法获得调度状态记录
    - 执行sql语句获得对应调度名称的调度器状态记录
    - 判断每个调度器记录是否是自己的记录(判断实例名称`id`是否相等),是且为第一次检入则直接添加到到返回的调度器状态列表中,否则若时间合适才添加到到返回的调度器状态列表中
    - 若是第一次检入,会检索孤儿触发器(已触发的触发器记录**对应触发器状态记录不是自己**),把它放到新建的调度状态记录中后加入到返回列表.
    - 最后如果检测到自己是第一次检入但没有找到自己的调度器记录,会打印一个日志来发出警告
    - 返回调度记录列表
- 更新或插入新的调度状态
- 返回`SchedulerStateRecord`调度状态记录列表(交给`clusterRecover()`方法进行恢复处理)

`JobStoreSupport`进行集群恢复`clusterRecover()`的主要逻辑
- 首先,只有传进来的`SchedulerStateRecord`调度状态记录列表长度大于0,下面的逻辑才会执行并且每个调度状态记录都会执行一次
- 通过调度状态记录的调度器名称从数据库的`*_FIRED_TRIGGERS`表中获得被触发的触发器记录`FiredTriggerRecord`
- 根据已触发触发器`*_FIRED_TRIGGERS`记录的状态进行处理,下面分别列出:
    - 若已触发触发器记录的状态为阻塞`BLOCKED`,`PAUSED_BLOCKED`,`ACQUIRED`等状态时,把对应触发器记录`*_TRIGGERS`的状态设置为阻塞`BLOCKED`,`PAUSED_BLOCKED`,`ACQUIRED`;
    - **[重要]** 若已触发触发器记录不处于`ACQUIRED`状态且记录的任务请求恢复为`true`时,对任务进行回复处理(对应任务存在的情况下才会处理)
        - 
- 对已触发的触发器记录处理完后,把处理这些记录都从表中删除

```
// 触发器状态  
TriggerState  
    |-public enum TriggerState { NONE, NORMAL, PAUSED, COMPLETE, ERROR, BLOCKED }  
        |-NONE 无  
        |-NORMAL 正常状态  
        |-PAUSED 暂停状态(调用了scheduler.pauseXXX()方法,直到resume()被调用为止都不会被调用) 
        |-COMPLETE 完成  
        |-ERROR 错误
        |-BLOCKED 堵塞(触发器对应的任务正在执行,触发完后转WAITING状态)
        |-PAUSED_BLOCKED 
        |-ACQUIRED 标记触发器下次触发(触发完后转WAITING状态)
        |-WAITING 正常状态,等待下次触发调度

//注意触发器`trigger`与任务`job`的关系是多对一的关系
```

# xxl中quartz的启动过程简述
在这里启动初始化位于调度器工厂bean`SchedulerFactoryBean`的`afterPropertiesSet()`方法.重要逻辑如下
- 实例化并初始化调度器工厂,主要是把配置数据传递给`SchedulerFactory`的`initialize()`方法进行初始化.(创建调度器时会用到)
- 通过调度器工厂`StdSchedulerFactory`创建quartz调度器

注意:用`SchedulerFactoryBean`整合spring的话,调度器的名称(**重要**)就是Bean的名称
## quartz任务执行原理
quartz任务执行中的关键线程有两个:调度线程和工作线程


# (转)美团写的quartz集群原理(`JobStoreTX`)
## 1. Quartz集群数据库表

Quartz的集群部署方案在架构上是分布式的，没有负责集中管理的节点，而是利用数据库锁的方式来实现集群环境下进行并发控制。BTW，分布式部署时需要保证各个节点的系统时间一致。

Quartz数据库核心表如下：

|Table Name	| Description |
| -------------| -----:|
|QRTZ_CALENDARS|	存储Quartz的Calendar信息|
|QRTZ_CRON_TRIGGERS	|存储CronTrigger，包括Cron表达式和时区信息
|QRTZ_FIRED_TRIGGERS|	存储与已触发的Trigger相关的状态信息，以及相联Job的执行信息
|QRTZ_PAUSED_TRIGGER_GRPS|	存储已暂停的Trigger组的信息
|QRTZ_SCHEDULER_STATE	|存储少量的有关Scheduler的状态信息，和别的Scheduler实例
|QRTZ_LOCKS	|存储程序的悲观锁的信息
|QRTZ_JOB_DETAILS	|存储每一个已配置的Job的详细信息
|QRTZ_JOB_LISTENERS	|存储有关已配置的JobListener的信息
|QRTZ_SIMPLE_TRIGGERS|	存储简单的Trigger，包括重复次数、间隔、以及已触的次数
|QRTZ_BLOG_TRIGGERS	|Trigger作为Blob类型存储
|QRTZ_TRIGGER_LISTENERS|	存储已配置的TriggerListener的信息
|QRTZ_TRIGGERS	|存储已配置的Trigger的信息
其中，QRTZ_LOCKS就是Quartz集群实现同步机制的行锁表，其表结构如下：

```sql
--QRTZ_LOCKS表结构
CREATE TABLE `QRTZ_LOCKS` (
  `LOCK_NAME` varchar(40) NOT NULL,
   PRIMARY KEY (`LOCK_NAME`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
```

```
--QRTZ_LOCKS记录

| LOCK_NAME       |
|-----------------|
| CALENDAR_ACCESS |
| JOB_ACCESS      |
| MISFIRE_ACCESS  |
| STATE_ACCESS    |
| TRIGGER_ACCESS  |
```

可以看出QRTZ_LOCKS中有5条记录，代表5把锁，分别用于实现多个Quartz Node对Job、Trigger、Calendar访问的同步控制。

## 2. Quartz线程模型
在Quartz中有两类线程：Scheduler调度线程和任务执行线程。

任务执行线程：Quartz不会在主线程(QuartzSchedulerThread)中处理用户的Job。Quartz把线程管理的职责委托给ThreadPool，一般的设置使用SimpleThreadPool。SimpleThreadPool创建了一定数量的WorkerThread实例来使得Job能够在线程中进行处理。

WorkerThread是定义在SimpleThreadPool类中的内部类，它实质上就是一个线程。例如，CRM中配置如下：

```xml
 <!-- 线程池配置 -->
 <prop key="org.quartz.threadPool.class">org.quartz.simpl.SimpleThreadPool</prop>
 <prop key="org.quartz.threadPool.threadCount">20</prop>
 <prop key="org.quartz.threadPool.threadPriority">5</prop>
```
QuartzSchedulerThread调度主线程：QuartzScheduler被创建时创建一个QuartzSchedulerThread实例。

## 3. 集群源码分析

Quartz究竟是如何保证集群情况下trgger处理的信息同步？

下面跟着源码一步一步分析，QuartzSchedulerThread包含有决定何时下一个Job将被触发的处理循环，主要逻辑在其run()方法中：
```java
public void run() {
   boolean lastAcquireFailed = false;
   while (!halted.get()) {
     //......

     int availThreadCount = qsRsrcs.getThreadPool().blockForAvailableThreads();
     if(availThreadCount > 0) { 

     //......

     //调度器在trigger队列中寻找30秒内一定数目的trigger(需要保证集群节点的系统时间一致)
     triggers = qsRsrcs.getJobStore().acquireNextTriggers(
                            now + idleWaitTime, Math.min(availThreadCount, qsRsrcs.getMaxBatchSize()), qsRsrcs.getBatchTimeWindow());

     //......

     //触发trigger
     List<TriggerFiredResult> res = qsRsrcs.getJobStore().triggersFired(triggers);

     //......

     //释放trigger
     for (int i = 0; i < triggers.size(); i++) {
         qsRsrcs.getJobStore().releaseAcquiredTrigger(triggers.get(i));
     }

   }                
}
```

由此可知，QuartzScheduler调度线程不断获取trigger，触发trigger，释放trigger。下面分析trigger的获取过程，qsRsrcs.getJobStore()返回对象是JobStore，集群环境配置如下：
```xml
<!-- JobStore 配置 -->
<prop key="org.quartz.jobStore.class">org.quartz.impl.jdbcjobstore.JobStoreTX</prop>
```
JobStoreTX继承自JobStoreSupport，而JobStoreSupport的acquireNextTriggers、triggersFired、releaseAcquiredTrigger方法负责具体trigger相关操作，都必须获得TRIGGER_ACCESS锁。核心逻辑在executeInNonManagedTXLock方法中：
```java
protected <T> T executeInNonManagedTXLock(
        String lockName, 
        TransactionCallback<T> txCallback, final TransactionValidator<T> txValidator) throws JobPersistenceException {
    boolean transOwner = false;
    Connection conn = null;
    try {
        if (lockName != null) {
            if (getLockHandler().requiresConnection()) {
                conn = getNonManagedTXConnection();
            }

            //获取锁
            transOwner = getLockHandler().obtainLock(conn, lockName);
        }

        if (conn == null) {
            conn = getNonManagedTXConnection();
        }

        final T result = txCallback.execute(conn);
        try {
            commitConnection(conn);
        } catch (JobPersistenceException e) {
            rollbackConnection(conn);
            if (txValidator == null || !retryExecuteInNonManagedTXLock(lockName, new TransactionCallback<Boolean>() {
                @Override
                public Boolean execute(Connection conn) throws JobPersistenceException {
                    return txValidator.validate(conn, result);
                }
            })) {
                throw e;
            }
        }

        Long sigTime = clearAndGetSignalSchedulingChangeOnTxCompletion();
        if(sigTime != null && sigTime >= 0) {
            signalSchedulingChangeImmediately(sigTime);
        }

        return result;
    } catch (JobPersistenceException e) {
        rollbackConnection(conn);
        throw e;
    } catch (RuntimeException e) {
        rollbackConnection(conn);
        throw new JobPersistenceException("Unexpected runtime exception: "
                + e.getMessage(), e);
    } finally {
        try {
            releaseLock(lockName, transOwner);      //释放锁
        } finally {
            cleanupConnection(conn);
        }
    }
}
```
由上代码可知Quartz集群基于数据库锁的同步操作流程如下图所示：
![](https://tech.meituan.com/img/quartz-lock-flow.png)

一个调度器实例在执行涉及到分布式问题的数据库操作前，首先要获取QUARTZ_LOCKS表中对应的行级锁，获取锁后即可执行其他表中的数据库操作，随着操作事务的提交，行级锁被释放，供其他调度实例获取。集群中的每一个调度器实例都遵循这样一种严格的操作规程。

getLockHandler()方法返回的对象类型是Semaphore，获取锁和释放锁的具体逻辑由该对象维护

```java
public interface Semaphore {

     boolean obtainLock(Connection conn, String lockName) throws LockException;

     void releaseLock(String lockName) throws LockException;

     boolean requiresConnection();
}
```

该接口的实现类完成具体操作锁的逻辑，在JobStoreSupport的初始化方法中注入的Semaphore具体类型是StdRowLockSemaphore

StdRowLockSemaphore的源码如下所示：
```java
public class StdRowLockSemaphore extends DBSemaphore {
//锁定SQL语句
public static final String SELECT_FOR_LOCK = "SELECT * FROM "
        + TABLE_PREFIX_SUBST + TABLE_LOCKS + " WHERE " + COL_LOCK_NAME
        + " = ? FOR UPDATE";

public static final String INSERT_LOCK = "INSERT INTO " + TABLE_PREFIX_SUBST 
        + TABLE_LOCKS + "(" + COL_SCHEDULER_NAME + ", " 
        + COL_LOCK_NAME + ") VALUES (" + SCHED_NAME_SUBST + ", ?)"; 

//指定锁定SQL
protected void executeSQL(Connection conn, String lockName, String expandedSQL) throws LockException {
    PreparedStatement ps = null;
    ResultSet rs = null;
    try {
        ps = conn.prepareStatement(expandedSQL);
        ps.setString(1, lockName);
        ......
        rs = ps.executeQuery();
        if (!rs.next()) {
            throw new SQLException(Util.rtp(
                "No row exists in table " + TABLE_PREFIX_SUBST +
                TABLE_LOCKS + " for lock named: " + lockName, getTablePrefix()));
        }
    } catch (SQLException sqle) {

    } finally {
      ...... //release resources
    }
  }
}

//获取QRTZ_LOCKS行级锁
public boolean obtainLock(Connection conn, String lockName) throws LockException {
    lockName = lockName.intern();

    if (!isLockOwner(conn, lockName)) {
        executeSQL(conn, lockName, expandedSQL);

        getThreadLocks().add(lockName);
    }
    return true;
}

//释放QRTZ_LOCKS行级锁
public void releaseLock(Connection conn, String lockName) {
    lockName = lockName.intern();

    if (isLockOwner(conn, lockName)) {
        getThreadLocks().remove(lockName);
    }
    ......
}
```

至此，总结一下Quartz集群同步机制：每当要进行与某种业务相关的数据库操作时，先去QRTZ_LOCKS表中查询操作相关的业务对象所需要的锁，在select语句之后加for update来实现。例如，TRIGGER_ACCESS表示对任务触发器相关的信息进行修改、删除操作时所需要获得的锁。这时，执行查询这个表数据的SQL形如：

```sql
select * from QRTZ_LOCKS t where t.lock_name='TRIGGER_ACCESS' for update
```

当一个线程使用上述的SQL对表中的数据执行查询操作时，若查询结果中包含相关的行，数据库就对该行进行ROW LOCK；若此时，另外一个线程使用相同的SQL对表的数据进行查询，由于查询出的数据行已经被数据库锁住了，此时这个线程就只能等待，直到拥有该行锁的线程完成了相关的业务操作，执行了commit动作后，数据库才会释放了相关行的锁，这个线程才能继续执行。

通过这样的机制，在集群环境下，结合悲观锁的机制就可以防止一个线程对数据库数据的操作的结果被另外一个线程所覆盖，从而可以避免一些难以觉察的错误发生。当然，达到这种效果的前提是需要把Connection设置为手动提交，即autoCommit为false。