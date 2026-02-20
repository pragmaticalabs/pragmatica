package org.pragmatica.aether.resource.http;

import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.http.JdkHttpOperations;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.parse.Network;
import org.pragmatica.lang.type.TypeToken;

import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.time.Duration;
import java.util.Map;
import java.util.function.BiFunction;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Unit.unit;

/// JDK HttpClient-based implementation of HttpClient.
/// Delegates to pragmatica-lite's JdkHttpOperations.
final class JdkHttpClient implements HttpClient {
    private final HttpClientConfig config;
    private final HttpOperations operations;
    private volatile JsonMapper jsonMapper;

    private JdkHttpClient(HttpClientConfig config, HttpOperations operations) {
        this.config = config;
        this.operations = operations;
    }

    static JdkHttpClient jdkHttpClient() {
        var config = HttpClientConfig.httpClientConfig()
                                     .unwrap();
        return new JdkHttpClient(config, createOperations(config));
    }

    static JdkHttpClient jdkHttpClient(HttpClientConfig config) {
        return new JdkHttpClient(config, createOperations(config));
    }

    private static HttpOperations createOperations(HttpClientConfig config) {
        var timeout = Duration.ofMillis(config.connectTimeout()
                                              .millis());
        return JdkHttpOperations.jdkHttpOperations(timeout, config.followRedirects(), none());
    }

    @Override
    public HttpClientConfig config() {
        return config;
    }

    @Override
    public Promise<HttpResult<String>> get(String path) {
        return get(path, Map.of());
    }

    @Override
    public Promise<HttpResult<String>> get(String path, Map<String, String> headers) {
        var builder = HttpRequest.newBuilder()
                                 .uri(buildUri(path))
                                 .GET()
                                 .timeout(requestTimeout());
        applyHeaders(builder, headers);
        return operations.sendString(builder.build());
    }

    @Override
    public Promise<HttpResult<String>> post(String path, String body) {
        return post(path, body, Map.of());
    }

    @Override
    public Promise<HttpResult<String>> post(String path, String body, Map<String, String> headers) {
        var builder = HttpRequest.newBuilder()
                                 .uri(buildUri(path))
                                 .POST(BodyPublishers.ofString(body))
                                 .header("Content-Type", "application/json")
                                 .timeout(requestTimeout());
        applyHeaders(builder, headers);
        return operations.sendString(builder.build());
    }

    @Override
    public Promise<HttpResult<String>> put(String path, String body) {
        return put(path, body, Map.of());
    }

    @Override
    public Promise<HttpResult<String>> put(String path, String body, Map<String, String> headers) {
        var builder = HttpRequest.newBuilder()
                                 .uri(buildUri(path))
                                 .PUT(BodyPublishers.ofString(body))
                                 .header("Content-Type", "application/json")
                                 .timeout(requestTimeout());
        applyHeaders(builder, headers);
        return operations.sendString(builder.build());
    }

    @Override
    public Promise<HttpResult<String>> delete(String path) {
        return delete(path, Map.of());
    }

    @Override
    public Promise<HttpResult<String>> delete(String path, Map<String, String> headers) {
        var builder = HttpRequest.newBuilder()
                                 .uri(buildUri(path))
                                 .DELETE()
                                 .timeout(requestTimeout());
        applyHeaders(builder, headers);
        return operations.sendString(builder.build());
    }

    @Override
    public Promise<HttpResult<String>> patch(String path, String body) {
        return patch(path, body, Map.of());
    }

    @Override
    public Promise<HttpResult<String>> patch(String path, String body, Map<String, String> headers) {
        var builder = HttpRequest.newBuilder()
                                 .uri(buildUri(path))
                                 .method("PATCH",
                                         BodyPublishers.ofString(body))
                                 .header("Content-Type", "application/json")
                                 .timeout(requestTimeout());
        applyHeaders(builder, headers);
        return operations.sendString(builder.build());
    }

    @Override
    public Promise<HttpResult<byte[]>> getBytes(String path) {
        return getBytes(path, Map.of());
    }

    @Override
    public Promise<HttpResult<byte[]>> getBytes(String path, Map<String, String> headers) {
        var builder = HttpRequest.newBuilder()
                                 .uri(buildUri(path))
                                 .GET()
                                 .timeout(requestTimeout());
        applyHeaders(builder, headers);
        return operations.sendBytes(builder.build());
    }

    private Duration requestTimeout() {
        return Duration.ofMillis(config.requestTimeout()
                                       .millis());
    }

    private URI buildUri(String path) {
        var fullUrl = config.baseUrl()
                            .map(base -> joinUrl(base, path));
        return fullUrl.map(url -> parseUri(url))
                      .or(() -> parseUri(path));
    }

    private static URI parseUri(String uri) {
        return Network.parseURI(uri)
                      .unwrap();
    }

    private static String joinUrl(String base, String path) {
        if (base.endsWith("/") && path.startsWith("/")) {
            return base + path.substring(1);
        }
        if (base.endsWith("/") || path.startsWith("/")) {
            return base + path;
        }
        return base + "/" + path;
    }

    private void applyHeaders(HttpRequest.Builder builder, Map<String, String> requestHeaders) {
        config.defaultHeaders().forEach(builder::header);
        requestHeaders.forEach(builder::header);
    }

    // ═══ Typed JSON API ═══

    @Override
    public <T> Promise<T> getJson(String path, TypeToken<T> responseType, Option<TypeToken<?>> errorType) {
        return get(path).flatMap(result -> parseResponse(result, responseType, errorType));
    }

    @Override
    public <T> Promise<T> postJson(String path, Object body, TypeToken<T> responseType, Option<TypeToken<?>> errorType) {
        return serializeAndSend(path, body, this::post).flatMap(result -> parseResponse(result, responseType, errorType));
    }

    @Override
    public <T> Promise<T> putJson(String path, Object body, TypeToken<T> responseType, Option<TypeToken<?>> errorType) {
        return serializeAndSend(path, body, this::put).flatMap(result -> parseResponse(result, responseType, errorType));
    }

    @Override
    public <T> Promise<T> patchJson(String path, Object body, TypeToken<T> responseType, Option<TypeToken<?>> errorType) {
        return serializeAndSend(path, body, this::patch).flatMap(result -> parseResponse(result, responseType, errorType));
    }

    @Override
    public <T> Promise<T> deleteJson(String path, TypeToken<T> responseType, Option<TypeToken<?>> errorType) {
        return delete(path).flatMap(result -> parseResponse(result, responseType, errorType));
    }

    @Override
    public Promise<Unit> deleteJsonVoid(String path) {
        return delete(path).flatMap(this::handleVoidResponse);
    }

    // ═══ JSON helpers ═══

    private JsonMapper jsonMapper() {
        var mapper = jsonMapper;
        if (mapper == null) {
            synchronized (this) {
                mapper = jsonMapper;
                if (mapper == null) {
                    mapper = createJsonMapper();
                    jsonMapper = mapper;
                }
            }
        }
        return mapper;
    }

    private JsonMapper createJsonMapper() {
        var jsonConfig = config.json()
                               .or(JsonConfig::jsonConfig);

        return JsonMapper.jsonMapper()
                         .withPragmaticaTypes()
                         .configure(b -> configureNaming(b, jsonConfig))
                         .configure(b -> configureInclusion(b, jsonConfig))
                         .configure(b -> configureUnknownProperties(b, jsonConfig))
                         .build();
    }

    private static void configureNaming(tools.jackson.databind.json.JsonMapper.Builder builder, JsonConfig jsonConfig) {
        switch (jsonConfig.naming()) {
            case SNAKE_CASE -> builder.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
            case KEBAB_CASE -> builder.propertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE);
            case CAMEL_CASE -> { }
        }
    }

    private static void configureInclusion(tools.jackson.databind.json.JsonMapper.Builder builder, JsonConfig jsonConfig) {
        switch (jsonConfig.nullInclusion()) {
            case EXCLUDE -> builder.changeDefaultPropertyInclusion(
                incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL));
            case NON_EMPTY -> builder.changeDefaultPropertyInclusion(
                incl -> incl.withValueInclusion(JsonInclude.Include.NON_EMPTY));
            case INCLUDE -> builder.changeDefaultPropertyInclusion(
                incl -> incl.withValueInclusion(JsonInclude.Include.ALWAYS));
        }
    }

    private static void configureUnknownProperties(tools.jackson.databind.json.JsonMapper.Builder builder, JsonConfig jsonConfig) {
        if (jsonConfig.failOnUnknown()) {
            builder.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        } else {
            builder.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        }
    }

    private Promise<HttpResult<String>> serializeAndSend(String path, Object body,
                                                          BiFunction<String, String, Promise<HttpResult<String>>> sender) {
        return jsonMapper().writeAsString(body)
                           .fold(
                               cause -> new HttpClientError.SerializationFailed(cause.message()).<HttpResult<String>>promise(),
                               json -> sender.apply(path, json)
                           );
    }

    private <T> Promise<T> parseResponse(HttpResult<String> result,
                                          TypeToken<T> responseType,
                                          Option<TypeToken<?>> errorType) {
        if (result.isSuccess()) {
            return deserializeSuccess(result, responseType);
        }
        return errorType.fold(
            () -> new HttpClientError.RequestFailed(result.statusCode(), result.body()).<T>promise(),
            et -> tryParseError(result, et)
        );
    }

    private <T> Promise<T> deserializeSuccess(HttpResult<String> result, TypeToken<T> responseType) {
        return jsonMapper().readString(result.body(), responseType)
                           .fold(
                               cause -> new HttpClientError.DeserializationFailed(cause.message(), result.body()).<T>promise(),
                               Promise::success
                           );
    }

    private <T> Promise<T> tryParseError(HttpResult<String> result, TypeToken<?> errorType) {
        return jsonMapper().readString(result.body(), errorType)
                           .fold(
                               _ -> new HttpClientError.RequestFailed(result.statusCode(), result.body()).<T>promise(),
                               parsedError -> new HttpClientError.RequestFailedWithBody(result.statusCode(), parsedError, result.body()).<T>promise()
                           );
    }

    private Promise<Unit> handleVoidResponse(HttpResult<String> result) {
        if (result.isSuccess()) {
            return Promise.success(unit());
        }
        return new HttpClientError.RequestFailed(result.statusCode(), result.body()).promise();
    }
}
