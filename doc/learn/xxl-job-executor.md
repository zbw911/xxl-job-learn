# xxl-job(v1.8.2)执行器原理

与调度中心不同,执行器不需要任何调度相关的信息,它得到了最大的简化,这是此框架的优点.我们使用它时,只需要继承`IJobHandler`实现它的`execute()`方法,并通过调用`XxlJobExecutor.start()`启动执行器即可.当然在此之前你要配置一些信息让它知道调度中心在哪里.

## 启动

执行器的启动入口是`XxlJobExecutor.start()`,逻辑如下:

1. 初始化化调度中心集群的`AdminBiz`远程代理对象列表(可以在服务器端启动时的`init()`方法中看到被代理的对象在`NetComServerFactory.putService()`方法中初始化)
2. 从IOC容器中获取继承`JobHandler`的任务并把它们注册到一个`map`  **[重要]**
3. 初始化日志路径
4. 初始化执行器服务器`initExecutorServer()`**[重要]**

`initExecutorServer()`方法:

1. 调用`NetComServerFactory.putService()`方法初始化一个`ExecutorBiz`执行器端的远程代理对象供调用中心端使用.
2. 调用`NetComServerFactory.setAccessToken(accessToken)`初始化远程调用的token
3. 通过`NetComServerFactory`的`start()`方法启动`jetty`服务器(`ExecutorBiz`是基于`jetty`的)并开始执行器注册线程(向调度中心注册自己)和触发器回调线程(返回执行结果给调度中心)

初始化jetty服务器的时候会把设置一个叫`JettyServerHandler`的处理器,它继承了jetty的`AbstractHandler`,通过`NetComServerFactory.putService()`方法注册的代理对象都是在此处被回调.

## 自动注册

执行器端的自动注册功能由执行器注册线程管理:

    这个线程的逻辑很简单,它先获得启动时初始化的调度中心代理对象`adminBiz`的列表,然后顺序尝试调用`adminBiz.registry()`进行注册(提供执行器名称和地址给调度中心),只要成功注册了一个调度中心,后面的就不再注册(因为注册的实质只是更新数据库记录信息,无需重复更新).之后线程休眠一个心跳时间再重复以上的逻辑


## 调度请求处理

当调度中心调用了远程代理对象`ExecutorBiz`的`run`方法时,执行器端会开始一个任务调度的处理.

执行器的处理如下(`ExecutorBizImpl.run()`):

1. 通过任务处理器名称获得对应的任务处理器`IJobHandler`(有缓存机制)
2. 通过`XxlJobExecutor.registJobThread()`创建并注册一个工作线程(有缓存机制),这时工作线程`JobThread`已经启动,只是触发器任务队列为空而阻塞
3. 通过`jobThread.pushTriggerQueue()`方法把任务加入到触发器队列中(工作线程从阻塞中恢复)

工作线程`JobThread`主要工作(中间穿插的日志等处理省略):

1. 从队列获得触发器参数,并解析成处理器参数
2. 回调用户实现的处理器的`execut()`方法,并传入处理器参数
3. 通过`TriggerCallbackThread.pushCallBack()`把处理结果提交到回调队列中


## 回调线程

回调线程的功能:

    从回调的阻塞队列中获得任务在执行器中的调度结果,然后调用`AdminBiz`这个远程代理的callback方法把结果发给调度中心.

## 集群原理

执行器的集群管理由调度中心完成.

因为只要执行器名称`xxl.job.executor.appname`相同,调度中心会把注册进来的执行器地址分到同一个触发器组(即把地址加到执行器信息表`XXL_JOB_QRTZ_TRIGGER_GROUP`的同一个记录中).

然后调度中心进行调度时就会从一个执行器信息记录取出地址列表进行路由(详细可以看调度中心的注册模块和调度模块的实现).
