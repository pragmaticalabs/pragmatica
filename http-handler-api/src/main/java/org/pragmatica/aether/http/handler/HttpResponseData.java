package org.pragmatica.aether.http.handler;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/**
 * Raw HTTP response data returned through SliceInvoker.
 * <p>
 * Note: The body byte array is defensively copied to ensure immutability.
 *
 * @param statusCode HTTP status code (e.g., 200, 404, 500)
 * @param headers    response headers
 * @param body       response body bytes
 */
public record HttpResponseData(int statusCode,
                               Map<String, String> headers,
                               byte[] body) {
    private static final byte[] EMPTY_BODY = new byte[0];
    private static final Map<String, String> JSON_HEADERS = Map.of("Content-Type", "application/json; charset=UTF-8");
    private static final Map<String, String> TEXT_HEADERS = Map.of("Content-Type", "text/plain; charset=UTF-8");

    /**
     * Canonical constructor with defensive copy of body.
     */
    public HttpResponseData {
        Objects.requireNonNull(headers, "headers");
        body = (body == null || body.length == 0)
               ? EMPTY_BODY
               : body.clone();
    }

    /**
     * Defensive copy on access.
     */
    @Override
    public byte[] body() {
        return body.length == 0
               ? EMPTY_BODY
               : body.clone();
    }

    /**
     * Create successful JSON response.
     */
    public static HttpResponseData ok(byte[] body) {
        return new HttpResponseData(200, JSON_HEADERS, body);
    }

    /**
     * Create successful JSON response from string.
     */
    public static HttpResponseData ok(String body) {
        return ok(body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Create 201 Created response.
     */
    public static HttpResponseData created(byte[] body) {
        return new HttpResponseData(201, JSON_HEADERS, body);
    }

    /**
     * Create 204 No Content response.
     */
    public static HttpResponseData noContent() {
        return new HttpResponseData(204, Map.of(), EMPTY_BODY);
    }

    /**
     * Create 400 Bad Request response.
     */
    public static HttpResponseData badRequest(String message) {
        return new HttpResponseData(400, TEXT_HEADERS, message.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Create 404 Not Found response.
     */
    public static HttpResponseData notFound(String message) {
        return new HttpResponseData(404, TEXT_HEADERS, message.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Create 500 Internal Server Error response.
     */
    public static HttpResponseData internalError(String message) {
        return new HttpResponseData(500, TEXT_HEADERS, message.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Create custom response.
     */
    public static HttpResponseData httpResponseData(int statusCode, Map<String, String> headers, byte[] body) {
        return new HttpResponseData(statusCode, headers, body);
    }
}
