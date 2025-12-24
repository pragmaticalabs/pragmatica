package org.pragmatica.aether.http;

import java.util.Locale;

/**
 * HTTP methods supported by the router.
 */
public enum HttpMethod {
    GET,
    POST,
    PUT,
    DELETE,
    PATCH,
    HEAD,
    OPTIONS;

    public static HttpMethod fromString(String method) {
        return switch (method.toUpperCase(Locale.ROOT)) {
            case "GET" -> GET;
            case "POST" -> POST;
            case "PUT" -> PUT;
            case "DELETE" -> DELETE;
            case "PATCH" -> PATCH;
            case "HEAD" -> HEAD;
            case "OPTIONS" -> OPTIONS;
            default -> throw new IllegalArgumentException("Unknown HTTP method: " + method);
        };
    }
}
