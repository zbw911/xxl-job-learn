# xxl-job(v1.8.2)调度中心原理

## 启动
配置:
```
xxl-job-admin         
模块中的配置文件（resource）

spring/applicationcontext-base.xml
freemarker模板框架Configurer bean配置

spring/applicationcontext-xxl-job-admin.xml
数据源bean，事务管理bean，调度器bean【重要】

spring/springmvc-context.xml
freemarker模板视图bean配置，权限和cookie拦截器配置

freemarker.properties freemarker
模板框架配置文件

log4j.properties 
日志配置

quartz.properties  
quantz任务调度框架的配置文件【重要】（用于SchedulerFactoryBean生成quartz调度器bean）

xxl-job-admin.properties  
数据库连接信息，email，access token

```
初始化:

      首先spring根据applicationcontext-xxl-job-admin.xml的配置系统启动时会预实例化`XxlJobDynamicScheduler`的单例，并注入quartz（xxl-job调度中心核心）的quartzScheduler（它的初始化由SchedulerFactoryBean根据配置文件完成，根据默认配置会初始化1个调度线程和15个工作线程），在预实例化完成后调用它的`init()`方法。

在`init()`方法中
1. 调用`JobRegistryMonitorHelper.getInstance().start()`创建一个注册线程（执行器注册监控）。
1. 调用`JobFailMonitorHelper.getInstance().start()`创建一个监控线程
1. 通过服务器工厂`NetComServerFactory`添加回调服务（使`JobApiController`收到执行器的回调请求后，能回调`adminBiz`,如执行结果,执行器注册等回调）。

到这里xxl-job的调度中心就基本启动完成了。


监控器线程的功能:

      监控任务的执行情况，调度中心每次触发任务后都会把任务日志更新到调度日志表中并把日志记录的jobLogId加入到监控器持有的阻塞队列中，然后通过从循环从队列中获得jobLogId来获取日志并输出(若任务还没结束会重新放进队列,注意每一次任务调度对应一个jobLog).

## 任务执行(调度器quartz)

`RemoteHttpJobBean`继承`QuartzJobBean`(它实质是quartz的`Job`接口的实现),这里我们只需要知道当任务被触发时`RemoteHttpJobBean.executeInternal()`方法会被回调就行了.
```Java
protected void executeInternal(JobExecutionContext context)
      throws JobExecutionException {

   // load jobId
   JobKey jobKey = context.getTrigger().getJobKey();
   Integer jobId = Integer.valueOf(jobKey.getName());

   // trigger
   XxlJobTrigger.trigger(jobId);
}
```
真正的任务逻辑在`XxlJobTrigger.trigger(jobId)`方法):
1. 通过xxl任务id从数据库中获取任务信息和执行器信息
1. 生成这次执行的日志对象并保存到日志表
1. 调用路由执行策略的`routeRun()`方法(调用远程执行器)
1. 最后把这次执行的日志id条件到监控器的阻塞队列中

`routeRun()`的实质是调用执行器端的远程代理对象`ExecutorBiz`的`run()`方法发起一次远程调用(最后调`HttpPost`)

## 回调服务
执行器端的任务执行完成后会调用`AdminBiz`这个远程代理的`callback`方法把结果发给调度中心.调度中心的`AdminBizImpl`被远程调用后,会检查该任务是否有子任务,是则触发子任务,否则仅仅做一些日志记录.

## 注册服务

执行器注册信息表数据的更新由`AdminBizImpl`的`registry`方法完成:执行器端每一个心跳时间就会调用一次远程代理对象`AdminBiz`的`registry()`方法更新执行器注册表`XXL_JOB_QRTZ_TRIGGER_REGISTRY`里的执行器注册信息,若无记录则插入(记录中的更新时间字段用于心跳检测)..

执行器注册监控线程原理

循环做如下逻辑：
1. 从执行器信息表`XXL_JOB_QRTZ_TRIGGER_GROUP`中获取用户在系统中添加的自动注册类型的任务执行器信息，如果没有添加任何执行器或只有手动注册类型的执行器那么下面的逻辑都不执行。
2. 移除执行器注册表`XXL_JOB_QRTZ_TRIGGER_REGISTRY`中因过时而失效了的信息(若一个心跳时间内没有再次继续注册则失效)。
3. 根据执行器名称`appname`对注册表信息进行分类
4. 更新执行器信息表中的有效执行器地址列表(分类后)信息字段,这样同名的执行器就可以构成一个集群了

## 任务添加

接下来是添加一个新任务的逻辑.
1. 对XxlJobInfo对象(在前端填的任务信息,它既是vo也是po)进行一系列的校验,校验失败则直接返回信息给前端
1. 把任务信息保存到`JOB_QRTZ_TRIGGER_INFO`任务信息扩展表(扩展quartz的任务信息)
1. 把任务交给`XxlJobDynamicScheduler.addJob()`方法调度

在`addJob()`方法中(与quartz有关的细节不在这里说明,有兴趣可以自己去了解)
1. 以xxl任务信息的id和xxl执行器信息id生成quartz的触发器和任务的key(xxl-job与quartz的关联点1)
1. 通过`XxlJobInfo`对象的cron表达式字符串和`triggerKey`生成`cronTrigger`对象(xxl-job与quartz的关联点2)
1. 通过任务key和`RemoteHttpJobBean`(xxl-job与quartz的核心关联点)生成`jobDetail`对象
1. 通过`scheduler.scheduleJob(jobDetail, cronTrigger)`开始任务调度执行

在这里可以看到xxl-job框架在任务调度有关的核心是由quartz完成的(包括任务的持久化的核心工作也是有quartz的jobStore完成)


## 任务管理的其他功能

任务的暂停,恢复仅仅是简单地调用quartz调度器的暂停与恢复

任务的编辑和删除也仅是多了一些校验和数据库操作


## 执行器管理

执行器的添加,编辑和删除都仅仅是简单的数据库操作

## 集群原理

调度中心里任务调度的集群同步是交给quartz框架完成,xxl-job本身没有做额外的操作


