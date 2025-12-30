package org.pragmatica.http.server;

import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.QueryParams;

/**
 * HTTP request context providing access to request data.
 */
public interface RequestContext {
    /**
     * HTTP method.
     */
    HttpMethod method();

    /**
     * Request path (without query string).
     */
    String path();

    /**
     * Request headers.
     */
    Headers headers();

    /**
     * Query parameters.
     */
    QueryParams queryParams();

    /**
     * Request body as bytes.
     */
    byte[] body();

    /**
     * Request body as UTF-8 string.
     */
    default String bodyAsString() {
        var bytes = body();
        return bytes.length == 0
               ? ""
               : new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Check if request has a body.
     */
    default boolean hasBody() {
        return body().length > 0;
    }
}
