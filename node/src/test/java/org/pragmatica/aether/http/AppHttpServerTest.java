package org.pragmatica.aether.http;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.HttpRouteKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.HttpRouteValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.lang.Option;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AppHttpServerTest {
    private HttpRouteRegistry registry;
    private AppHttpServer server;
    private HttpClient httpClient;
    private int port;

    @BeforeEach
    void setUp() {
        registry = HttpRouteRegistry.httpRouteRegistry();
        // Use port 0 to let OS assign available port
        var config = AppHttpConfig.enabledOnPort(0);
        server = AppHttpServer.appHttpServer(config, registry, Option.none());
        httpClient = HttpClient.newBuilder()
                               .connectTimeout(Duration.ofSeconds(5))
                               .build();
    }

    @AfterEach
    void tearDown() {
        server.stop().await();
    }

    @Test
    void start_binds_to_port() {
        server.start().await();
        assertThat(server.boundPort().isPresent()).isTrue();
        port = server.boundPort().unwrap();
        assertThat(port).isGreaterThan(0);
    }

    @Test
    void request_to_unknown_route_returns_404() throws Exception {
        server.start().await();
        port = server.boundPort().unwrap();

        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/unknown/path"))
                                 .GET()
                                 .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        assertThat(response.body()).contains("No route found");
    }

    @Test
    void request_to_known_route_returns_503_pending_integration() throws Exception {
        // Register a route
        registerRoute("GET", "/users/", "org.example:user-service:1.0.0", "getUsers");

        server.start().await();
        port = server.boundPort().unwrap();

        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/users/123"))
                                 .GET()
                                 .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // Returns 503 because SliceInvoker integration is not yet done (Phase 5)
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
    }

    @Test
    void disabled_server_does_not_bind() {
        var disabledConfig = AppHttpConfig.disabled();
        var disabledServer = AppHttpServer.appHttpServer(disabledConfig, registry, Option.none());

        disabledServer.start().await();

        assertThat(disabledServer.boundPort().isEmpty()).isTrue();
    }

    @Test
    void post_request_routes_correctly() throws Exception {
        registerRoute("POST", "/orders/", "org.example:order-service:1.0.0", "createOrder");

        server.start().await();
        port = server.boundPort().unwrap();

        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/orders/"))
                                 .POST(HttpRequest.BodyPublishers.ofString("{\"item\":\"test\"}"))
                                 .header("Content-Type", "application/json")
                                 .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // Route is found but returns 503 (pending SliceInvoker)
        assertThat(response.statusCode()).isEqualTo(503);
    }

    @Test
    void problem_detail_includes_request_id() throws Exception {
        server.start().await();
        port = server.boundPort().unwrap();

        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/not-found"))
                                 .GET()
                                 .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.body()).contains("requestId");
        assertThat(response.body()).contains("instance");
        assertThat(response.body()).contains("status");
        assertThat(response.body()).contains("title");
    }

    private void registerRoute(String method, String path, String artifact, String sliceMethod) {
        var key = HttpRouteKey.httpRouteKey(method, path);
        var value = HttpRouteValue.httpRouteValue(artifact, sliceMethod);
        var command = new KVCommand.Put<AetherKey, AetherValue>(key, value);
        var notification = new ValuePut<>(command, Option.none());
        registry.onValuePut(notification);
    }
}
