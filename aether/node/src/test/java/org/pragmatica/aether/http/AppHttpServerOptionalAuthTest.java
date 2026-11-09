// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.http;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.ApiKeyEntry;
import org.pragmatica.aether.config.AuthSchemes;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.JwtConfig;
import org.pragmatica.aether.config.JwtKeyMode;
import org.pragmatica.aether.config.JwtSecret;
import org.pragmatica.aether.config.SecurityMode;
import org.pragmatica.aether.config.TimeoutsConfig.ForwardingTimeouts;
import org.pragmatica.aether.http.HttpRoutePublisher.LocalRouteInfo;
import org.pragmatica.aether.http.adapter.RouteDecorator;
import org.pragmatica.aether.http.adapter.SliceRouter;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.http.handler.security.SecurityContextHolder;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.slice.ObservabilityCellRegistrar;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.slice.blueprint.SecurityOverrides;
import org.pragmatica.aether.slice.kvstore.AetherKey.HttpNodeRouteKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.routing.SliceVersionRegistry;
import org.pragmatica.http.routing.VersioningMetricsSink;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Unit.unit;

/// #2946 — the optional-authentication route policy through a live `AppHttpServer` with a real `HttpClient`:
/// no credential is served anonymously (the slice sees an empty context), an invalid / expired / forged / wrong-`alg`
/// credential is a 401 with the #2926 challenge (`<first scheme> realm="Aether", error="invalid_token"`), and a valid
/// credential reaches the slice as its validated `SecurityContext`. `public` keeps ignoring credentials and the
/// required policies are unchanged. The stub slice answers with the context it was invoked under, which is what a
/// generated route hands to a `Principal` / `SecurityContext` parameter.
class AppHttpServerOptionalAuthTest {
    private static final NodeId SELF_NODE = NodeId.nodeId("test-node-optional-auth").unwrap();
    private static final Artifact TEST_ARTIFACT = Artifact.artifact("com.example:optional:1.0.0").unwrap();
    private static final String SECRET = "a-test-only-shared-secret-of-32-bytes!";
    private static final String API_KEY = "optional-auth-test-key-24680";
    private static final String HS256 = "{\"alg\":\"HS256\"}";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private AppHttpServer server;
    private HttpClient httpClient;
    private int port;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop().await();
            server = null;
        }
    }

    @Test
    void jwt_noToken_servedAnonymously() throws Exception {
        startServer(jwtMode(AuthSchemes.DEFAULT), SecurityPolicy.optional());

        var response = get(Map.of());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("authenticated=false");
    }

    @Test
    void jwt_garbageToken_is401InvalidToken() throws Exception {
        assertRefusedInvalidToken("Bearer not-a-jwt");
    }

    @Test
    void jwt_bareBearerScheme_is401InvalidToken() throws Exception {
        assertRefusedInvalidToken("Bearer");
    }

    @Test
    void jwt_unknownScheme_is401InvalidToken() throws Exception {
        assertRefusedInvalidToken("Basic dXNlcjpwdw==");
    }

    @Test
    void jwt_expiredToken_is401InvalidToken() throws Exception {
        assertRefusedInvalidToken("Bearer " + token(HS256, "{\"sub\":\"alice\",\"exp\":" + (now() - 3600) + "}", SECRET));
    }

    @Test
    void jwt_badSignature_is401InvalidToken() throws Exception {
        assertRefusedInvalidToken("Bearer " + token(HS256, validClaims(), "another-shared-secret-of-32-bytes!!!"));
    }

    @Test
    void jwt_wrongAlg_is401InvalidToken() throws Exception {
        assertRefusedInvalidToken("Bearer " + token("{\"alg\":\"RS256\",\"kid\":\"k1\"}", validClaims(), SECRET));
    }

    @Test
    void jwt_invalidToken_challengeNamesTheFirstConfiguredScheme() throws Exception {
        startServer(jwtMode(List.of("Token", "Bearer")), SecurityPolicy.optional());

        var response = get(Map.of("Authorization", "Token not-a-jwt"));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Token realm=\"Aether\", error=\"invalid_token\"");
    }

    @Test
    void jwt_validToken_reachesTheSliceAsItsValidatedContext() throws Exception {
        startServer(jwtMode(AuthSchemes.DEFAULT), SecurityPolicy.optional());

        var response = get(Map.of("Authorization", "Bearer " + token(HS256, validClaims(), SECRET)));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("authenticated=true").contains("alice");
    }

    @Test
    void jwt_publicRoute_stillIgnoresAPresentedToken() throws Exception {
        startServer(jwtMode(AuthSchemes.DEFAULT), SecurityPolicy.publicRoute());

        var response = get(Map.of("Authorization", "Bearer not-a-jwt"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("authenticated=false");
    }

    @Test
    void jwt_roleRoute_unchanged_noTokenIs401_tokenWithoutTheRoleIs403() throws Exception {
        startServer(jwtMode(AuthSchemes.DEFAULT), SecurityPolicy.roleRequired("admin"));

        assertThat(get(Map.of()).statusCode()).isEqualTo(401);
        assertThat(get(Map.of("Authorization", "Bearer " + token(HS256, validClaims(), SECRET))).statusCode()).isEqualTo(403);
    }

    @Test
    void apiKey_noKey_servedAnonymously_wrongKeyIs401_validKeyReachesTheSlice() throws Exception {
        startServer(AppHttpConfig.appHttpConfig(0, Set.of(API_KEY)), SecurityPolicy.optional());

        var anonymous = get(Map.of());
        var wrong = get(Map.of("X-API-Key", "wrong-key"));
        var valid = get(Map.of("X-API-Key", API_KEY));

        assertThat(anonymous.statusCode()).isEqualTo(200);
        assertThat(anonymous.body()).contains("authenticated=false");
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(wrong.headers().firstValue("WWW-Authenticate")).contains("ApiKey realm=\"Aether\", error=\"invalid_token\"");
        assertThat(valid.statusCode()).isEqualTo(200);
        assertThat(valid.body()).contains("authenticated=true");
    }

    @Test
    void none_optionalRoute_servedWithAnEmptyContext_notTheSystemGrant() throws Exception {
        startServer(config(SecurityMode.NONE, Option.empty()), SecurityPolicy.optional());

        var response = get(Map.of("Authorization", "Bearer not-a-jwt"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("authenticated=false");
    }

    private void assertRefusedInvalidToken(String authorization) throws Exception {
        startServer(jwtMode(AuthSchemes.DEFAULT), SecurityPolicy.optional());

        var response = get(Map.of("Authorization", authorization));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).doesNotContain("authenticated=");
        assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer realm=\"Aether\", error=\"invalid_token\"");
    }

    private static AppHttpConfig jwtMode(List<String> schemes) {
        var jwt = JwtConfig.jwtConfig("",
                                      Option.empty(),
                                      Option.empty(),
                                      "role",
                                      3600,
                                      30,
                                      schemes,
                                      JwtKeyMode.HS256,
                                      Option.some(JwtSecret.jwtSecret(SECRET).unwrap()))
                           .unwrap();

        return config(SecurityMode.JWT, Option.some(jwt));
    }

    private static AppHttpConfig config(SecurityMode mode, Option<JwtConfig> jwtConfig) {
        return AppHttpConfig.appHttpConfig(true,
                                           0,
                                           Map.<String, ApiKeyEntry> of(),
                                           AppHttpConfig.DEFAULT_MAX_REQUEST_SIZE,
                                           mode,
                                           jwtConfig,
                                           HttpProtocol.H1)
                            .unwrap();
    }

    private static long now() {
        return System.currentTimeMillis() / 1000;
    }

    private static String validClaims() {
        return "{\"sub\":\"alice\",\"exp\":" + (now() + 3600) + ",\"role\":\"editor\"}";
    }

    private static String token(String header, String claims, String secret) throws GeneralSecurityException {
        var signed = B64.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "." + B64.encodeToString(claims.getBytes(StandardCharsets.UTF_8));
        var mac = Mac.getInstance("HmacSHA256");

        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));

        return signed + "." + B64.encodeToString(mac.doFinal(signed.getBytes(StandardCharsets.US_ASCII)));
    }

    private void startServer(AppHttpConfig config, SecurityPolicy routePolicy) {
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        var publisher = new StubRoutePublisher("GET", "/local/", routePolicy, new StubSliceRouter());

        server = AppHttpServer.appHttpServer(config,
                                             ForwardingTimeouts.forwardingTimeouts(),
                                             SELF_NODE,
                                             HttpRouteRegistry.httpRouteRegistry(),
                                             Option.some(publisher),
                                             Option.none(),
                                             Option.none(),
                                             Option.none(),
                                             Option.none(),
                                             Option.none(),
                                             Option.none(),
                                             Option.none(),
                                             Option.none());
        TlsProbe.requireStarted(server.start().await(), "app listener");
        port = TlsProbe.requireBoundPort(server.boundPort(), "app listener");
    }

    private HttpResponse<String> get(Map<String, String> headers) throws Exception {
        var builder = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/local/thing")).GET();

        headers.forEach(builder::header);

        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private record StubRoutePublisher(String httpMethod, String pathPrefix, SecurityPolicy security, SliceRouter router)
        implements HttpRoutePublisher {
        private boolean matches(String method, String path) {
            return httpMethod.equalsIgnoreCase(method) && path.startsWith(pathPrefix);
        }

        @Override
        public Set<HttpNodeRouteKey> allLocalRoutes() {
            return Set.of(HttpNodeRouteKey.httpNodeRouteKey(httpMethod, pathPrefix, SELF_NODE));
        }

        @Override
        public Option<SliceRouter> findLocalRouter(String method, String prefix) {
            return matches(method, prefix)
                   ? Option.some(router)
                   : Option.none();
        }

        @Override
        public Option<LocalRouteInfo> findLocalRoute(String method, String path) {
            return matches(method, path)
                   ? Option.some(new LocalRouteInfo(httpMethod, pathPrefix, TEST_ARTIFACT.asString(), "create", security))
                   : Option.none();
        }

        @Override
        public Promise<Unit> publishRoutes(Artifact artifact,
                                           ClassLoader classLoader,
                                           Object sliceInstance,
                                           SliceInvokerFacade invokerFacade) {
            return Promise.success(unit());
        }

        @Override
        public boolean hasRoutes(ClassLoader classLoader, Object sliceInstance) {
            return true;
        }

        @Override
        public Promise<Unit> unpublishRoutes(Artifact artifact) {
            return Promise.success(unit());
        }

        @Override
        public Option<SliceRouter> getSliceRouter(Artifact artifact) {
            return Option.some(router);
        }

        @Override
        public Unit updateSecurityOverrides(SecurityOverrides overrides) {
            return unit();
        }

        @Override
        public Unit setVersioningMetricsSink(VersioningMetricsSink sink) {
            return unit();
        }

        @Override
        public Map<Artifact, SliceVersionRegistry> versionRegistries() {
            return Map.of();
        }

        @Override
        public Unit setObservabilityCellRegistrar(ObservabilityCellRegistrar registrar) {
            return unit();
        }
    }

    /// Answers 200 with the `SecurityContext` the dispatch put in scope: what a generated route injects into a
    /// `Principal` / `SecurityContext` parameter.
    private static final class StubSliceRouter implements SliceRouter {
        @Override
        public Promise<HttpResponseData> handle(HttpRequestContext request) {
            return Promise.success(HttpResponseData.httpResponseData(200, describe(SecurityContextHolder.currentContext())));
        }

        private static String describe(Option<SecurityContext> context) {
            return context.map(ctx -> "authenticated=" + ctx.isAuthenticated() + " principal=" + ctx.principal().value())
                          .or("no-context");
        }

        @Override
        public SliceVersionRegistry versionRegistry() {
            return SliceVersionRegistry.UNVERSIONED;
        }

        @Override
        public SliceRouter withObservability(String sliceName, VersioningMetricsSink sink) {
            return this;
        }

        @Override
        public SliceRouter withInvocationCells(RouteDecorator decorator) {
            return this;
        }
    }
}
