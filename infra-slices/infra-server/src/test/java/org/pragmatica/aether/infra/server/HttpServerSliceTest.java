package org.pragmatica.aether.infra.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.http.routing.HttpStatus;
import org.pragmatica.http.routing.PathParameter;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import org.pragmatica.lang.type.TypeToken;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.http.routing.Route.*;

class HttpServerSliceTest {
    private static final int TEST_PORT = 18080;
    private HttpServerSlice server;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @AfterEach
    void tearDown() {
        if (server != null && server.isRunning()) {
            server.stop().await();
        }
    }

    @Nested
    class ConfigurationTests {
        @Test
        void config_succeeds_withDefaultValues() {
            HttpServerSliceConfig.httpServerSliceConfig()
                                 .onFailureRun(Assertions::fail)
                                 .onSuccess(config -> {
                                     assertThat(config.name()).isEqualTo("http-server");
                                     assertThat(config.port()).isEqualTo(8080);
                                     assertThat(config.maxContentLength()).isEqualTo(65536);
                                 });
        }

        @Test
        void config_succeeds_withCustomPort() {
            HttpServerSliceConfig.httpServerSliceConfig(9000)
                                 .onFailureRun(Assertions::fail)
                                 .onSuccess(config -> {
                                     assertThat(config.port()).isEqualTo(9000);
                                 });
        }

        @Test
        void config_succeeds_withNameAndPort() {
            HttpServerSliceConfig.httpServerSliceConfig("my-server", 9000)
                                 .onFailureRun(Assertions::fail)
                                 .onSuccess(config -> {
                                     assertThat(config.name()).isEqualTo("my-server");
                                     assertThat(config.port()).isEqualTo(9000);
                                 });
        }

        @Test
        void config_fails_forInvalidPort() {
            HttpServerSliceConfig.httpServerSliceConfig(0)
                                 .onSuccessRun(Assertions::fail)
                                 .onFailure(cause -> assertThat(cause.message()).contains("Port"));

            HttpServerSliceConfig.httpServerSliceConfig(70000)
                                 .onSuccessRun(Assertions::fail)
                                 .onFailure(cause -> assertThat(cause.message()).contains("Port"));
        }

        @Test
        void config_fails_forEmptyName() {
            HttpServerSliceConfig.httpServerSliceConfig("", 9000)
                                 .onSuccessRun(Assertions::fail)
                                 .onFailure(cause -> assertThat(cause.message()).contains("name"));

            HttpServerSliceConfig.httpServerSliceConfig("  ", 9000)
                                 .onSuccessRun(Assertions::fail)
                                 .onFailure(cause -> assertThat(cause.message()).contains("name"));
        }

        @Test
        void config_withMethods_returnNewInstance() {
            var original = HttpServerSliceConfig.httpServerSliceConfig().unwrap();
            var modified = original.withPort(9999)
                                   .withName("new-name")
                                   .withMaxContentLength(1024)
                                   .withRequestTimeout(TimeSpan.timeSpan(60).seconds())
                                   .withIdleTimeout(TimeSpan.timeSpan(120).seconds());

            assertThat(modified.port()).isEqualTo(9999);
            assertThat(modified.name()).isEqualTo("new-name");
            assertThat(modified.maxContentLength()).isEqualTo(1024);
            assertThat(modified.requestTimeout().millis()).isEqualTo(60_000);
            assertThat(modified.idleTimeout().millis()).isEqualTo(120_000);

            // Original unchanged
            assertThat(original.port()).isEqualTo(8080);
        }
    }

    @Nested
    class LifecycleTests {
        @Test
        void server_startsAndStops() {
            var config = HttpServerSliceConfig.httpServerSliceConfig(TEST_PORT).unwrap();

            server = HttpServerSlice.httpServerSlice(config,
                get("/health").withoutParameters().to(_ -> Promise.success(Map.of("status", "ok"))).asJson()
            );

            assertThat(server.isRunning()).isFalse();
            assertThat(server.boundPort().isPresent()).isFalse();

            server.start()
                  .await()
                  .onFailureRun(Assertions::fail);

            assertThat(server.isRunning()).isTrue();
            assertThat(server.boundPort().isPresent()).isTrue();
            assertThat(server.boundPort().or(0)).isEqualTo(TEST_PORT);

            server.stop()
                  .await()
                  .onFailureRun(Assertions::fail);

            assertThat(server.isRunning()).isFalse();
        }

        @Test
        void server_reportsRouteCount() {
            var config = HttpServerSliceConfig.httpServerSliceConfig(TEST_PORT).unwrap();

            server = HttpServerSlice.httpServerSlice(config,
                get("/route1").withoutParameters().to(_ -> Promise.success("1")).asJson(),
                post("/route2").withoutParameters().to(_ -> Promise.success("2")).asJson(),
                put("/route3").withoutParameters().to(_ -> Promise.success("3")).asJson()
            );

            assertThat(server.routeCount()).isEqualTo(3);
        }

        @Test
        void server_startsOnce_whenCalledMultipleTimes() {
            var config = HttpServerSliceConfig.httpServerSliceConfig(TEST_PORT).unwrap();
            var startCount = new AtomicInteger(0);

            server = HttpServerSlice.httpServerSlice(config,
                get("/health").withoutParameters().to(_ -> {
                    startCount.incrementAndGet();
                    return Promise.success(Map.of("status", "ok"));
                }).asJson()
            );

            // Start multiple times
            server.start().await();
            server.start().await();
            server.start().await();

            assertThat(server.isRunning()).isTrue();
        }
    }

    @Nested
    class RoutingTests {
        @Test
        void server_handlesGetRequest() throws Exception {
            var config = HttpServerSliceConfig.httpServerSliceConfig(TEST_PORT).unwrap();

            server = HttpServerSlice.httpServerSlice(config,
                get("/hello").withoutParameters().to(_ -> Promise.success(Map.of("message", "Hello, World!"))).asJson()
            );
            server.start().await();

            var request = HttpRequest.newBuilder()
                                     .uri(URI.create("http://localhost:" + TEST_PORT + "/hello"))
                                     .GET()
                                     .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("Hello, World!");
        }

        @Test
        void server_handlesPostRequest() throws Exception {
            var config = HttpServerSliceConfig.httpServerSliceConfig(TEST_PORT).unwrap();

            server = HttpServerSlice.httpServerSlice(config,
                post("/echo").withoutParameters().to(ctx -> {
                    var body = ctx.bodyAsString();
                    return Promise.success(Map.of("received", body));
                }).asJson()
            );
            server.start().await();

            var request = HttpRequest.newBuilder()
                                     .uri(URI.create("http://localhost:" + TEST_PORT + "/echo"))
                                     .POST(HttpRequest.BodyPublishers.ofString("test body"))
                                     .header("Content-Type", "application/json")
                                     .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("test body");
        }

        @Test
        void server_handlesPutRequest() throws Exception {
            var config = HttpServerSliceConfig.httpServerSliceConfig(TEST_PORT).unwrap();

            server = HttpServerSlice.httpServerSlice(config,
                put("/resource").withoutParameters().to(_ -> Promise.success(Map.of("updated", true))).asJson()
            );
            server.start().await();

            var request = HttpRequest.newBuilder()
                                     .uri(URI.create("http://localhost:" + TEST_PORT + "/resource"))
                                     .PUT(HttpRequest.BodyPublishers.ofString("{}"))
                                     .header("Content-Type", "application/json")
                                     .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
        }

        @Test
        void server_handlesDeleteRequest() throws Exception {
            var config = HttpServerSliceConfig.httpServerSliceConfig(TEST_PORT).unwrap();

            server = HttpServerSlice.httpServerSlice(config,
                delete("/resource").withoutParameters().to(_ -> Promise.success(Map.of("deleted", true))).asJson()
            );
            server.start().await();

            var request = HttpRequest.newBuilder()
                                     .uri(URI.create("http://localhost:" + TEST_PORT + "/resource"))
                                     .DELETE()
                                     .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
        }

        @Test
        void server_handlesPatchRequest() throws Exception {
            var config = HttpServerSliceConfig.httpServerSliceConfig(TEST_PORT).unwrap();

            server = HttpServerSlice.httpServerSlice(config,
                patch("/resource").withoutParameters().to(_ -> Promise.success(Map.of("patched", true))).asJson()
            );
            server.start().await();

            var request = HttpRequest.newBuilder()
                                     .uri(URI.create("http://localhost:" + TEST_PORT + "/resource"))
                                     .method("PATCH", HttpRequest.BodyPublishers.ofString("{}"))
                                     .header("Content-Type", "application/json")
                                     .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
        }

        @Test
        void server_returns404_forUnknownPath() throws Exception {
            var config = HttpServerSliceConfig.httpServerSliceConfig(TEST_PORT).unwrap();

            server = HttpServerSlice.httpServerSlice(config,
                get("/known").withoutParameters().to(_ -> Promise.success("ok")).asJson()
            );
            server.start().await();

            var request = HttpRequest.newBuilder()
                                     .uri(URI.create("http://localhost:" + TEST_PORT + "/unknown"))
                                     .GET()
                                     .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(404);
        }
    }

    @Nested
    class PathParameterTests {
        @Test
        void server_extractsPathParameter() throws Exception {
            var config = HttpServerSliceConfig.httpServerSliceConfig(TEST_PORT).unwrap();

            server = HttpServerSlice.httpServerSlice(config,
                get("/users/").withPath(PathParameter.aString())
                              .to(userId -> Promise.success(Map.of("userId", userId)))
                              .asJson()
            );
            server.start().await();

            var request = HttpRequest.newBuilder()
                                     .uri(URI.create("http://localhost:" + TEST_PORT + "/users/12345"))
                                     .GET()
                                     .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("12345");
        }

        @Test
        void server_extractsMultiplePathParameters() throws Exception {
            var config = HttpServerSliceConfig.httpServerSliceConfig(TEST_PORT).unwrap();

            server = HttpServerSlice.httpServerSlice(config,
                get("/users/").withPath(PathParameter.aString(), PathParameter.aString())
                              .to((userId, action) -> Promise.success(Map.of("userId", userId, "action", action)))
                              .asJson()
            );
            server.start().await();

            var request = HttpRequest.newBuilder()
                                     .uri(URI.create("http://localhost:" + TEST_PORT + "/users/123/profile"))
                                     .GET()
                                     .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("123");
            assertThat(response.body()).contains("profile");
        }

        @Test
        void server_extractsIntegerPathParameter() throws Exception {
            var config = HttpServerSliceConfig.httpServerSliceConfig(TEST_PORT).unwrap();

            server = HttpServerSlice.httpServerSlice(config,
                get("/items/").withPath(PathParameter.aInteger())
                              .to(itemId -> Promise.success(Map.of("itemId", itemId)))
                              .asJson()
            );
            server.start().await();

            var request = HttpRequest.newBuilder()
                                     .uri(URI.create("http://localhost:" + TEST_PORT + "/items/42"))
                                     .GET()
                                     .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("42");
        }
    }

    @Nested
    class QueryParameterTests {
        @Test
        void server_extractsQueryParameters() throws Exception {
            var config = HttpServerSliceConfig.httpServerSliceConfig(TEST_PORT).unwrap();

            server = HttpServerSlice.httpServerSlice(config,
                get("/search").withoutParameters().to(ctx -> {
                    var query = ctx.queryParams().get("q").getFirst();
                    return Promise.success(Map.of("query", query));
                }).asJson()
            );
            server.start().await();

            var request = HttpRequest.newBuilder()
                                     .uri(URI.create("http://localhost:" + TEST_PORT + "/search?q=hello"))
                                     .GET()
                                     .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("hello");
        }
    }

    @Nested
    class ContentTypeTests {
        @Test
        void server_returnsJsonContentType() throws Exception {
            var config = HttpServerSliceConfig.httpServerSliceConfig(TEST_PORT).unwrap();

            server = HttpServerSlice.httpServerSlice(config,
                get("/json").withoutParameters().to(_ -> Promise.success(Map.of("key", "value"))).asJson()
            );
            server.start().await();

            var request = HttpRequest.newBuilder()
                                     .uri(URI.create("http://localhost:" + TEST_PORT + "/json"))
                                     .GET()
                                     .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("content-type").orElse(""))
                .contains("application/json");
        }

        @Test
        void server_returnsTextContentType() throws Exception {
            var config = HttpServerSliceConfig.httpServerSliceConfig(TEST_PORT).unwrap();

            server = HttpServerSlice.httpServerSlice(config,
                get("/text").withoutParameters().to(_ -> Promise.success("Plain text response")).asText()
            );
            server.start().await();

            var request = HttpRequest.newBuilder()
                                     .uri(URI.create("http://localhost:" + TEST_PORT + "/text"))
                                     .GET()
                                     .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("content-type").orElse(""))
                .contains("text/plain");
        }
    }

    @Nested
    class SubroutesTests {
        @Test
        void server_handlesSubroutes() throws Exception {
            var config = HttpServerSliceConfig.httpServerSliceConfig(TEST_PORT).unwrap();
            var routes = Stream.<RouteSource>of(
                in("/api/v1").serve(
                    get("/users").withoutParameters().to(_ -> Promise.success(Map.of("users", "list"))).asJson(),
                    get("/products").withoutParameters().to(_ -> Promise.success(Map.of("products", "list"))).asJson()
                )
            );

            server = HttpServerSlice.httpServerSlice(config, routes);
            server.start().await();

            var usersRequest = HttpRequest.newBuilder()
                                          .uri(URI.create("http://localhost:" + TEST_PORT + "/api/v1/users"))
                                          .GET()
                                          .build();

            var usersResponse = httpClient.send(usersRequest, HttpResponse.BodyHandlers.ofString());
            assertThat(usersResponse.statusCode()).isEqualTo(200);
            assertThat(usersResponse.body()).contains("users");

            var productsRequest = HttpRequest.newBuilder()
                                             .uri(URI.create("http://localhost:" + TEST_PORT + "/api/v1/products"))
                                             .GET()
                                             .build();

            var productsResponse = httpClient.send(productsRequest, HttpResponse.BodyHandlers.ofString());
            assertThat(productsResponse.statusCode()).isEqualTo(200);
            assertThat(productsResponse.body()).contains("products");
        }
    }

    @Nested
    class ErrorHandlingTests {
        private final JsonMapper jsonMapper = JsonMapper.defaultJsonMapper();

        @Test
        void server_handlesHandlerError_withProblemDetail() throws Exception {
            var config = HttpServerSliceConfig.httpServerSliceConfig(TEST_PORT).unwrap();

            server = HttpServerSlice.httpServerSlice(config,
                get("/error").withoutParameters().to(_ ->
                    new HttpServerSliceError.RequestFailed("test-123", new RuntimeException("Test error")).promise()
                ).asJson()
            );
            server.start().await();

            var request = HttpRequest.newBuilder()
                                     .uri(URI.create("http://localhost:" + TEST_PORT + "/error"))
                                     .GET()
                                     .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(response.headers().firstValue("Content-Type"))
                    .isPresent()
                    .hasValueSatisfying(ct -> assertThat(ct).contains("application/problem+json"));

            var problemDetail = parseProblemDetail(response.body());
            assertThat(problemDetail.containsKey("type")).isTrue();
            assertThat(problemDetail.containsKey("title")).isTrue();
            assertThat(problemDetail.containsKey("status")).isTrue();
            assertThat(problemDetail.containsKey("detail")).isTrue();
            assertThat(problemDetail.containsKey("instance")).isTrue();
            assertThat(problemDetail.containsKey("requestId")).isTrue();

            assertThat(((Number) problemDetail.get("status")).intValue()).isEqualTo(500);
            assertThat(problemDetail.get("title")).isEqualTo("Internal Server Error");
            assertThat(problemDetail.get("instance")).isEqualTo("/error");
            assertThat((String) problemDetail.get("requestId")).isNotBlank();
        }

        @Test
        void server_handlesHttpError_withProblemDetail() throws Exception {
            var config = HttpServerSliceConfig.httpServerSliceConfig(TEST_PORT).unwrap();

            server = HttpServerSlice.httpServerSlice(config,
                get("/bad-request").withoutParameters().to(_ ->
                    HttpStatus.BAD_REQUEST.with("Invalid input provided").promise()
                ).asJson()
            );
            server.start().await();

            var request = HttpRequest.newBuilder()
                                     .uri(URI.create("http://localhost:" + TEST_PORT + "/bad-request"))
                                     .GET()
                                     .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.headers().firstValue("Content-Type"))
                    .isPresent()
                    .hasValueSatisfying(ct -> assertThat(ct).contains("application/problem+json"));

            var problemDetail = parseProblemDetail(response.body());
            assertThat(((Number) problemDetail.get("status")).intValue()).isEqualTo(400);
            assertThat(problemDetail.get("title")).isEqualTo("Bad Request");
            assertThat(problemDetail.get("detail")).isEqualTo("Invalid input provided");
            assertThat((String) problemDetail.get("requestId")).isNotBlank();
        }

        @Test
        void server_handlesNotFound_withProblemDetail() throws Exception {
            var config = HttpServerSliceConfig.httpServerSliceConfig(TEST_PORT).unwrap();

            server = HttpServerSlice.httpServerSlice(config,
                get("/existing").withoutParameters().to(_ -> Promise.success("OK")).asJson()
            );
            server.start().await();

            var request = HttpRequest.newBuilder()
                                     .uri(URI.create("http://localhost:" + TEST_PORT + "/nonexistent"))
                                     .GET()
                                     .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(response.headers().firstValue("Content-Type"))
                    .isPresent()
                    .hasValueSatisfying(ct -> assertThat(ct).contains("application/problem+json"));

            var problemDetail = parseProblemDetail(response.body());
            assertThat(((Number) problemDetail.get("status")).intValue()).isEqualTo(404);
            assertThat(problemDetail.get("title")).isEqualTo("Not Found");
            assertThat((String) problemDetail.get("detail")).contains("/nonexistent");
            assertThat(problemDetail.get("instance")).isEqualTo("/nonexistent");
            assertThat((String) problemDetail.get("requestId")).isNotBlank();
        }

        @Test
        void problemDetail_includesCorrectType() throws Exception {
            var config = HttpServerSliceConfig.httpServerSliceConfig(TEST_PORT).unwrap();

            server = HttpServerSlice.httpServerSlice(config,
                get("/error").withoutParameters().to(_ ->
                    HttpStatus.UNPROCESSABLE_ENTITY.with("Validation failed").promise()
                ).asJson()
            );
            server.start().await();

            var request = HttpRequest.newBuilder()
                                     .uri(URI.create("http://localhost:" + TEST_PORT + "/error"))
                                     .GET()
                                     .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            var problemDetail = parseProblemDetail(response.body());
            assertThat(problemDetail.get("type")).isEqualTo("about:blank");
            assertThat(((Number) problemDetail.get("status")).intValue()).isEqualTo(422);
        }

        private static final TypeToken<Map<String, Object>> MAP_TYPE = new TypeToken<>() {};

        private Map<String, Object> parseProblemDetail(String json) {
            return jsonMapper.readString(json, MAP_TYPE).unwrap();
        }
    }
}
