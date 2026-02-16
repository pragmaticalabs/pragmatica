package org.pragmatica.aether.resource.http;

import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Promise;

import java.util.Map;

/// HTTP client infrastructure slice providing outbound HTTP operations.
/// Wraps pragmatica-lite HttpOperations with Slice lifecycle.
public interface HttpClient {
    /// Sends a GET request.
    ///
    /// @param path Path (will be appended to base URL if configured)
    /// @return HTTP result with string body
    Promise<HttpResult<String>> get(String path);

    /// Sends a GET request with headers.
    ///
    /// @param path    Path (will be appended to base URL if configured)
    /// @param headers Request headers
    /// @return HTTP result with string body
    Promise<HttpResult<String>> get(String path, Map<String, String> headers);

    /// Sends a POST request with JSON body.
    ///
    /// @param path Path (will be appended to base URL if configured)
    /// @param body Request body (JSON string)
    /// @return HTTP result with string body
    Promise<HttpResult<String>> post(String path, String body);

    /// Sends a POST request with JSON body and headers.
    ///
    /// @param path    Path (will be appended to base URL if configured)
    /// @param body    Request body (JSON string)
    /// @param headers Request headers
    /// @return HTTP result with string body
    Promise<HttpResult<String>> post(String path, String body, Map<String, String> headers);

    /// Sends a PUT request with JSON body.
    ///
    /// @param path Path (will be appended to base URL if configured)
    /// @param body Request body (JSON string)
    /// @return HTTP result with string body
    Promise<HttpResult<String>> put(String path, String body);

    /// Sends a PUT request with JSON body and headers.
    ///
    /// @param path    Path (will be appended to base URL if configured)
    /// @param body    Request body (JSON string)
    /// @param headers Request headers
    /// @return HTTP result with string body
    Promise<HttpResult<String>> put(String path, String body, Map<String, String> headers);

    /// Sends a DELETE request.
    ///
    /// @param path Path (will be appended to base URL if configured)
    /// @return HTTP result with string body
    Promise<HttpResult<String>> delete(String path);

    /// Sends a DELETE request with headers.
    ///
    /// @param path    Path (will be appended to base URL if configured)
    /// @param headers Request headers
    /// @return HTTP result with string body
    Promise<HttpResult<String>> delete(String path, Map<String, String> headers);

    /// Sends a PATCH request with JSON body.
    ///
    /// @param path Path (will be appended to base URL if configured)
    /// @param body Request body (JSON string)
    /// @return HTTP result with string body
    Promise<HttpResult<String>> patch(String path, String body);

    /// Sends a PATCH request with JSON body and headers.
    ///
    /// @param path    Path (will be appended to base URL if configured)
    /// @param body    Request body (JSON string)
    /// @param headers Request headers
    /// @return HTTP result with string body
    Promise<HttpResult<String>> patch(String path, String body, Map<String, String> headers);

    /// Sends a GET request and returns byte array.
    ///
    /// @param path Path (will be appended to base URL if configured)
    /// @return HTTP result with byte array body
    Promise<HttpResult<byte[]>> getBytes(String path);

    /// Sends a GET request with headers and returns byte array.
    ///
    /// @param path    Path (will be appended to base URL if configured)
    /// @param headers Request headers
    /// @return HTTP result with byte array body
    Promise<HttpResult<byte[]>> getBytes(String path, Map<String, String> headers);

    /// Returns the current configuration.
    ///
    /// @return Configuration
    HttpClientConfig config();
}
