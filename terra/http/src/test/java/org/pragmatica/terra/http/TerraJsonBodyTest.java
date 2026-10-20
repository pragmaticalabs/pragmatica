// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra.http;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.adapter.ErrorMapper;
import org.pragmatica.aether.http.adapter.SliceRouter;
import org.pragmatica.aether.http.adapter.SliceRouterFactory;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.http.security.HttpAuthenticator;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.aether.slice.ResourceProviderFacade;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.http.JsonCodec;
import org.pragmatica.http.routing.*;
import org.pragmatica.http.server.HttpServerConfig;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.terra.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.lang.utils.Causes.cause;

/// Terra's JSON request-body path: the context Terra rebuilds after authentication decodes the body, and it must
/// read the wire form the router writes, the same form as on Aether (#2229 F1).
public class TerraJsonBodyTest {
    public record Echo(String name, int n) {}
    public record Code(String value) {
        public static Result<Code> code(String value) { return Result.success(new Code(value)); }
    }
    public record Tagged(Code code, int n) {}
    public record VoSlice() {}
    record Sample() {}
    record Factory<T>(Class<T> sliceType, T value) implements TerraFactory<T> {
        public String artifact() { return "test:" + sliceType.getSimpleName() + ":1"; }
        public List<Class<?>> dependencies() { return List.of(); }
        public Promise<T> create(TerraContext context) { return Promise.success(value); }
    }
    record Routes<T>(Class<T> sliceType, List<Route<?>> routes) implements SliceRouterFactory<T> {
        public int routeSecurityContract() { return ROUTE_SECURITY_CONTRACT; }
        public SliceRouter create(T slice) { return create(slice, JsonMapper.defaultJsonMapper()); }
        public SliceRouter create(T slice, JsonMapper mapper) { return create(slice, mapper, RouteMountMode.pathMode()); }
        public SliceRouter create(T slice, JsonMapper mapper, RouteMountMode mode) { return router(routes, mapper, mode); }
    }
    /// A router codec no mapper reproduces: every body it decodes is the same marker, so a body that reaches the
    /// handler as the marker was decoded by the router's codec and by nothing Terra built itself.
    static final class MarkerCodec implements JsonCodec {
        private final JsonCodec writer = JsonCodecAdapter.forMapper(JsonMapper.defaultJsonMapper());
        public Result<byte[]> serialize(Object value) { return writer.serialize(value); }
        @SuppressWarnings("unchecked")
        public <T> Result<T> deserialize(byte[] bytes, TypeToken<T> token) { return Result.success((T) new Echo("router-codec", 0)); }
    }
    record MarkedRoutes<T>(Class<T> sliceType, List<Route<?>> routes) implements SliceRouterFactory<T> {
        public int routeSecurityContract() { return ROUTE_SECURITY_CONTRACT; }
        public SliceRouter create(T slice) { return create(slice, JsonMapper.defaultJsonMapper()); }
        public SliceRouter create(T slice, JsonMapper mapper) { return create(slice, mapper, RouteMountMode.pathMode()); }
        public SliceRouter create(T slice, JsonMapper mapper, RouteMountMode mode) {
            return SliceRouter.sliceRouter(source(routes), ErrorMapper.defaultMapper(), mapper, mode, new MarkerCodec());
        }
    }
    /// Found by `TerraHttpServer.start`'s own discovery (test `META-INF/services`), so the server picks the mapper.
    public static final class VoRoutes implements SliceRouterFactory<VoSlice> {
        public Class<VoSlice> sliceType() { return VoSlice.class; }
        public int routeSecurityContract() { return ROUTE_SECURITY_CONTRACT; }
        public SliceRouter create(VoSlice slice) { return create(slice, JsonMapper.defaultJsonMapper()); }
        public SliceRouter create(VoSlice slice, JsonMapper mapper) { return create(slice, mapper, RouteMountMode.pathMode()); }
        public SliceRouter create(VoSlice slice, JsonMapper mapper, RouteMountMode mode) {
            return router(List.of(
                Route.<Tagged>get("/tagged").withoutParameters().to(_ -> Promise.success(new Tagged(new Code("x"), 1)))
                     .withSecurity(SecurityPolicy.publicRoute()).asJson(),
                Route.<Tagged>post("/tagged").withBody(Tagged.class).to(body -> Promise.success(new Tagged(body.code(), body.n() + 1)))
                     .withSecurity(SecurityPolicy.publicRoute()).asJson()), mapper, mode);
        }
    }

    @Test void jsonBody_decodesValidBody_andRefusesMalformedAndMistypedBodies() throws Exception {
        var route = Route.<Echo>post("/echo").withBody(Echo.class)
                         .to(body -> Promise.success(new Echo(body.name() + "!", body.n() + 1)))
                         .withSecurity(SecurityPolicy.publicRoute()).asJson();
        var host = TerraHttpServer.start(application(Sample.class, new Sample()), config(), HttpAuthenticator.denyUnlessPublicValidator(),
            List.of(new Routes<>(Sample.class, List.<Route<?>>of(route))), JsonMapper.defaultJsonMapper())
            .await(timeSpan(10).seconds()).unwrap();
        try (var client = HttpClient.newHttpClient()) {
            var ok = post(client, host, "/echo", "{\"name\":\"x\",\"n\":3}");

            assertThat(ok.statusCode()).isEqualTo(200);
            assertThat(ok.body()).isEqualTo("{\"name\":\"x!\",\"n\":4}");
            assertThat(post(client, host, "/echo", "{\"name\":").statusCode()).isEqualTo(400);
            assertThat(post(client, host, "/echo", "{\"name\":\"x\",\"n\":\"no\"}").statusCode()).isEqualTo(400);
        } finally { close(host); }
    }

    // One wire form: a nested value object is written bare, and the response echoed back as the next body is read.
    // The rc5 object form is no longer accepted.
    @Test void nestedValueObject_responseEchoedAsBody_roundTripsInTheBareForm() throws Exception {
        var host = TerraHttpServer.start(application(VoSlice.class, new VoSlice()), config(), HttpAuthenticator.denyUnlessPublicValidator())
                                  .await(timeSpan(10).seconds()).unwrap();
        try (var client = HttpClient.newHttpClient()) {
            var written = client.send(HttpRequest.newBuilder(uri(host, "/tagged")).timeout(Duration.ofSeconds(5)).build(),
                                      HttpResponse.BodyHandlers.ofString());

            assertThat(written.statusCode()).isEqualTo(200);
            assertThat(written.body()).isEqualTo("{\"code\":\"x\",\"n\":1}");

            var echoed = post(client, host, "/tagged", written.body());

            assertThat(echoed.statusCode()).isEqualTo(200);
            assertThat(echoed.body()).isEqualTo("{\"code\":\"x\",\"n\":2}");
            assertThat(post(client, host, "/tagged", "{\"code\":{\"value\":\"x\"},\"n\":1}").statusCode()).isEqualTo(400);
        } finally { close(host); }
    }

    // F1's claim is identity, not equivalence: Terra decodes with the codec the router holds (the generated one where
    // the slice has it), never a codec of its own over a mapper, however that mapper is chosen.
    @Test void requestBody_isDecodedByTheRoutersOwnCodec() throws Exception {
        var route = Route.<Echo>post("/echo").withBody(Echo.class).to(body -> Promise.success(body))
                         .withSecurity(SecurityPolicy.publicRoute()).asJson();
        var host = TerraHttpServer.start(application(Sample.class, new Sample()), config(), HttpAuthenticator.denyUnlessPublicValidator(),
            List.of(new MarkedRoutes<>(Sample.class, List.<Route<?>>of(route))), JsonMapper.valueObjectJsonMapper())
            .await(timeSpan(10).seconds()).unwrap();
        try (var client = HttpClient.newHttpClient()) {
            var decoded = post(client, host, "/echo", "{\"name\":\"client\",\"n\":1}");

            assertThat(decoded.statusCode()).isEqualTo(200);
            assertThat(decoded.body()).isEqualTo("{\"name\":\"router-codec\",\"n\":0}");
        } finally { close(host); }
    }

    private static SliceRouter router(List<Route<?>> routes, JsonMapper mapper, RouteMountMode mode) {
        return SliceRouter.sliceRouter(source(routes), ErrorMapper.defaultMapper(), mapper, mode);
    }
    private static RouteSource source(List<Route<?>> routes) {
        return new RouteSource() {
            public Stream<Route<?>> routes() { return routes.stream(); }
            public SliceVersionRegistry versionRegistry() { return SliceVersionRegistry.UNVERSIONED; }
        };
    }
    private static URI uri(TerraHttpServer host, String path) {
        return URI.create("http://127.0.0.1:" + host.port() + path);
    }
    private static HttpResponse<String> post(HttpClient client, TerraHttpServer host, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(uri(host, path)).timeout(Duration.ofSeconds(5))
                                 .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
    private static TerraHttpConfig config() {
        return TerraHttpConfig.terraHttpConfig(HttpServerConfig.httpServerConfig("terra-json", 0), RouteMountMode.pathMode(),
                                               SecurityPolicy.apiKeyRequired()).unwrap();
    }
    private static <T> TerraApplication application(Class<T> sliceType, T slice) {
        var factory = new Factory<>(sliceType, slice);
        var provider = new ResourceProviderFacade() {
            public <R> Promise<R> provide(Class<R> type, String section) { return cause("unexpected resource").promise(); }
            public <R> Promise<R> provide(Class<R> type, String section, ProvisioningContext context) { return provide(type, section); }
            public Promise<Unit> releaseAll(String scope) { return Promise.unitPromise(); }
        };
        return TerraApplication.start(new TerraBlueprint(List.of(factory.artifact())), List.of(factory),
            _ -> ConfigurationProvider.builder().build(), provider).await(timeSpan(5).seconds()).unwrap();
    }
    private static void close(TerraHttpServer host) {
        assertThat(host.close().await(timeSpan(10).seconds()).isSuccess()).isTrue();
    }
}
