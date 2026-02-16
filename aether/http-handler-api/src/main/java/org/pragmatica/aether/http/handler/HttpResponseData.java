package org.pragmatica.aether.http.handler;

import org.pragmatica.lang.Result;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

import static org.pragmatica.lang.Result.success;

/// Raw HTTP response data returned through SliceInvoker.
///
/// Note: The body byte array is defensively copied to ensure immutability.
///
/// @param statusCode HTTP status code (e.g., 200, 404, 500)
/// @param headers    response headers
/// @param body       response body bytes
public record HttpResponseData(int statusCode,
                               Map<String, String> headers,
                               byte[] body) {
    private static final byte[] EMPTY_BODY = new byte[0];
    private static final Map<String, String> JSON_HEADERS = Map.of("Content-Type", "application/json; charset=UTF-8");
    private static final Map<String, String> TEXT_HEADERS = Map.of("Content-Type", "text/plain; charset=UTF-8");

    /// Canonical constructor with defensive copy of body.
    public HttpResponseData {
        Objects.requireNonNull(headers, "headers");
        body = (body == null || body.length == 0)
               ? EMPTY_BODY
               : body.clone();
    }

    /// Defensive copy on access.
    @Override
    public byte[] body() {
        return body.length == 0
               ? EMPTY_BODY
               : body.clone();
    }

    /// Create validated response with custom headers.
    public static Result<HttpResponseData> httpResponseData(Result<Integer> statusCode,
                                                            Result<Map<String, String>> headers,
                                                            Result<byte[]> body) {
        return Result.all(statusCode, headers, body)
                     .map(HttpResponseData::new);
    }

    /// Create response with JSON content type and byte body.
    public static HttpResponseData httpResponseData(int statusCode, byte[] body) {
        return httpResponseData(statusCode, JSON_HEADERS, body);
    }

    /// Create response with text content type and string body.
    public static HttpResponseData httpResponseData(int statusCode, String body) {
        return httpResponseData(statusCode, TEXT_HEADERS, body.getBytes(StandardCharsets.UTF_8));
    }

    /// Create response with no body.
    public static HttpResponseData httpResponseData(int statusCode) {
        return httpResponseData(statusCode, Map.of(), EMPTY_BODY);
    }

    /// Create response with custom headers.
    public static HttpResponseData httpResponseData(int statusCode, Map<String, String> headers, byte[] body) {
        return Result.all(success(statusCode),
                          success(headers),
                          success(body))
                     .map(HttpResponseData::new)
                     .unwrap();
    }
}
