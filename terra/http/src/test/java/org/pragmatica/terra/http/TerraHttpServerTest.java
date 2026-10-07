// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.terra.http;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.ApiKeyEntry;
import org.pragmatica.aether.http.adapter.ErrorMapper;
import org.pragmatica.aether.http.adapter.SliceRouter;
import org.pragmatica.aether.http.adapter.SliceRouterFactory;
import org.pragmatica.aether.http.handler.security.SecurityContextHolder;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.http.security.HttpAuthenticator;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.aether.slice.ResourceProviderFacade;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.routing.*;
import org.pragmatica.http.server.HttpServerConfig;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.terra.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.lang.utils.Causes.cause;

class TerraHttpServerTest {
    record Sample() {}
    record Other() {}
    record Factory<T>(Class<T> sliceType, T value) implements TerraFactory<T> {
        public String artifact() { return "test:" + sliceType.getSimpleName() + ":1"; }
        public List<Class<?>> dependencies() { return List.of(); }
        public Promise<T> create(TerraContext context) { return Promise.success(value); }
    }
    record Routes<T>(Class<T> sliceType, List<Route<?>> routes, SliceVersionRegistry registry) implements SliceRouterFactory<T> {
        public int routeSecurityContract() { return 1; }
        public SliceRouter create(T slice) { return create(slice, JsonMapper.defaultJsonMapper()); }
        public SliceRouter create(T slice, JsonMapper mapper) { return create(slice, mapper, RouteMountMode.pathMode()); }
        public SliceRouter create(T slice, JsonMapper mapper, RouteMountMode mode) {
            var source = new RouteSource() {
                public Stream<Route<?>> routes() { return routes.stream(); }
                public SliceVersionRegistry versionRegistry() { return registry; }
            };
            return SliceRouter.sliceRouter(source, ErrorMapper.defaultMapper(), mapper, mode);
        }
    }
    private static final Map<String, ApiKeyEntry> KEYS = Map.of(
        "reader-secret", ApiKeyEntry.apiKeyEntry("reader", Set.of("viewer"), "VIEWER"),
        "admin-secret", ApiKeyEntry.apiKeyEntry("operator", Set.of("admin"), "ADMIN"));

    @Test void serve_securityInheritanceAndRoles_enforcesActualHandlerAndScopedPrincipal() throws Exception {
        var routes = List.<Route<?>>of(
            Route.<String>get("/public").withoutParameters().to(_ -> Promise.success("open")).withSecurity(SecurityPolicy.publicRoute()).asText(),
            Route.<String>get("/private").withoutParameters().to(_ -> Promise.success(SecurityContextHolder.currentContext().unwrap().principal().value()))
                .withSecurity(SecurityPolicy.unspecified()).asText(),
            Route.<String>get("/admin").withoutParameters().to(_ -> Promise.success("admin")).withSecurity(SecurityPolicy.roleRequired("admin")).asText());
        var host = start(routes, HttpAuthenticator.apiKeyValidator(KEYS), new AtomicInteger());
        try (var client = HttpClient.newHttpClient()) {
            assertThat(get(client, host, "/public").statusCode()).isEqualTo(200);
            assertThat(get(client, host, "/private").statusCode()).isEqualTo(401);
            assertThat(get(client, host, "/private", "X-API-Key", "bad").statusCode()).isEqualTo(401);
            assertThat(get(client, host, "/private", "X-API-Key", "reader-secret").body()).contains("reader");
            assertThat(get(client, host, "/admin", "X-API-Key", "reader-secret").statusCode()).isEqualTo(403);
            assertThat(get(client, host, "/admin", "X-API-Key", "admin-secret").statusCode()).isEqualTo(200);
            assertThat(get(client, host, "/__terra/health/ready").statusCode()).isEqualTo(200);
            assertThat(get(client, host, "/missing").statusCode()).isEqualTo(404);
        } finally { close(host); }
    }

    @Test void serve_headerVersions_authorizesSelectedVersionAndPreservesLifecycleHeaders() throws Exception {
        var routes = List.<Route<?>>of(
            Route.<String>get("/items").withoutParameters().to(_ -> Promise.success("v1")).versioned(1).withSecurity(SecurityPolicy.publicRoute()).asText(),
            Route.<String>get("/items").withoutParameters().to(_ -> Promise.success("v2")).versioned(2).withSecurity(SecurityPolicy.roleRequired("admin")).asText());
        var registry = new SliceVersionRegistry("/api", false, Option.some(2), List.of(
            new SliceVersionRegistry.VersionInfo(1, true, Option.some("2026-12-31")),
            new SliceVersionRegistry.VersionInfo(2, false, Option.empty())));
        var app = application(new AtomicInteger());
        var host = TerraHttpServer.start(app, config(RouteMountMode.headerMode("API-Version")), HttpAuthenticator.apiKeyValidator(KEYS),
            List.of(new Routes<>(Sample.class, routes, registry)), JsonMapper.defaultJsonMapper()).await(timeSpan(10).seconds()).unwrap();
        try (var client = HttpClient.newHttpClient()) {
            var v1 = get(client, host, "/api/items", "API-Version", "1");
            assertThat(v1.body()).isEqualTo("v1");
            assertThat(v1.headers().firstValue("Deprecation")).isPresent();
            assertThat(get(client, host, "/api/items", "API-Version", "2").statusCode()).isEqualTo(401);
            assertThat(get(client, host, "/api/items", "X-API-Key", "reader-secret").statusCode()).isEqualTo(403);
            assertThat(get(client, host, "/api/items", "X-API-Key", "admin-secret").body()).isEqualTo("v2");
            assertThat(get(client, host, "/api/items", "API-Version", "99").statusCode()).isEqualTo(404);
        } finally { close(host); }
    }

    @Test void serve_headerModePrefixFromAnotherVersion_doesNotHideMatchingVersion() throws Exception {
        var routes = List.<Route<?>>of(
            Route.<String>get("/items/").withPath(PathParameter.aLong()).to(id -> Promise.success("v1:" + id))
                .versioned(1).withSecurity(SecurityPolicy.publicRoute()).asText(),
            Route.<String>get("/items/42/").withPath(PathParameter.aLong()).to(id -> Promise.success("v2:" + id))
                .versioned(2).withSecurity(SecurityPolicy.publicRoute()).asText());
        var registry = new SliceVersionRegistry("/api", true, Option.empty(), List.of(
            new SliceVersionRegistry.VersionInfo(1, false, Option.empty()),
            new SliceVersionRegistry.VersionInfo(2, false, Option.empty())));
        var host = TerraHttpServer.start(application(new AtomicInteger()), config(RouteMountMode.headerMode("API-Version")),
            HttpAuthenticator.denyUnlessPublicValidator(), List.of(new Routes<>(Sample.class, routes, registry)),
            JsonMapper.defaultJsonMapper()).await(timeSpan(10).seconds()).unwrap();
        try (var client = HttpClient.newHttpClient()) {
            assertThat(get(client, host, "/api/items/42", "API-Version", "1").body()).isEqualTo("v1:42");
            assertThat(get(client, host, "/api/items/42").statusCode()).isEqualTo(400);
            assertThat(get(client, host, "/api/items/42/7", "API-Version", "2").body()).isEqualTo("v2:7");
        } finally { close(host); }
    }

    @Test void close_inFlightRequest_drainsBeforeReleasingResourcesAndRejectsNewWork() throws Exception {
        var result = Promise.<String>promise();
        var entered = new CountDownLatch(1);
        var released = new AtomicInteger();
        var route = Route.<String>get("/slow").withoutParameters().to(_ -> { entered.countDown(); return result; })
            .withSecurity(SecurityPolicy.publicRoute()).asText();
        var host = start(List.of(route), HttpAuthenticator.denyUnlessPublicValidator(), released);
        try (var client = HttpClient.newHttpClient()) {
            var response = client.sendAsync(request(host, "/slow"), HttpResponse.BodyHandlers.ofString());
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var closed = host.close();
            assertThat(closed.isResolved()).isFalse();
            assertThat(host.close().timeout(timeSpan(20).millis()).await(timeSpan(1).seconds()).isFailure()).isTrue();
            assertThat(released).hasValue(0);
            assertThat(host.status().ready()).isFalse();
            assertThat(get(client, host, "/__terra/health/ready").statusCode()).isEqualTo(503);
            assertThat(get(client, host, "/slow").statusCode()).isEqualTo(503);
            result.succeed("completed");
            assertThat(response.get(10, TimeUnit.SECONDS).body()).isEqualTo("completed");
            assertThat(closed.await(timeSpan(10).seconds()).isSuccess()).isTrue();
            assertThat(released).hasValue(1);
            close(host);
            assertThat(released).hasValue(1);
        } finally { result.succeed("cleanup"); close(host); }
    }

    @Test void start_duplicateRoutes_failsAndReleasesApplication() {
        var released = new AtomicInteger();
        var route = Route.<String>get("/same").withoutParameters().to(_ -> Promise.success("one")).withSecurity(SecurityPolicy.publicRoute()).asText();
        var result = TerraHttpServer.start(application(released), config(RouteMountMode.pathMode()), HttpAuthenticator.denyUnlessPublicValidator(),
            List.of(new Routes<>(Sample.class, List.of(route, route), SliceVersionRegistry.UNVERSIONED)), JsonMapper.defaultJsonMapper())
            .await(timeSpan(10).seconds());
        assertThat(result.isFailure()).isTrue();
        assertThat(released).hasValue(1);
    }

    @Test void start_unselectedFactory_doesNotExposeItsRoutes() throws Exception {
        var route = Route.<String>get("/other").withoutParameters().to(_ -> Promise.success("secret")).withSecurity(SecurityPolicy.publicRoute()).asText();
        var host = TerraHttpServer.start(application(new AtomicInteger()), config(RouteMountMode.pathMode()), HttpAuthenticator.denyUnlessPublicValidator(),
            List.of(new Routes<>(Other.class, List.of(route), SliceVersionRegistry.UNVERSIONED)), JsonMapper.defaultJsonMapper())
            .await(timeSpan(10).seconds()).unwrap();
        try (var client = HttpClient.newHttpClient()) {
            assertThat(get(client, host, "/other").statusCode()).isEqualTo(404);
        } finally { close(host); }
    }

    @Test void serve_handlerDefect_doesNotHangDrain() throws Exception {
        var route = Route.<String>get("/defect").withoutParameters().to(_ -> { throw new IllegalStateException("private details"); })
            .withSecurity(SecurityPolicy.publicRoute()).asText();
        var host = start(List.of(route), HttpAuthenticator.denyUnlessPublicValidator(), new AtomicInteger());
        try (var client = HttpClient.newHttpClient()) {
            assertThat(get(client, host, "/defect").statusCode()).isEqualTo(500);
        } finally { close(host); }
    }

    @Test void serve_jwtWithLiveJwks_verifiesSignatureClaimsAndRole() throws Exception {
        var generator = java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        var publicKey = (java.security.interfaces.RSAPublicKey) pair.getPublic();
        var json = "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"test-key\",\"alg\":\"RS256\",\"n\":\"" + unsigned(publicKey.getModulus())
            + "\",\"e\":\"" + unsigned(publicKey.getPublicExponent()) + "\"}]}";
        var jwks = org.pragmatica.http.server.HttpServer.httpServer(HttpServerConfig.httpServerConfig("jwks", 0),
            (_, writer) -> writer.ok(json)).await(timeSpan(5).seconds()).unwrap();
        var jwt = org.pragmatica.aether.config.JwtConfig.jwtConfig("http://127.0.0.1:" + jwks.port() + "/keys",
            "terra-tests", "terra-app").unwrap();
        var route = Route.<String>get("/jwt").withoutParameters().to(_ -> Promise.success(SecurityContextHolder.currentContext().unwrap().principal().value()))
            .withSecurity(SecurityPolicy.roleRequired("admin")).asText();
        var host = start(List.of(route), HttpAuthenticator.jwtValidator(jwt), new AtomicInteger());
        try (var client = HttpClient.newHttpClient()) {
            var now = System.currentTimeMillis() / 1000;
            var valid = token(pair, "terra-tests", "admin", now + 300);
            assertThat(get(client, host, "/jwt", "Authorization", "Bearer " + valid).body()).contains("alice");
            assertThat(get(client, host, "/jwt", "Authorization", "Bearer " + token(pair, "wrong-issuer", "admin", now + 300)).statusCode()).isEqualTo(401);
            assertThat(get(client, host, "/jwt", "Authorization", "Bearer " + token(pair, "terra-tests", "admin", now - 120)).statusCode()).isEqualTo(401);
            assertThat(get(client, host, "/jwt", "Authorization", "Bearer " + token(pair, "terra-tests", "viewer", now + 300)).statusCode()).isEqualTo(403);
            var forged = valid.substring(0, valid.lastIndexOf('.') + 1) + "AAAA";
            assertThat(get(client, host, "/jwt", "Authorization", "Bearer " + forged).statusCode()).isEqualTo(401);
        } finally {
            close(host);
            jwks.stop().await(timeSpan(10).seconds()).unwrap();
        }
    }

    @Test void start_occupiedPort_releasesApplicationAndAuthenticator() {
        var occupied = org.pragmatica.http.server.HttpServer.httpServer(HttpServerConfig.httpServerConfig("occupied", 0), (_, _) -> {})
            .await(timeSpan(5).seconds()).unwrap();
        var released = new AtomicInteger();
        var authClosed = new AtomicInteger();
        var auth = new HttpAuthenticator() {
            public org.pragmatica.lang.Result<org.pragmatica.aether.http.handler.security.SecurityContext> validate(
                org.pragmatica.aether.http.handler.HttpRequestContext request, SecurityPolicy policy) {
                return cause("unused").result();
            }
            public Promise<Unit> close() { authClosed.incrementAndGet(); return Promise.unitPromise(); }
        };
        try {
            var config = TerraHttpConfig.terraHttpConfig(HttpServerConfig.httpServerConfig("conflict", occupied.port()),
                RouteMountMode.pathMode(), SecurityPolicy.apiKeyRequired()).unwrap();
            var result = TerraHttpServer.start(application(released), config, auth, List.of(), JsonMapper.defaultJsonMapper())
                .await(timeSpan(10).seconds());
            assertThat(result.isFailure()).isTrue();
            assertThat(released).hasValue(1);
            assertThat(authClosed).hasValue(1);
        } finally { occupied.stop().await(timeSpan(10).seconds()).unwrap(); }
    }

    private static String unsigned(java.math.BigInteger value) {
        var bytes = value.toByteArray();
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            bytes[0] == 0 ? java.util.Arrays.copyOfRange(bytes, 1, bytes.length) : bytes);
    }

    private static String token(java.security.KeyPair pair, String issuer, String role, long expires) throws Exception {
        var encoder = java.util.Base64.getUrlEncoder().withoutPadding();
        var header = encoder.encodeToString("{\"alg\":\"RS256\",\"kid\":\"test-key\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var payload = encoder.encodeToString(("{\"sub\":\"alice\",\"iss\":\"" + issuer + "\",\"aud\":\"terra-app\",\"role\":\"" + role
            + "\",\"exp\":" + expires + "}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var signed = header + "." + payload;
        var signature = java.security.Signature.getInstance("SHA256withRSA");
        signature.initSign(pair.getPrivate());
        signature.update(signed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return signed + "." + encoder.encodeToString(signature.sign());
    }

    @Test void serve_tlsListener_acceptsHttpsRequests() throws Exception {
        var config = TerraHttpConfig.terraHttpConfig(HttpServerConfig.httpServerConfig("tls", 0)
            .withTls(org.pragmatica.net.tcp.TlsConfig.selfSignedServer()), RouteMountMode.pathMode(), SecurityPolicy.publicRoute()).unwrap();
        var host = TerraHttpServer.start(application(new AtomicInteger()), config, HttpAuthenticator.denyUnlessPublicValidator(),
            List.of(), JsonMapper.defaultJsonMapper()).await(timeSpan(10).seconds()).unwrap();
        var trust = new javax.net.ssl.X509TrustManager() {
            public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
            public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String type) {}
            public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String type) {}
        };
        var tls = javax.net.ssl.SSLContext.getInstance("TLS");
        tls.init(null, new javax.net.ssl.TrustManager[]{trust}, new java.security.SecureRandom());
        try (var client = HttpClient.newBuilder().sslContext(tls).build()) {
            var response = client.send(HttpRequest.newBuilder(URI.create("https://localhost:" + host.port() + "/__terra/health/ready"))
                .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
        } finally { close(host); }
    }

    @Test void serve_nullHandlerPromise_failsAndDoesNotHangDrain() throws Exception {
        var route = Route.<String>get("/null").withoutParameters().to(_ -> null)
            .withSecurity(SecurityPolicy.publicRoute()).asText();
        var host = start(List.of(route), HttpAuthenticator.denyUnlessPublicValidator(), new AtomicInteger());
        try (var client = HttpClient.newHttpClient()) {
            assertThat(get(client, host, "/null").statusCode()).isEqualTo(500);
        } finally { close(host); }
    }

    private static TerraHttpServer start(List<Route<?>> routes, HttpAuthenticator auth, AtomicInteger released) {
        return TerraHttpServer.start(application(released), config(RouteMountMode.pathMode()), auth,
            List.of(new Routes<>(Sample.class, routes, SliceVersionRegistry.UNVERSIONED)), JsonMapper.defaultJsonMapper())
            .await(timeSpan(10).seconds()).unwrap();
    }
    private static TerraHttpConfig config(RouteMountMode mode) {
        return TerraHttpConfig.terraHttpConfig(HttpServerConfig.httpServerConfig("terra-test", 0), mode, SecurityPolicy.apiKeyRequired()).unwrap();
    }
    private static TerraApplication application(AtomicInteger released) {
        var factory = new Factory<>(Sample.class, new Sample());
        var provider = new ResourceProviderFacade() {
            public <T> Promise<T> provide(Class<T> type, String section) { return cause("unexpected resource").promise(); }
            public <T> Promise<T> provide(Class<T> type, String section, ProvisioningContext context) { return provide(type, section); }
            public Promise<Unit> releaseAll(String scope) { released.incrementAndGet(); return Promise.unitPromise(); }
        };
        return TerraApplication.start(new TerraBlueprint(List.of(factory.artifact())), List.of(factory),
            _ -> ConfigurationProvider.builder().build(), provider).await(timeSpan(5).seconds()).unwrap();
    }
    private static HttpRequest request(TerraHttpServer host, String path, String... headers) {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + host.port() + path)).timeout(Duration.ofSeconds(5));
        if (headers.length > 0) { builder.headers(headers); }
        return builder.build();
    }
    private static HttpResponse<String> get(HttpClient client, TerraHttpServer host, String path, String... headers) throws Exception {
        return client.send(request(host, path, headers), HttpResponse.BodyHandlers.ofString());
    }
    private static void close(TerraHttpServer host) {
        assertThat(host.close().await(timeSpan(10).seconds()).isSuccess()).isTrue();
    }
}
