package org.pragmatica.aether.resource.http;

import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.http.JdkHttpOperations;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.parse.Network;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.time.Duration;
import java.util.Map;

import static org.pragmatica.lang.Option.none;

/// JDK HttpClient-based implementation of HttpClient.
/// Delegates to pragmatica-lite's JdkHttpOperations.
final class JdkHttpClient implements HttpClient {
    private final HttpClientConfig config;
    private final HttpOperations operations;

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
                                 .timeout(requestTimeout());
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
                                 .timeout(requestTimeout());
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
                                 .timeout(requestTimeout());
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
                                 .timeout(requestTimeout());
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
                                 .timeout(requestTimeout());
        headers.forEach(builder::header);
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
}
