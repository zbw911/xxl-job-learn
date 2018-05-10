package com.xxl.job.core.rpc.netcom.jetty.server;

import com.xxl.job.core.rpc.codec.RpcRequest;
import com.xxl.job.core.rpc.codec.RpcResponse;
import com.xxl.job.core.rpc.netcom.NetComServerFactory;
import com.xxl.job.core.util.HttpClientUtil;
import com.xxl.job.core.util.JacksonUtil;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;

/**
 * jetty handler
 *
 * @author xuxueli 2015-11-19 22:32:36
 */
public class JettyServerHandler extends AbstractHandler {
    private static Logger logger = LoggerFactory.getLogger(JettyServerHandler.class);

    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {

        // invoke
        RpcResponse rpcResponse = doInvoke(request);

        // serialize response
        String responseStr = JacksonUtil.writeValueAsString(rpcResponse);

        response.setContentType("text/html;charset=utf-8");
        response.setStatus(HttpServletResponse.SC_OK);
        baseRequest.setHandled(true);

        OutputStream out = response.getOutputStream();
        out.write(responseStr.getBytes());
        out.flush();

    }

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

}
