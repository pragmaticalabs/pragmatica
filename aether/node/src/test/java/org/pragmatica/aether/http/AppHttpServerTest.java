package org.pragmatica.aether.http;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey.HttpRouteKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.HttpRouteValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AppHttpServerTest {
    private static final NodeId NODE_1 = NodeId.nodeId("node-1").unwrap();
    private static final NodeId SELF_NODE = NodeId.nodeId("test-node").unwrap();

    private HttpRouteRegistry registry;
    private AppHttpServer server;
    private HttpClient httpClient;
    private int port;

    private static final int TEST_PORT = 18080;

    @BeforeEach
    void setUp() {
        registry = HttpRouteRegistry.httpRouteRegistry();
        // Use fixed port (pragmatica-lite HttpServer doesn't properly return OS-assigned ports)
        var config = AppHttpConfig.appHttpConfig(TEST_PORT);
        port = TEST_PORT;
        // Create server without HTTP forwarding support for basic tests
        server = AppHttpServer.appHttpServer(config,
                                             SELF_NODE,
                                             registry,
                                             Option.none(),  // No HttpRoutePublisher
                                             Option.none(),  // No ClusterNetwork
                                             Option.none(),  // No Serializer
                                             Option.none(),  // No Deserializer
                                             Option.none()); // No TLS
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
        assertThat(server.boundPort().unwrap()).isEqualTo(TEST_PORT);
    }

    @Test
    void request_to_unknown_route_returns_404() throws Exception {
        server.start().await();

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
    void request_to_known_route_returns_503_when_no_forwarding() throws Exception {
        // Register a route (not local, so needs forwarding)
        registerRoute("GET", "/users/", Set.of(NODE_1));

        server.start().await();

        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/users/123"))
                                 .GET()
                                 .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // Returns 503 because HTTP forwarding is not configured
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
    }

    @Test
    void disabled_server_does_not_bind() {
        var disabledConfig = AppHttpConfig.appHttpConfig();
        var disabledServer = AppHttpServer.appHttpServer(disabledConfig,
                                                         SELF_NODE,
                                                         registry,
                                                         Option.none(),
                                                         Option.none(),
                                                         Option.none(),
                                                         Option.none(),
                                                         Option.none());

        disabledServer.start().await();

        assertThat(disabledServer.boundPort().isEmpty()).isTrue();
    }

    @Test
    void post_request_routes_correctly() throws Exception {
        registerRoute("POST", "/orders/", Set.of(NODE_1));

        server.start().await();

        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/orders/"))
                                 .POST(HttpRequest.BodyPublishers.ofString("{\"item\":\"test\"}"))
                                 .header("Content-Type", "application/json")
                                 .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // Route is found but returns 503 (no forwarding configured)
        assertThat(response.statusCode()).isEqualTo(503);
    }

    @Test
    void problem_detail_includes_request_id() throws Exception {
        server.start().await();

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

    private void registerRoute(String method, String path, Set<NodeId> nodes) {
        var key = HttpRouteKey.httpRouteKey(method, path);
        var value = HttpRouteValue.httpRouteValue(nodes);
        var command = new KVCommand.Put<HttpRouteKey, HttpRouteValue>(key, value);
        var notification = new ValuePut<>(command, Option.none());
        registry.onRoutePut(notification);
    }
}
