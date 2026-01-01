package org.pragmatica.aether.http;

import org.pragmatica.lang.Option;

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
    public static Option<HttpMethod> fromString(String method) {
        return switch (method.toUpperCase(Locale.ROOT)) {
            case"GET" -> Option.some(GET);
            case"POST" -> Option.some(POST);
            case"PUT" -> Option.some(PUT);
            case"DELETE" -> Option.some(DELETE);
            case"PATCH" -> Option.some(PATCH);
            case"HEAD" -> Option.some(HEAD);
            case"OPTIONS" -> Option.some(OPTIONS);
            default -> Option.none();
        };
    }
}
