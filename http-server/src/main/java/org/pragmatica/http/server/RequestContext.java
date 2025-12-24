package org.pragmatica.http.server;

import io.netty.buffer.ByteBuf;
import io.netty.util.CharsetUtil;
import org.pragmatica.lang.Option;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Immutable HTTP request context.
 * <p>
 * Provides access to request method, path, query parameters, headers, and body.
 *
 * @param method  HTTP method
 * @param path    request path (without query string)
 * @param query   raw query string (may be null)
 * @param headers request headers (case-insensitive keys)
 * @param body    request body as ByteBuf
 */
public record RequestContext(
        HttpMethod method,
        String path,
        String query,
        Map<String, String> headers,
        ByteBuf body
) {
    /**
     * Get header value by name (case-insensitive).
     */
    public Option<String> header(String name) {
        return Option.option(headers.get(name.toLowerCase()));
    }

    /**
     * Get query parameter value by name.
     */
    public Option<String> queryParam(String name) {
        if (query == null || query.isEmpty()) {
            return Option.empty();
        }

        var params = parseQueryParams();
        return Option.option(params.get(name));
    }

    /**
     * Get all query parameters as a map.
     */
    public Map<String, String> queryParams() {
        if (query == null || query.isEmpty()) {
            return Collections.emptyMap();
        }
        return parseQueryParams();
    }

    /**
     * Get request body as UTF-8 string.
     */
    public String bodyAsString() {
        if (body == null || body.readableBytes() == 0) {
            return "";
        }
        return body.toString(CharsetUtil.UTF_8);
    }

    /**
     * Check if request has body.
     */
    public boolean hasBody() {
        return body != null && body.readableBytes() > 0;
    }

    /**
     * Get Content-Type header.
     */
    public Option<String> contentType() {
        return header("content-type");
    }

    /**
     * Check if request content type is JSON.
     */
    public boolean isJson() {
        return contentType()
                .map(ct -> ct.contains("application/json"))
                .fold(() -> false, bool -> bool);
    }

    /**
     * Release the body buffer. Must be called when done with the request.
     */
    public void release() {
        if (body != null && body.refCnt() > 0) {
            body.release();
        }
    }

    private Map<String, String> parseQueryParams() {
        var params = new HashMap<String, String>();
        var pairs = query.split("&");

        for (var pair : pairs) {
            var idx = pair.indexOf('=');
            if (idx > 0) {
                var key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                var value = idx < pair.length() - 1
                            ? URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8)
                            : "";
                params.put(key, value);
            } else if (!pair.isEmpty()) {
                params.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
            }
        }

        return params;
    }
}
