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
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.http.security.HttpAuthenticator;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.aether.slice.ResourceProviderFacade;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.http.routing.PathParameter;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteMountMode;
import org.pragmatica.http.routing.SliceVersionRegistry;
import org.pragmatica.http.server.HttpServerConfig;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.terra.TerraApplication;
import org.pragmatica.terra.TerraBlueprint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.http.routing.PathParameter.aString;
import static org.pragmatica.http.routing.PathParameter.spacer;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.lang.utils.Causes.cause;

/// #2947: route ownership is keyed by the full route shape, so sibling shapes under one base path may belong to
/// different slices (the Conduit article/comment/favorite split), while one shape owned by two slices is refused.
class TerraRouteShapeTest {
    record Articles() {}
    record Comments() {}
    record Favorites() {}

    @Test void serve_conduitSiblingShapesOfDifferentSlices_dispatchToTheOwningSlice() throws Exception {
        var host = start(List.of(new TerraHttpServerTest.Routes<>(Articles.class, articleRoutes(), SliceVersionRegistry.UNVERSIONED),
                                 new TerraHttpServerTest.Routes<>(Comments.class, commentRoutes(), SliceVersionRegistry.UNVERSIONED),
                                 new TerraHttpServerTest.Routes<>(Favorites.class, favoriteRoutes(), SliceVersionRegistry.UNVERSIONED)))
            .unwrap();
        try (var client = HttpClient.newHttpClient()) {
            assertThat(call(client, host, "GET", "/articles/a1")).isEqualTo("articles:get:a1");
            assertThat(call(client, host, "DELETE", "/articles/a1")).isEqualTo("articles:delete:a1");
            assertThat(call(client, host, "GET", "/articles/a1/comments")).isEqualTo("comments:list:a1");
            assertThat(call(client, host, "POST", "/articles/a1/comments")).isEqualTo("comments:add:a1");
            assertThat(call(client, host, "DELETE", "/articles/a1/comments/c9")).isEqualTo("comments:delete:a1:c9");
            assertThat(call(client, host, "POST", "/articles/a1/favorite")).isEqualTo("favorites:add:a1");
            assertThat(call(client, host, "DELETE", "/articles/a1/favorite")).isEqualTo("favorites:delete:a1");
            assertThat(call(client, host, "GET", "/articles/a1/nothing")).isEqualTo("404");
        } finally { close(host); }
    }

    @Test void start_sameShapeOwnedByTwoSlices_isRefusedNamingBothSlices() {
        var first = List.<Route<?>>of(Route.<String>get("/articles/").withPath(aString()).to(slug -> Promise.success("one")).withSecurity(SecurityPolicy.publicRoute()).asText());
        var second = List.<Route<?>>of(Route.<String>get("/articles/").withPath(aString()).to(id -> Promise.success("two")).withSecurity(SecurityPolicy.publicRoute()).asText());
        var result = start(List.of(new TerraHttpServerTest.Routes<>(Articles.class, first, SliceVersionRegistry.UNVERSIONED),
                                   new TerraHttpServerTest.Routes<>(Comments.class, second, SliceVersionRegistry.UNVERSIONED)));
        assertThat(result.isFailure()).isTrue();
        result.onSuccess(TerraRouteShapeTest::close).onFailure(failure -> assertThat(failure.message())
            .contains("Multiple slices own route shape").contains(Articles.class.getName()).contains(Comments.class.getName()));
    }

    @Test void start_sameSpacerShapeOwnedByTwoSlices_isRefused() {
        var first = List.<Route<?>>of(Route.<String>get("/articles/").withPath(aString(), spacer("comments")).to((slug, _) -> Promise.success("one")).withSecurity(SecurityPolicy.publicRoute()).asText());
        var second = List.<Route<?>>of(Route.<String>get("/articles/").withPath(aString(), spacer("comments")).to((id, _) -> Promise.success("two")).withSecurity(SecurityPolicy.publicRoute()).asText());
        var result = start(List.of(new TerraHttpServerTest.Routes<>(Articles.class, first, SliceVersionRegistry.UNVERSIONED),
                                   new TerraHttpServerTest.Routes<>(Comments.class, second, SliceVersionRegistry.UNVERSIONED)));
        assertThat(result.isFailure()).isTrue();
        result.onSuccess(TerraRouteShapeTest::close);
    }

    @Test void serve_shapesDifferingOnlyInArityOrSpacerSlot_belongToDifferentSlices() throws Exception {
        var articles = List.<Route<?>>of(Route.<String>get("/items/").withPath(aString(), spacer("x")).to((a, _) -> Promise.success("articles:" + a)).withSecurity(SecurityPolicy.publicRoute()).asText());
        var comments = List.<Route<?>>of(Route.<String>get("/items/").withPath(aString(), spacer("x"), aString()).to((a, _, b) -> Promise.success("comments:" + a + ":" + b)).withSecurity(SecurityPolicy.publicRoute()).asText());
        var favorites = List.<Route<?>>of(Route.<String>get("/items/").withPath(spacer("x"), aString()).to((_, a) -> Promise.success("favorites:" + a)).withSecurity(SecurityPolicy.publicRoute()).asText());
        var host = start(List.of(new TerraHttpServerTest.Routes<>(Articles.class, articles, SliceVersionRegistry.UNVERSIONED),
                                 new TerraHttpServerTest.Routes<>(Comments.class, comments, SliceVersionRegistry.UNVERSIONED),
                                 new TerraHttpServerTest.Routes<>(Favorites.class, favorites, SliceVersionRegistry.UNVERSIONED))).unwrap();
        try (var client = HttpClient.newHttpClient()) {
            assertThat(call(client, host, "GET", "/items/1/x")).isEqualTo("articles:1");
            assertThat(call(client, host, "GET", "/items/1/x/2")).isEqualTo("comments:1:2");
            assertThat(call(client, host, "GET", "/items/x/1")).isEqualTo("favorites:1");
        } finally { close(host); }
    }

    private static List<Route<?>> articleRoutes() {
        return List.of(
            Route.<String>get("/articles/").withPath(aString()).to(slug -> Promise.success("articles:get:" + slug)).withSecurity(SecurityPolicy.publicRoute()).asText(),
            Route.<String>delete("/articles/").withPath(aString()).to(slug -> Promise.success("articles:delete:" + slug)).withSecurity(SecurityPolicy.publicRoute()).asText());
    }
    private static List<Route<?>> commentRoutes() {
        return List.of(
            Route.<String>get("/articles/").withPath(aString(), spacer("comments")).to((slug, _) -> Promise.success("comments:list:" + slug)).withSecurity(SecurityPolicy.publicRoute()).asText(),
            Route.<String>post("/articles/").withPath(aString(), spacer("comments")).to((slug, _) -> Promise.success("comments:add:" + slug)).withSecurity(SecurityPolicy.publicRoute()).asText(),
            Route.<String>delete("/articles/").withPath(aString(), spacer("comments"), aString()).to((slug, _, id) -> Promise.success("comments:delete:" + slug + ":" + id)).withSecurity(SecurityPolicy.publicRoute()).asText());
    }
    private static List<Route<?>> favoriteRoutes() {
        return List.of(
            Route.<String>post("/articles/").withPath(aString(), spacer("favorite")).to((slug, _) -> Promise.success("favorites:add:" + slug)).withSecurity(SecurityPolicy.publicRoute()).asText(),
            Route.<String>delete("/articles/").withPath(aString(), spacer("favorite")).to((slug, _) -> Promise.success("favorites:delete:" + slug)).withSecurity(SecurityPolicy.publicRoute()).asText());
    }

    private static org.pragmatica.lang.Result<TerraHttpServer> start(List<org.pragmatica.aether.http.adapter.SliceRouterFactory<?>> routes) {
        return TerraHttpServer.start(application(), config(), HttpAuthenticator.denyUnlessPublicValidator(), routes, JsonMapper.defaultJsonMapper())
            .await(timeSpan(10).seconds());
    }
    private static String call(HttpClient client, TerraHttpServer host, String method, String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + host.port() + path)).timeout(Duration.ofSeconds(5))
            .method(method, HttpRequest.BodyPublishers.noBody()).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode() == 200 ? response.body() : String.valueOf(response.statusCode());
    }
    private static TerraHttpConfig config() {
        return TerraHttpConfig.terraHttpConfig(HttpServerConfig.httpServerConfig("terra-shape-test", 0), RouteMountMode.pathMode(), SecurityPolicy.apiKeyRequired()).unwrap();
    }
    private static TerraApplication application() {
        var factories = List.<org.pragmatica.terra.TerraFactory<?>>of(new TerraHttpServerTest.Factory<>(Articles.class, new Articles()),
                                                                       new TerraHttpServerTest.Factory<>(Comments.class, new Comments()),
                                                                       new TerraHttpServerTest.Factory<>(Favorites.class, new Favorites()));
        var provider = new ResourceProviderFacade() {
            public <T> Promise<T> provide(Class<T> type, String section) { return cause("unexpected resource").promise(); }
            public <T> Promise<T> provide(Class<T> type, String section, ProvisioningContext context) { return provide(type, section); }
            public Promise<Unit> releaseAll(String scope) { return Promise.unitPromise(); }
        };
        return TerraApplication.start(new TerraBlueprint(factories.stream().map(org.pragmatica.terra.TerraFactory::artifact).toList()), factories,
            _ -> ConfigurationProvider.builder().build(), provider).await(timeSpan(5).seconds()).unwrap();
    }
    private static void close(TerraHttpServer host) {
        assertThat(host.close().await(timeSpan(10).seconds()).isSuccess()).isTrue();
    }
}
