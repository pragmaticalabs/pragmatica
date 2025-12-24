package org.pragmatica.aether.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import org.pragmatica.lang.Cause;
import org.pragmatica.net.serialization.Serializer;

/**
 * Writes HTTP responses to the channel.
 */
public interface ResponseWriter {

    /**
     * Write a successful response with the given body.
     */
    void writeSuccess(ChannelHandlerContext ctx, Object body);

    /**
     * Write an error response.
     */
    void writeError(ChannelHandlerContext ctx, HttpResponseStatus status, Cause cause);

    /**
     * Create a ResponseWriter with the given serializer.
     */
    static ResponseWriter responseWriter(Serializer serializer) {
        return new ResponseWriterImpl(serializer);
    }
}

class ResponseWriterImpl implements ResponseWriter {

    private static final String CONTENT_TYPE_JSON = "application/json";

    private final Serializer serializer;

    ResponseWriterImpl(Serializer serializer) {
        this.serializer = serializer;
    }

    @Override
    public void writeSuccess(ChannelHandlerContext ctx, Object body) {
        ByteBuf content;
        if (body == null) {
            content = Unpooled.EMPTY_BUFFER;
        } else {
            content = Unpooled.buffer();
            serializer.write(content, body);
        }

        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, CONTENT_TYPE_JSON);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());

        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void writeError(ChannelHandlerContext ctx, HttpResponseStatus status, Cause cause) {
        var errorJson = "{\"error\":\"" + escapeJson(cause.message()) + "\"}";
        var content = Unpooled.copiedBuffer(errorJson, CharsetUtil.UTF_8);

        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, content);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, CONTENT_TYPE_JSON);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());

        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
