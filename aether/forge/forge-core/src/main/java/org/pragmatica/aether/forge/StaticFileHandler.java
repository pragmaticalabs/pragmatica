package org.pragmatica.aether.forge;

import org.pragmatica.http.CommonContentType;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.server.RequestContext;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.Option;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serves static files from classpath resources.
 */
public final class StaticFileHandler {
    private static final Logger log = LoggerFactory.getLogger(StaticFileHandler.class);

    private static final String STATIC_PREFIX = "static/";
    private static final Map<String, ContentType> CONTENT_TYPES = Map.of(".html",
                                                                         CommonContentType.TEXT_HTML,
                                                                         ".css",
                                                                         CommonContentType.TEXT_CSS,
                                                                         ".js",
                                                                         CommonContentType.TEXT_JAVASCRIPT,
                                                                         ".json",
                                                                         CommonContentType.APPLICATION_JSON,
                                                                         ".png",
                                                                         CommonContentType.IMAGE_PNG,
                                                                         ".svg",
                                                                         CommonContentType.IMAGE_SVG,
                                                                         ".ico",
                                                                         CommonContentType.APPLICATION_OCTET_STREAM);

    private StaticFileHandler() {}

    public static StaticFileHandler staticFileHandler() {
        return new StaticFileHandler();
    }

    public void handle(RequestContext request, ResponseWriter response) {
        var path = request.path();
        // Handle root path
        if (path.equals("/") || path.equals("/index.html")) {
            path = "/index.html";
        }
        // Decode URL before security check to prevent bypass via percent-encoding
        path = URLDecoder.decode(path, StandardCharsets.UTF_8);
        // Security: prevent directory traversal
        if (path.contains("..")) {
            sendError(response, HttpStatus.FORBIDDEN, "Invalid path");
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
                                               sendError(response, HttpStatus.NOT_FOUND, "File not found: " + finalPath);
                                           })
                    .onPresent(content -> sendStaticContent(response, finalPath, content));
    }

    private void sendStaticContent(ResponseWriter response, String path, byte[] content) {
        var contentType = getContentType(path);
        response.header("Cache-Control", "no-cache")
                .write(HttpStatus.OK, content, contentType);
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

    private ContentType getContentType(String path) {
        for (var entry : CONTENT_TYPES.entrySet()) {
            if (path.endsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return CommonContentType.APPLICATION_OCTET_STREAM;
    }

    private void sendError(ResponseWriter response, HttpStatus status, String message) {
        response.write(status, message.getBytes(StandardCharsets.UTF_8), CommonContentType.TEXT_PLAIN);
    }
}
