package org.wso2.carbon.transport.http.netty.listener;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

import java.util.List;

/**
 * Custom Http Content Compressor to handle the content-length and transfer encoding.
 */
public class CustomHttpContentCompressor extends HttpContentCompressor {

    private HttpMethod method;
    private boolean chunkDisabled = false;

    public CustomHttpContentCompressor() {
        super();
    }

    public CustomHttpContentCompressor(boolean chunkDisabled) {
        super();
        this.chunkDisabled = chunkDisabled;
    }

    @Override
    protected Result beginEncode(HttpResponse headers, String acceptEncoding) throws Exception {
        if (chunkDisabled) {
            return null;
        }
        String allowHeader = headers.headers().get("Allow");
        String contentLength = headers.headers().get("Content-Length");
        if (method == HttpMethod.OPTIONS && allowHeader != null && contentLength.equals("0")) {
            return null;
        }
        return super.beginEncode(headers, acceptEncoding);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, HttpRequest msg, List<Object> out)
            throws Exception {
        this.method = msg.method();
        super.decode(ctx, msg, out);
    }
}
