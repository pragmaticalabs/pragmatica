// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra.management;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.http.security.HttpAuthenticator;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.aether.slice.ResourceProviderFacade;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.http.routing.RouteMountMode;
import org.pragmatica.http.server.HttpServerConfig;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.terra.TerraApplication;
import org.pragmatica.terra.TerraBlueprint;
import org.pragmatica.terra.TerraContext;
import org.pragmatica.terra.TerraFactory;
import org.pragmatica.terra.http.TerraHttpConfig;
import org.pragmatica.terra.http.TerraHttpServer;
import org.pragmatica.terra.management.HealthIndicator.Health;
import org.pragmatica.terra.management.HealthIndicator.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.lang.utils.Causes.cause;

/// #3651 contract: each endpoint's response against Spring Boot's documented actuator v3 shapes, served through a real
/// Terra host (shared listener and a separate management port).
class TerraManagementHttpTest {
    private static final String KEY = "management-key-0123456789abcdef";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    record Sample() {}

    record Factory<T>(Class<T> sliceType, T value) implements TerraFactory<T> {
        public String artifact() { return "test:" + sliceType.getSimpleName() + ":1"; }
        public List<Class<?>> dependencies() { return List.of(); }
        public Promise<T> create(TerraContext context) { return Promise.success(value); }
    }

    @Test
    void defaultConfig_exposesOnlyHealth() throws Exception {
        try (var host = Host.start(Map.of(), Map.of())) {
            var health = host.get("/actuator/health");

            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(health.headers().firstValue("Content-Type")).hasValue(TerraManagement.ACTUATOR_V3);
            assertThat(json(health)).isEqualTo(JSON.readTree("{\"status\":\"UP\",\"groups\":[\"liveness\",\"readiness\"]}"));
            assertThat(json(host.get("/actuator/health/liveness"))).isEqualTo(JSON.readTree("{\"status\":\"UP\"}"));
            assertThat(json(host.get("/actuator/health/readiness"))).isEqualTo(JSON.readTree("{\"status\":\"UP\"}"));

            for (var path : List.of("/actuator/info", "/actuator/metrics", "/actuator/prometheus", "/actuator/env",
                                    "/actuator/health/ping")) {
                assertThat(host.get(path).statusCode()).as(path).isEqualTo(404);
            }

            assertThat(host.get("/actuator", "X-API-Key", KEY).statusCode()).isEqualTo(200);
            assertThat(json(host.get("/actuator", "X-API-Key", KEY)).get("_links").fieldNames())
                .toIterable().containsExactly("self", "health-path", "health");
        }
    }

    @Test
    void healthComponents_alwaysShown_springShapeAndAggregation() throws Exception {
        var settings = Map.of("management.endpoint.health.show-details", "always");
        Map<String, HealthIndicator> indicators = Map.of("db", () -> Promise.success(Health.health(Status.OUT_OF_SERVICE, Map.of("database", "x"))),
                                                         "broken", () -> cause("pool gone").promise());

        try (var host = Host.start(settings, indicators)) {
            var health = host.get("/actuator/health");
            var body = json(health);

            assertThat(health.statusCode()).isEqualTo(503);
            assertThat(body.get("status").asText()).isEqualTo("DOWN");
            assertThat(body.get("components").fieldNames()).toIterable()
                .containsExactly("broken", "db", "diskSpace", "livenessState", "ping", "readinessState");
            assertThat(body.at("/components/broken")).isEqualTo(JSON.readTree("{\"status\":\"DOWN\",\"details\":{\"error\":\"pool gone\"}}"));
            assertThat(body.at("/components/db/details/database").asText()).isEqualTo("x");
            assertThat(body.at("/components/diskSpace/details").fieldNames()).toIterable()
                .containsExactlyInAnyOrder("total", "free", "threshold", "path", "exists");
            assertThat(body.at("/components/ping")).isEqualTo(JSON.readTree("{\"status\":\"UP\"}"));

            var db = host.get("/actuator/health/db");
            assertThat(db.statusCode()).isEqualTo(503);
            assertThat(json(db).get("status").asText()).isEqualTo("OUT_OF_SERVICE");
            assertThat(host.get("/actuator/health/nope").statusCode()).isEqualTo(404);
        }
    }

    @Test
    void sensitiveEndpoints_requireAuthentication_infoDoesNot() throws Exception {
        var settings = Map.of("management.endpoints.web.exposure.include", "*",
                              "management.info.env.enabled", "true",
                              "info.app.name", "demo");

        try (var host = Host.start(settings, Map.of())) {
            assertThat(json(host.get("/actuator/info"))).isEqualTo(JSON.readTree("{\"app\":{\"name\":\"demo\"}}"));
            assertThat(host.get("/actuator/metrics").statusCode()).isEqualTo(401);
            assertThat(host.get("/actuator").statusCode()).isEqualTo(401);
            assertThat(host.get("/actuator/prometheus").statusCode()).isEqualTo(401);

            var names = host.get("/actuator/metrics", "X-API-Key", KEY);
            assertThat(names.statusCode()).isEqualTo(200);
            assertThat(json(names).get("names").toString()).contains("requests");

            var metric = json(host.get("/actuator/metrics/requests?tag=route:a", "X-API-Key", KEY));
            assertThat(metric.get("name").asText()).isEqualTo("requests");
            assertThat(metric.at("/measurements/0/statistic").asText()).isEqualTo("COUNT");
            assertThat(metric.at("/measurements/0/value").asDouble()).isEqualTo(3.0);
            assertThat(metric.get("availableTags").toString()).isEqualTo("[{\"tag\":\"outcome\",\"values\":[\"ok\"]}]");
            assertThat(host.get("/actuator/metrics/missing", "X-API-Key", KEY).statusCode()).isEqualTo(404);
            assertThat(host.get("/actuator/metrics/requests?tag=bad", "X-API-Key", KEY).statusCode()).isEqualTo(400);

            var scrape = host.get("/actuator/prometheus", "X-API-Key", KEY);
            assertThat(scrape.statusCode()).isEqualTo(200);
            assertThat(scrape.headers().firstValue("Content-Type")).hasValue(ManagementMetrics.PROMETHEUS_CONTENT_TYPE);
            assertThat(scrape.body()).isEqualTo("requests_total 3.0\n");

            assertThat(json(host.get("/actuator", "X-API-Key", KEY)).get("_links").fieldNames()).toIterable()
                .containsExactly("self", "health-path", "health", "info", "metrics-requiredMetricName", "metrics", "prometheus");
        }
    }

    @Test
    void mediaType_negotiatesJsonOrRefuses_andPostIs405() throws Exception {
        try (var host = Host.start(Map.of(), Map.of())) {
            assertThat(host.get("/actuator/health", "Accept", "application/json").headers().firstValue("Content-Type"))
                .hasValue("application/json");
            assertThat(host.get("/actuator/health", "Accept", "text/html").statusCode()).isEqualTo(406);
            assertThat(host.send(HttpRequest.newBuilder(host.uri("/actuator/health")).POST(HttpRequest.BodyPublishers.noBody())).statusCode())
                .isEqualTo(405);
        }
    }

    @Test
    void separatePort_servesManagement_applicationListenerDoesNot() throws Exception {
        try (var host = Host.start(Map.of("management.server.port", "0"), Map.of())) {
            var port = host.server.mountPort("/actuator");

            assertThat(port.isPresent()).isTrue();
            assertThat(CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port.unwrap() + "/actuator/health")).build(),
                                   HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
            assertThat(host.get("/actuator/health").statusCode()).isEqualTo(404);
        }
    }

    @Test
    void portMinusOne_disablesHttpManagement() throws Exception {
        try (var host = Host.start(Map.of("management.server.port", "-1"), Map.of())) {
            assertThat(host.get("/actuator/health").statusCode()).isEqualTo(404);
        }
    }

    @Test
    void managementConfig_unknownKey_refused() {
        assertThat(ManagementConfig.managementConfig(Map.of("management.endpoints.web.exposure.inclde", "*")).isFailure()).isTrue();
        assertThat(ManagementConfig.managementConfig(Map.of("management.endpoint.health.show-details", "sometimes")).isFailure()).isTrue();
    }

    private static JsonNode json(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body());
    }

    record Host(TerraHttpServer server) implements AutoCloseable {
        static Host start(Map<String, String> settings, Map<String, HealthIndicator> indicators) {
            var registry = new SimpleMeterRegistry();
            registry.counter("requests", "route", "a", "outcome", "ok").increment(2);
            registry.counter("requests", "route", "b", "outcome", "ok").increment();
            registry.counter("requests", "route", "a", "outcome", "ok").increment();
            var metrics = ManagementMetrics.managementMetrics(registry, () -> Option.some("requests_total 3.0\n"));
            var config = ManagementConfig.managementConfig(settings).unwrap();
            var management = TerraManagement.terraManagement(config, indicators, Map.of(), Option.some(metrics)).unwrap();
            var mounts = management.mount(-2).unwrap().stream().toList();
            var http = TerraHttpConfig.terraHttpConfig(HttpServerConfig.httpServerConfig("terra-test", 0),
                                                       RouteMountMode.pathMode(), SecurityPolicy.apiKeyRequired()).unwrap();
            var server = TerraHttpServer.start(application(), http, HttpAuthenticator.apiKeyValidator(Set.of(KEY)), List.of(),
                                               JsonMapper.defaultJsonMapper(), mounts)
                                        .await(timeSpan(10).seconds()).unwrap();
            return new Host(server);
        }

        URI uri(String path) {
            return URI.create("http://127.0.0.1:" + server.port() + path);
        }

        HttpResponse<String> get(String path, String... headers) throws Exception {
            var builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(5));
            if (headers.length > 0) { builder.headers(headers); }
            return send(builder);
        }

        HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
            return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }

        public void close() {
            server.close().await(timeSpan(10).seconds()).unwrap();
        }
    }

    private static TerraApplication application() {
        var factory = new Factory<>(Sample.class, new Sample());
        var provider = new ResourceProviderFacade() {
            public <T> Promise<T> provide(Class<T> type, String section) { return cause("unexpected resource").promise(); }
            public <T> Promise<T> provide(Class<T> type, String section, ProvisioningContext context) { return provide(type, section); }
            public Promise<Unit> releaseAll(String scope) { return Promise.unitPromise(); }
        };
        return TerraApplication.start(new TerraBlueprint(List.of(factory.artifact())), List.of(factory),
            _ -> ConfigurationProvider.builder().build(), provider).await(timeSpan(5).seconds()).unwrap();
    }
}
