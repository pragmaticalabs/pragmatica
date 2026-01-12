package org.pragmatica.aether.infra.http;

import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.http.JdkHttpOperations;
import org.pragmatica.lang.Promise;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.time.Duration;
import java.util.Map;

/**
 * JDK HttpClient-based implementation of HttpClientSlice.
 * Delegates to pragmatica-lite's JdkHttpOperations.
 */
final class JdkHttpClientSlice implements HttpClientSlice {
    private final HttpClientConfig config;
    private final HttpOperations operations;

    private JdkHttpClientSlice(HttpClientConfig config, HttpOperations operations) {
        this.config = config;
        this.operations = operations;
    }

    static JdkHttpClientSlice jdkHttpClientSlice() {
        var config = HttpClientConfig.httpClientConfig()
                                     .unwrap();
        return new JdkHttpClientSlice(config, createOperations(config));
    }

    static JdkHttpClientSlice jdkHttpClientSlice(HttpClientConfig config) {
        return new JdkHttpClientSlice(config, createOperations(config));
    }

    private static HttpOperations createOperations(HttpClientConfig config) {
        return JdkHttpOperations.jdkHttpOperations(Duration.ofMillis(config.connectTimeout()
                                                                           .millis()),
                                                   config.followRedirects(),
                                                   null);
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
                                 .timeout(Duration.ofMillis(config.requestTimeout()
                                                                  .millis()));
        headers.forEach(builder::header);
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
                                 .timeout(Duration.ofMillis(config.requestTimeout()
                                                                  .millis()));
        headers.forEach(builder::header);
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
                                 .timeout(Duration.ofMillis(config.requestTimeout()
                                                                  .millis()));
        headers.forEach(builder::header);
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
                                 .timeout(Duration.ofMillis(config.requestTimeout()
                                                                  .millis()));
        headers.forEach(builder::header);
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
                                 .timeout(Duration.ofMillis(config.requestTimeout()
                                                                  .millis()));
        headers.forEach(builder::header);
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
                                 .timeout(Duration.ofMillis(config.requestTimeout()
                                                                  .millis()));
        headers.forEach(builder::header);
        return operations.sendBytes(builder.build());
    }

    private URI buildUri(String path) {
        return config.baseUrl()
                     .map(base -> base.endsWith("/") && path.startsWith("/")
                                  ? base + path.substring(1)
                                  : base.endsWith("/") || path.startsWith("/")
                                    ? base + path
                                    : base + "/" + path)
                     .map(URI::create)
                     .or(() -> URI.create(path));
    }
}
