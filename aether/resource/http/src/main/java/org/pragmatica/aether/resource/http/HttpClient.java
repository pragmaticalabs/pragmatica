package org.pragmatica.aether.resource.http;

import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;

import java.util.Map;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.type.TypeToken.typeToken;

/// HTTP client resource providing outbound HTTP operations.
/// Wraps pragmatica-lite HttpOperations with resource lifecycle.
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

    // ═══ Typed JSON API — abstract methods ═══
    /// Sends a GET request and deserializes the response as JSON.
    <T> Promise<T> getJson(String path, TypeToken<T> responseType, Option<TypeToken<?>> errorType);

    /// Sends a POST request with JSON body and deserializes the response.
    <T> Promise<T> postJson(String path, Object body, TypeToken<T> responseType, Option<TypeToken<?>> errorType);

    /// Sends a PUT request with JSON body and deserializes the response.
    <T> Promise<T> putJson(String path, Object body, TypeToken<T> responseType, Option<TypeToken<?>> errorType);

    /// Sends a PATCH request with JSON body and deserializes the response.
    <T> Promise<T> patchJson(String path, Object body, TypeToken<T> responseType, Option<TypeToken<?>> errorType);

    /// Sends a DELETE request and deserializes the response as JSON.
    <T> Promise<T> deleteJson(String path, TypeToken<T> responseType, Option<TypeToken<?>> errorType);

    /// Sends a DELETE request expecting no response body.
    Promise<Unit> deleteJsonVoid(String path);

    // ═══ Typed JSON API — GET defaults ═══
    /// Sends a GET request and deserializes the response as JSON.
    default <T> Promise<T> getJson(Class<T> type) {
        return getJson("", typeToken(type), none());
    }

    /// Sends a GET request and deserializes the response as JSON.
    default <T> Promise<T> getJson(String path, Class<T> type) {
        return getJson(path, typeToken(type), none());
    }

    /// Sends a GET request and deserializes the response as JSON using TypeToken.
    default <T> Promise<T> getJson(String path, TypeToken<T> type) {
        return getJson(path, type, none());
    }

    /// Sends a GET request, deserializes the response, and parses errors as the given type.
    default <T> Promise<T> getJson(String path, Class<T> type, Class<?> errorType) {
        return getJson(path, typeToken(type), Option.<TypeToken<?>>some(typeToken(errorType)));
    }

    // ═══ Typed JSON API — POST defaults ═══
    /// Sends a POST request with JSON body and deserializes the response.
    default <T> Promise<T> postJson(Object body, Class<T> type) {
        return postJson("", body, typeToken(type), none());
    }

    /// Sends a POST request with JSON body and deserializes the response.
    default <T> Promise<T> postJson(String path, Object body, Class<T> type) {
        return postJson(path, body, typeToken(type), none());
    }

    /// Sends a POST request with JSON body and deserializes the response using TypeToken.
    default <T> Promise<T> postJson(String path, Object body, TypeToken<T> type) {
        return postJson(path, body, type, none());
    }

    /// Sends a POST request with JSON body, deserializes the response, and parses errors as the given type.
    default <T> Promise<T> postJson(String path, Object body, Class<T> type, Class<?> errorType) {
        return postJson(path, body, typeToken(type), Option.<TypeToken<?>>some(typeToken(errorType)));
    }

    // ═══ Typed JSON API — PUT defaults ═══
    /// Sends a PUT request with JSON body and deserializes the response.
    default <T> Promise<T> putJson(Object body, Class<T> type) {
        return putJson("", body, typeToken(type), none());
    }

    /// Sends a PUT request with JSON body and deserializes the response.
    default <T> Promise<T> putJson(String path, Object body, Class<T> type) {
        return putJson(path, body, typeToken(type), none());
    }

    /// Sends a PUT request with JSON body and deserializes the response using TypeToken.
    default <T> Promise<T> putJson(String path, Object body, TypeToken<T> type) {
        return putJson(path, body, type, none());
    }

    /// Sends a PUT request with JSON body, deserializes the response, and parses errors as the given type.
    default <T> Promise<T> putJson(String path, Object body, Class<T> type, Class<?> errorType) {
        return putJson(path, body, typeToken(type), Option.<TypeToken<?>>some(typeToken(errorType)));
    }

    // ═══ Typed JSON API — PATCH defaults ═══
    /// Sends a PATCH request with JSON body and deserializes the response.
    default <T> Promise<T> patchJson(Object body, Class<T> type) {
        return patchJson("", body, typeToken(type), none());
    }

    /// Sends a PATCH request with JSON body and deserializes the response.
    default <T> Promise<T> patchJson(String path, Object body, Class<T> type) {
        return patchJson(path, body, typeToken(type), none());
    }

    /// Sends a PATCH request with JSON body and deserializes the response using TypeToken.
    default <T> Promise<T> patchJson(String path, Object body, TypeToken<T> type) {
        return patchJson(path, body, type, none());
    }

    /// Sends a PATCH request with JSON body, deserializes the response, and parses errors as the given type.
    default <T> Promise<T> patchJson(String path, Object body, Class<T> type, Class<?> errorType) {
        return patchJson(path, body, typeToken(type), Option.<TypeToken<?>>some(typeToken(errorType)));
    }

    // ═══ Typed JSON API — DELETE defaults ═══
    /// Sends a DELETE request and deserializes the response as JSON.
    default <T> Promise<T> deleteJson(Class<T> type) {
        return deleteJson("", typeToken(type), none());
    }

    /// Sends a DELETE request and deserializes the response as JSON.
    default <T> Promise<T> deleteJson(String path, Class<T> type) {
        return deleteJson(path, typeToken(type), none());
    }

    /// Sends a DELETE request and deserializes the response as JSON using TypeToken.
    default <T> Promise<T> deleteJson(String path, TypeToken<T> type) {
        return deleteJson(path, type, none());
    }

    /// Sends a DELETE request, deserializes the response, and parses errors as the given type.
    default <T> Promise<T> deleteJson(String path, Class<T> type, Class<?> errorType) {
        return deleteJson(path, typeToken(type), Option.<TypeToken<?>>some(typeToken(errorType)));
    }

    /// Sends a DELETE request expecting no response body.
    default Promise<Unit> deleteJson() {
        return deleteJsonVoid("");
    }

    /// Sends a DELETE request expecting no response body.
    default Promise<Unit> deleteJson(String path) {
        return deleteJsonVoid(path);
    }

    /// Factory method for JDK-based implementation.
    ///
    /// @return HttpClient instance with default configuration
    static HttpClient httpClient() {
        return JdkHttpClient.jdkHttpClient();
    }

    /// Factory method for JDK-based implementation with configuration.
    ///
    /// @param config Client configuration
    /// @return HttpClient instance
    static HttpClient httpClient(HttpClientConfig config) {
        return JdkHttpClient.jdkHttpClient(config);
    }
}
