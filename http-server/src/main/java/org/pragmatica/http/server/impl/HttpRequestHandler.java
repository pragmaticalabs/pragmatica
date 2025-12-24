package org.pragmatica.http.server.impl;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import org.pragmatica.http.server.HttpMethod;
import org.pragmatica.http.server.HttpStatus;
import org.pragmatica.http.server.RequestContext;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.utils.Causes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Netty channel handler that processes HTTP requests.
 */
public final class HttpRequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final Logger LOG = LoggerFactory.getLogger(HttpRequestHandler.class);

    private final BiConsumer<RequestContext, ResponseWriter> handler;

    public HttpRequestHandler(BiConsumer<RequestContext, ResponseWriter> handler) {
        this.handler = handler;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (!request.decoderResult().isSuccess()) {
            sendBadRequest(ctx, "Invalid HTTP request");
            return;
        }

        var keepAlive = HttpUtil.isKeepAlive(request);
        RequestContext context = null;
        try {
            context = createRequestContext(request);
            var writer = new NettyResponseWriter(ctx, keepAlive);
            handler.accept(context, writer);
        } catch (Exception e) {
            LOG.error("Handler error", e);
            new NettyResponseWriter(ctx, keepAlive).internalError(Causes.fromThrowable(e));
        } finally {
            if (context != null) {
                context.release();
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOG.error("Channel error", cause);
        ctx.close();
    }

    private RequestContext createRequestContext(FullHttpRequest request) {
        var uri = request.uri();
        var queryIndex = uri.indexOf('?');

        String path;
        String query;
        if (queryIndex >= 0) {
            path = uri.substring(0, queryIndex);
            query = uri.substring(queryIndex + 1);
        } else {
            path = uri;
            query = null;
        }

        var method = HttpMethod.from(request.method().name());
        var headers = extractHeaders(request);
        var body = request.content().retain();

        return new RequestContext(method, path, query, headers, body);
    }

    private Map<String, String> extractHeaders(FullHttpRequest request) {
        var headers = new HashMap<String, String>();
        for (var entry : request.headers()) {
            headers.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
        }
        return headers;
    }

    private void sendBadRequest(ChannelHandlerContext ctx, String message) {
        var writer = new NettyResponseWriter(ctx, false);
        writer.error(HttpStatus.BAD_REQUEST, message);
    }
}
