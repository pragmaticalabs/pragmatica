package org.pragmatica.http.server.impl;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import org.pragmatica.http.server.HttpStatus;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.Cause;

import java.util.HashMap;
import java.util.Map;

/**
 * Netty implementation of ResponseWriter.
 */
public final class NettyResponseWriter implements ResponseWriter {
    private static final String APPLICATION_JSON = "application/json";

    private final ChannelHandlerContext ctx;
    private final boolean keepAlive;
    private final Map<String, String> headers = new HashMap<>();
    private boolean responseSent = false;

    public NettyResponseWriter(ChannelHandlerContext ctx, boolean keepAlive) {
        this.ctx = ctx;
        this.keepAlive = keepAlive;
    }

    @Override
    public void ok(ByteBuf content, String contentType) {
        respond(HttpStatus.OK, content, contentType);
    }

    @Override
    public void ok(String json) {
        respond(HttpStatus.OK, json);
    }

    @Override
    public void noContent() {
        sendResponse(HttpStatus.NO_CONTENT, Unpooled.EMPTY_BUFFER, null);
    }

    @Override
    public void error(HttpStatus status, String message) {
        var json = "{\"error\":\"" + escapeJson(message) + "\"}";
        respond(status, json);
    }

    @Override
    public void notFound() {
        error(HttpStatus.NOT_FOUND, "Not Found");
    }

    @Override
    public void badRequest(String message) {
        error(HttpStatus.BAD_REQUEST, message);
    }

    @Override
    public void internalError(Cause cause) {
        error(HttpStatus.INTERNAL_SERVER_ERROR, cause.message());
    }

    @Override
    public void respond(HttpStatus status, String json) {
        var content = Unpooled.copiedBuffer(json, CharsetUtil.UTF_8);
        respond(status, content, APPLICATION_JSON);
    }

    @Override
    public void respond(HttpStatus status, ByteBuf content, String contentType) {
        sendResponse(status, content, contentType);
    }

    @Override
    public ResponseWriter header(String name, String value) {
        headers.put(name, value);
        return this;
    }

    private void sendResponse(HttpStatus status, ByteBuf content, String contentType) {
        if (responseSent) {
            content.release();
            return;
        }
        responseSent = true;

        var nettyStatus = HttpResponseStatus.valueOf(status.code());
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, nettyStatus, content);

        // Set content headers
        if (contentType != null) {
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        }
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());

        // Set custom headers
        headers.forEach((name, value) -> response.headers().set(name, value));

        // Handle keep-alive
        if (keepAlive) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            ctx.writeAndFlush(response);
        } else {
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        var sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < ' ') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
