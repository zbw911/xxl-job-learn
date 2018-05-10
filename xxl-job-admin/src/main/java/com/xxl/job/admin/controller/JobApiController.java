package com.xxl.job.admin.controller;

import com.xxl.job.admin.controller.annotation.PermessionLimit;
import com.xxl.job.core.biz.AdminBiz;
import com.xxl.job.core.rpc.codec.RpcRequest;
import com.xxl.job.core.rpc.codec.RpcResponse;
import com.xxl.job.core.rpc.netcom.NetComServerFactory;
import com.xxl.job.core.util.HttpClientUtil;
import com.xxl.job.core.util.JacksonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Created by xuxueli on 17/5/10.
 */
@Controller
public class JobApiController {
    private static Logger logger = LoggerFactory.getLogger(JobApiController.class);

    private RpcResponse doInvoke(HttpServletRequest request) {
        try {
            // deserialize request
            String requestStr = HttpClientUtil.readStr(request);

            if (requestStr == null || requestStr.length() == 0) {
                RpcResponse rpcResponse = new RpcResponse();
                rpcResponse.setError("RpcRequest byte[] is null");
                return rpcResponse;
            }
            RpcRequest rpcRequest = (RpcRequest) JacksonUtil.readValue(requestStr, RpcRequest.class);

            // invoke
            RpcResponse rpcResponse = NetComServerFactory.invokeService(rpcRequest, null);
            return rpcResponse;
        } catch (Exception e) {
            logger.error(e.getMessage(), e);

            RpcResponse rpcResponse = new RpcResponse();
            rpcResponse.setError("Server-error:" + e.getMessage());
            return rpcResponse;
        }
    }

    @RequestMapping(AdminBiz.MAPPING)
    @PermessionLimit(limit = false)
    public void api(HttpServletRequest request, HttpServletResponse response) throws IOException {

        // invoke
        RpcResponse rpcResponse = doInvoke(request);

        // serialize response
        String responseStr = JacksonUtil.writeValueAsString(rpcResponse);

        response.setContentType("text/html;charset=utf-8");
        response.setStatus(HttpServletResponse.SC_OK);
        //baseRequest.setHandled(true);

        OutputStream out = response.getOutputStream();
        out.write(responseStr.getBytes());
        out.flush();
    }

}
