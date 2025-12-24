package org.pragmatica.http.server;

/**
 * HTTP request methods.
 */
public enum HttpMethod {
    GET,
    POST,
    PUT,
    DELETE,
    PATCH,
    HEAD,
    OPTIONS,
    TRACE,
    CONNECT;

    /**
     * Parse HTTP method from string.
     */
    public static HttpMethod from(String method) {
        return switch (method.toUpperCase()) {
            case "GET" -> GET;
            case "POST" -> POST;
            case "PUT" -> PUT;
            case "DELETE" -> DELETE;
            case "PATCH" -> PATCH;
            case "HEAD" -> HEAD;
            case "OPTIONS" -> OPTIONS;
            case "TRACE" -> TRACE;
            case "CONNECT" -> CONNECT;
            default -> throw new IllegalArgumentException("Unknown HTTP method: " + method);
        };
    }
}
