package org.pragmatica.aether.infra.http;

import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.Map;

/**
 * HTTP client infrastructure slice providing outbound HTTP operations.
 * Wraps pragmatica-lite HttpOperations with Slice lifecycle.
 */
public interface HttpClientSlice extends Slice {
    /**
     * Sends a GET request.
     *
     * @param path Path (will be appended to base URL if configured)
     * @return HTTP result with string body
     */
    Promise<HttpResult<String>> get(String path);

    /**
     * Sends a GET request with headers.
     *
     * @param path    Path (will be appended to base URL if configured)
     * @param headers Request headers
     * @return HTTP result with string body
     */
    Promise<HttpResult<String>> get(String path, Map<String, String> headers);

    /**
     * Sends a POST request with JSON body.
     *
     * @param path Path (will be appended to base URL if configured)
     * @param body Request body (JSON string)
     * @return HTTP result with string body
     */
    Promise<HttpResult<String>> post(String path, String body);

    /**
     * Sends a POST request with JSON body and headers.
     *
     * @param path    Path (will be appended to base URL if configured)
     * @param body    Request body (JSON string)
     * @param headers Request headers
     * @return HTTP result with string body
     */
    Promise<HttpResult<String>> post(String path, String body, Map<String, String> headers);

    /**
     * Sends a PUT request with JSON body.
     *
     * @param path Path (will be appended to base URL if configured)
     * @param body Request body (JSON string)
     * @return HTTP result with string body
     */
    Promise<HttpResult<String>> put(String path, String body);

    /**
     * Sends a PUT request with JSON body and headers.
     *
     * @param path    Path (will be appended to base URL if configured)
     * @param body    Request body (JSON string)
     * @param headers Request headers
     * @return HTTP result with string body
     */
    Promise<HttpResult<String>> put(String path, String body, Map<String, String> headers);

    /**
     * Sends a DELETE request.
     *
     * @param path Path (will be appended to base URL if configured)
     * @return HTTP result with string body
     */
    Promise<HttpResult<String>> delete(String path);

    /**
     * Sends a DELETE request with headers.
     *
     * @param path    Path (will be appended to base URL if configured)
     * @param headers Request headers
     * @return HTTP result with string body
     */
    Promise<HttpResult<String>> delete(String path, Map<String, String> headers);

    /**
     * Sends a PATCH request with JSON body.
     *
     * @param path Path (will be appended to base URL if configured)
     * @param body Request body (JSON string)
     * @return HTTP result with string body
     */
    Promise<HttpResult<String>> patch(String path, String body);

    /**
     * Sends a PATCH request with JSON body and headers.
     *
     * @param path    Path (will be appended to base URL if configured)
     * @param body    Request body (JSON string)
     * @param headers Request headers
     * @return HTTP result with string body
     */
    Promise<HttpResult<String>> patch(String path, String body, Map<String, String> headers);

    /**
     * Sends a GET request and returns byte array.
     *
     * @param path Path (will be appended to base URL if configured)
     * @return HTTP result with byte array body
     */
    Promise<HttpResult<byte[]>> getBytes(String path);

    /**
     * Sends a GET request with headers and returns byte array.
     *
     * @param path    Path (will be appended to base URL if configured)
     * @param headers Request headers
     * @return HTTP result with byte array body
     */
    Promise<HttpResult<byte[]>> getBytes(String path, Map<String, String> headers);

    /**
     * Returns the current configuration.
     *
     * @return Configuration
     */
    HttpClientConfig config();

    /**
     * Factory method for JDK-based implementation.
     *
     * @return HttpClientSlice instance with default configuration
     */
    static HttpClientSlice httpClientSlice() {
        return JdkHttpClientSlice.jdkHttpClientSlice();
    }

    /**
     * Factory method for JDK-based implementation with configuration.
     *
     * @param config Client configuration
     * @return HttpClientSlice instance
     */
    static HttpClientSlice httpClientSlice(HttpClientConfig config) {
        return JdkHttpClientSlice.jdkHttpClientSlice(config);
    }

    @Override
    default Promise<Unit> start() {
        return Promise.success(Unit.unit());
    }

    @Override
    default Promise<Unit> stop() {
        return Promise.success(Unit.unit());
    }

    @Override
    default List<SliceMethod< ?, ?>> methods() {
        return List.of();
    }
}
