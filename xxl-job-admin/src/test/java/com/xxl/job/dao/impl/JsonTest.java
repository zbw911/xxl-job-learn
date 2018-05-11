package com.xxl.job.dao.impl;

import com.xxl.job.core.biz.model.LogResult;
import com.xxl.job.core.rpc.codec.RpcRequest;
import com.xxl.job.core.util.JacksonUtil;
import org.junit.Test;

public class JsonTest {
    @Test
    public void name() {
        String requestStr = "{\"serverAddress\":\"http://localhost:8080/api\",\"createMillisTime\":1526022306105,\"accessToken\":null,\"className\":\"com.xxl.job.core.biz.AdminBiz\",\"methodName\":\"callback\",\"parameterTypes\":[\"java.util.List\"],\"parameters\":[[{\"logId\":188605,\"executeResult\":{\"code\":200,\"msg\":null,\"content\":null}}]]}";
        System.out.println(requestStr);
        //requestStr = "{serverAddress='http://127.0.0.1:8080/api', createMillisTime=1526018505399, accessToken='', className='com.xxl.job.core.biz.AdminBiz', methodName='callback', parameterTypes=[java.util.List], parameters=[[{logId=188593, executeResult={code=200, msg=null, content=null}}]]}";
        RpcRequest rpcRequest = (RpcRequest) JacksonUtil.readValue(requestStr, RpcRequest.class);

    }

    @Test
    public void testLogModel() {
        String requestStr = "{\"fromLineNum\":1,\"toLineNum\":3,\"logContent\":\"2018-05-11 15:12:33 [E:\\\\Github\\\\zbw911\\\\xxl-job-csharp-client\\\\JobClient\\\\log\\\\XxlJobLogger.cs]-[log]-[30]-[10] test\\n2018-05-11 15:12:33 [E:\\\\Github\\\\zbw911\\\\xxl-job-csharp-client\\\\JobClient\\\\log\\\\XxlJobLogger.cs]-[log]-[30]-[10] <br>----------- xxl-job job execute end(finish) -----------<br>----------- ReturnT:ReturnT [code=200, msg=, content=]\\n\",\"end\":false}";

        LogResult rpcRequest = (LogResult) JacksonUtil.readValue(requestStr, LogResult.class);

        LogResult logResult = new LogResult(1, 1, "text", true);

        String s = JacksonUtil.writeValueAsString(logResult);
        System.out.println(s);
    }
}
