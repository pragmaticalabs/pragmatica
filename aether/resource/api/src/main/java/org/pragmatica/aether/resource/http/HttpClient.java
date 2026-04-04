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
    Promise<HttpResult<String>> get(String path);
    Promise<HttpResult<String>> get(String path, Map<String, String> headers);
    Promise<HttpResult<String>> post(String path, String body);
    Promise<HttpResult<String>> post(String path, String body, Map<String, String> headers);
    Promise<HttpResult<String>> put(String path, String body);
    Promise<HttpResult<String>> put(String path, String body, Map<String, String> headers);
    Promise<HttpResult<String>> delete(String path);
    Promise<HttpResult<String>> delete(String path, Map<String, String> headers);
    Promise<HttpResult<String>> patch(String path, String body);
    Promise<HttpResult<String>> patch(String path, String body, Map<String, String> headers);
    Promise<HttpResult<byte[]>> getBytes(String path);
    Promise<HttpResult<byte[]>> getBytes(String path, Map<String, String> headers);
    HttpClientConfig config();
    <T> Promise<T> getJson(String path, TypeToken<T> responseType, Option<TypeToken<?>> errorType);
    <T> Promise<T> postJson(String path, Object body, TypeToken<T> responseType, Option<TypeToken<?>> errorType);
    <T> Promise<T> putJson(String path, Object body, TypeToken<T> responseType, Option<TypeToken<?>> errorType);
    <T> Promise<T> patchJson(String path, Object body, TypeToken<T> responseType, Option<TypeToken<?>> errorType);
    <T> Promise<T> deleteJson(String path, TypeToken<T> responseType, Option<TypeToken<?>> errorType);
    Promise<Unit> deleteJsonVoid(String path);

    default <T> Promise<T> getJson(Class<T> type) {
        return getJson("", typeToken(type), none());
    }

    default <T> Promise<T> getJson(String path, Class<T> type) {
        return getJson(path, typeToken(type), none());
    }

    default <T> Promise<T> getJson(String path, TypeToken<T> type) {
        return getJson(path, type, none());
    }

    default <T> Promise<T> getJson(String path, Class<T> type, Class<?> errorType) {
        return getJson(path, typeToken(type), Option.<TypeToken<?>>some(typeToken(errorType)));
    }

    default <T> Promise<T> postJson(Object body, Class<T> type) {
        return postJson("", body, typeToken(type), none());
    }

    default <T> Promise<T> postJson(String path, Object body, Class<T> type) {
        return postJson(path, body, typeToken(type), none());
    }

    default <T> Promise<T> postJson(String path, Object body, TypeToken<T> type) {
        return postJson(path, body, type, none());
    }

    default <T> Promise<T> postJson(String path, Object body, Class<T> type, Class<?> errorType) {
        return postJson(path, body, typeToken(type), Option.<TypeToken<?>>some(typeToken(errorType)));
    }

    default <T> Promise<T> putJson(Object body, Class<T> type) {
        return putJson("", body, typeToken(type), none());
    }

    default <T> Promise<T> putJson(String path, Object body, Class<T> type) {
        return putJson(path, body, typeToken(type), none());
    }

    default <T> Promise<T> putJson(String path, Object body, TypeToken<T> type) {
        return putJson(path, body, type, none());
    }

    default <T> Promise<T> putJson(String path, Object body, Class<T> type, Class<?> errorType) {
        return putJson(path, body, typeToken(type), Option.<TypeToken<?>>some(typeToken(errorType)));
    }

    default <T> Promise<T> patchJson(Object body, Class<T> type) {
        return patchJson("", body, typeToken(type), none());
    }

    default <T> Promise<T> patchJson(String path, Object body, Class<T> type) {
        return patchJson(path, body, typeToken(type), none());
    }

    default <T> Promise<T> patchJson(String path, Object body, TypeToken<T> type) {
        return patchJson(path, body, type, none());
    }

    default <T> Promise<T> patchJson(String path, Object body, Class<T> type, Class<?> errorType) {
        return patchJson(path, body, typeToken(type), Option.<TypeToken<?>>some(typeToken(errorType)));
    }

    default <T> Promise<T> deleteJson(Class<T> type) {
        return deleteJson("", typeToken(type), none());
    }

    default <T> Promise<T> deleteJson(String path, Class<T> type) {
        return deleteJson(path, typeToken(type), none());
    }

    default <T> Promise<T> deleteJson(String path, TypeToken<T> type) {
        return deleteJson(path, type, none());
    }

    default <T> Promise<T> deleteJson(String path, Class<T> type, Class<?> errorType) {
        return deleteJson(path, typeToken(type), Option.<TypeToken<?>>some(typeToken(errorType)));
    }

    default Promise<Unit> deleteJson() {
        return deleteJsonVoid("");
    }

    default Promise<Unit> deleteJson(String path) {
        return deleteJsonVoid(path);
    }
}
