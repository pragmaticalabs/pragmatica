package org.pragmatica.aether.http;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.SecurityMode;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AppHttpServerSecurityModeTest {
    private static final NodeId SELF_NODE = NodeId.nodeId("test-node-sec").unwrap();

    @Nested
    class ConfigTests {
        @Test
        void securityModeApiKey_configuredFromApiKeys() {
            var config = AppHttpConfig.appHttpConfig(19090, Set.of("test-key"));
            assertThat(config.securityMode()).isEqualTo(SecurityMode.API_KEY);
            assertThat(config.securityEnabled()).isTrue();
        }

        @Test
        void securityModeNone_isDefault() {
            var config = AppHttpConfig.appHttpConfig(19090);
            assertThat(config.securityMode()).isEqualTo(SecurityMode.NONE);
            assertThat(config.securityEnabled()).isFalse();
        }

        @Test
        void withSecurityMode_updatesMode() {
            var config = AppHttpConfig.appHttpConfig(19090)
                                      .withSecurityMode(SecurityMode.API_KEY);
            assertThat(config.securityMode()).isEqualTo(SecurityMode.API_KEY);
            assertThat(config.securityEnabled()).isTrue();
        }
    }

    @Nested
    class ApiKeyServerTests {
        private static final int SECURE_PORT = 19091;
        private static final String VALID_API_KEY = "test-api-key-valid-12345";
        private static AppHttpServer server;
        private static HttpClient httpClient;

        @BeforeAll
        static void startServer() {
            httpClient = HttpClient.newBuilder()
                                   .connectTimeout(Duration.ofSeconds(5))
                                   .build();
            var config = AppHttpConfig.appHttpConfig(SECURE_PORT, Set.of(VALID_API_KEY));
            server = AppHttpServer.appHttpServer(config,
                                                 SELF_NODE,
                                                 HttpRouteRegistry.httpRouteRegistry(),
                                                 Option.none(),
                                                 Option.none(),
                                                 Option.none(),
                                                 Option.none(),
                                                 Option.none(),
                                                 Option.none(),
                                                 Option.none(),
                                                 Option.none(),
                                                 Option.none());
            server.start().await();
        }

        @AfterAll
        static void stopServer() {
            server.stop().await();
        }

        @Test
        void rejectsRequestWithoutKey() throws Exception {
            var request = HttpRequest.newBuilder()
                                     .uri(URI.create("http://localhost:" + SECURE_PORT + "/some/path"))
                                     .GET()
                                     .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(401);
        }

        @Test
        void healthEndpointBypassesAuth() throws Exception {
            var request = HttpRequest.newBuilder()
                                     .uri(URI.create("http://localhost:" + SECURE_PORT + "/health"))
                                     .GET()
                                     .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
        }

        @Test
        void acceptsValidKey() throws Exception {
            var request = HttpRequest.newBuilder()
                                     .uri(URI.create("http://localhost:" + SECURE_PORT + "/some/path"))
                                     .header("X-API-Key", VALID_API_KEY)
                                     .GET()
                                     .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            // Authenticated but no route found -> 404 (not 401)
            assertThat(response.statusCode()).isEqualTo(404);
        }

        @Test
        void rejectsInvalidKey() throws Exception {
            var request = HttpRequest.newBuilder()
                                     .uri(URI.create("http://localhost:" + SECURE_PORT + "/some/path"))
                                     .header("X-API-Key", "wrong-key")
                                     .GET()
                                     .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isIn(401, 403);
        }
    }

    @Nested
    class NoSecurityServerTests {
        private static final int OPEN_PORT = 19092;
        private static AppHttpServer server;
        private static HttpClient httpClient;

        @BeforeAll
        static void startServer() {
            httpClient = HttpClient.newBuilder()
                                   .connectTimeout(Duration.ofSeconds(5))
                                   .build();
            var config = AppHttpConfig.appHttpConfig(OPEN_PORT);
            server = AppHttpServer.appHttpServer(config,
                                                 SELF_NODE,
                                                 HttpRouteRegistry.httpRouteRegistry(),
                                                 Option.none(),
                                                 Option.none(),
                                                 Option.none(),
                                                 Option.none(),
                                                 Option.none(),
                                                 Option.none(),
                                                 Option.none(),
                                                 Option.none(),
                                                 Option.none());
            server.start().await();
        }

        @AfterAll
        static void stopServer() {
            server.stop().await();
        }

        @Test
        void allowsUnauthenticatedHealthRequest() throws Exception {
            var request = HttpRequest.newBuilder()
                                     .uri(URI.create("http://localhost:" + OPEN_PORT + "/health"))
                                     .GET()
                                     .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
        }
    }
}
