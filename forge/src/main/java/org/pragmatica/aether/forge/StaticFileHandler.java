package org.pragmatica.aether.forge;

import org.pragmatica.lang.Option;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * Serves static files from classpath resources.
 */
@Sharable
public final class StaticFileHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final Logger log = LoggerFactory.getLogger(StaticFileHandler.class);

    private static final String STATIC_PREFIX = "static/";
    private static final Map<String, String> CONTENT_TYPES = Map.of(".html",
                                                                    "text/html; charset=utf-8",
                                                                    ".css",
                                                                    "text/css; charset=utf-8",
                                                                    ".js",
                                                                    "application/javascript; charset=utf-8",
                                                                    ".json",
                                                                    "application/json; charset=utf-8",
                                                                    ".png",
                                                                    "image/png",
                                                                    ".svg",
                                                                    "image/svg+xml",
                                                                    ".ico",
                                                                    "image/x-icon");

    private StaticFileHandler() {}

    public static StaticFileHandler staticFileHandler() {
        return new StaticFileHandler();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        var path = request.uri();
        // Handle root path
        if (path.equals("/") || path.equals("/index.html")) {
            path = "/index.html";
        }
        // Remove query string
        var queryIdx = path.indexOf('?');
        if (queryIdx >= 0) {
            path = path.substring(0, queryIdx);
        }
        // Decode URL before security check to prevent bypass via percent-encoding
        path = URLDecoder.decode(path, StandardCharsets.UTF_8);
        // Security: prevent directory traversal
        if (path.contains("..")) {
            sendError(ctx, FORBIDDEN, "Invalid path");
            return;
        }
        // Load from classpath
        var finalPath = path;
        // capture for lambda
        var resourcePath = STATIC_PREFIX + (finalPath.startsWith("/")
                                            ? finalPath.substring(1)
                                            : finalPath);
        loadResource(resourcePath).onEmpty(() -> {
                                               log.debug("Static file not found: {}", resourcePath);
                                               sendError(ctx, NOT_FOUND, "File not found: " + finalPath);
                                           })
                    .onPresent(content -> sendStaticContent(ctx, finalPath, content));
    }

    private void sendStaticContent(ChannelHandlerContext ctx, String path, byte[] content) {
        var contentType = getContentType(path);
        var buffer = Unpooled.wrappedBuffer(content);
        var response = new DefaultFullHttpResponse(HTTP_1_1, OK, buffer);
        response.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, contentType);
        response.headers()
                .set(HttpHeaderNames.CONTENT_LENGTH,
                     buffer.readableBytes());
        response.headers()
                .set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
        ctx.writeAndFlush(response)
           .addListener(ChannelFutureListener.CLOSE);
    }

    private Option<byte[]> loadResource(String path) {
        try (InputStream is = getClass().getClassLoader()
                                      .getResourceAsStream(path)) {
            if (is == null) {
                return Option.empty();
            }
            return Option.option(is.readAllBytes());
        } catch (IOException e) {
            log.error("Error loading resource: {}", path, e);
            return Option.empty();
        }
    }

    private String getContentType(String path) {
        for (var entry : CONTENT_TYPES.entrySet()) {
            if (path.endsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return "application/octet-stream";
    }

    private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        var content = Unpooled.copiedBuffer(message, StandardCharsets.UTF_8);
        var response = new DefaultFullHttpResponse(HTTP_1_1, status, content);
        response.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
        response.headers()
                .set(HttpHeaderNames.CONTENT_LENGTH,
                     content.readableBytes());
        ctx.writeAndFlush(response)
           .addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Error in static file handler: {}", cause.getMessage());
        ctx.close();
    }
}
