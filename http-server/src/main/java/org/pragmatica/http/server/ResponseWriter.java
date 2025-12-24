package org.pragmatica.http.server;

import io.netty.buffer.ByteBuf;
import org.pragmatica.lang.Cause;

/**
 * Response writing interface for HTTP handlers.
 * <p>
 * Provides methods to send various HTTP responses.
 * Each response method can only be called once per request.
 */
public interface ResponseWriter {

    /**
     * Send successful response with content.
     *
     * @param content     response body
     * @param contentType Content-Type header value
     */
    void ok(ByteBuf content, String contentType);

    /**
     * Send successful JSON response.
     *
     * @param json JSON string
     */
    void ok(String json);

    /**
     * Send 204 No Content response.
     */
    void noContent();

    /**
     * Send error response with custom status and message.
     *
     * @param status  HTTP status code
     * @param message error message
     */
    void error(HttpStatus status, String message);

    /**
     * Send 404 Not Found response.
     */
    void notFound();

    /**
     * Send 400 Bad Request response.
     *
     * @param message error message
     */
    void badRequest(String message);

    /**
     * Send 500 Internal Server Error response.
     *
     * @param cause error cause
     */
    void internalError(Cause cause);

    /**
     * Send response with custom status and JSON body.
     *
     * @param status HTTP status
     * @param json   JSON response body
     */
    void respond(HttpStatus status, String json);

    /**
     * Send response with custom status, body, and content type.
     *
     * @param status      HTTP status
     * @param content     response body
     * @param contentType Content-Type header value
     */
    void respond(HttpStatus status, ByteBuf content, String contentType);

    /**
     * Add header to the response.
     * Must be called before any response method.
     *
     * @param name  header name
     * @param value header value
     * @return this writer for chaining
     */
    ResponseWriter header(String name, String value);
}
