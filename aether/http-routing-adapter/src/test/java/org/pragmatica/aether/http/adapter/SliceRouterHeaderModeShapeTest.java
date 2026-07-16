// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.adapter;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.http.CommonContentType;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteMountMode;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.http.routing.SliceVersionRegistry;
import org.pragmatica.http.routing.SliceVersionRegistry.VersionInfo;
import org.pragmatica.http.routing.VersioningMetricsSink;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.http.routing.PathParameter.aLong;
import static org.pragmatica.http.routing.PathParameter.spacer;

/// Structural regression coverage for header-mode versioned dispatch across MIXED-arity route shapes
/// within one version (#198 §7).
///
/// A slice declaring, per version, a collection route (`GET /items`), a get route (`GET /items/{id}`)
/// and a nested octet-stream route (`GET /items/{id}/image`) mounts all of them at one bare prefix in
/// header mode. Dispatch must select the version AND the path-shape independently: version comes from
/// the `API-Version` header, the shape from arity+spacer matching. The previous dispatcher collapsed
/// every route of a version to one arbitrary route, so the collection and nested shapes were
/// unreachable for any version with more than one route — this test pins the corrected behavior.
class SliceRouterHeaderModeShapeTest {
    private static final String API_VERSION = "API-Version";
    private static final RouteMountMode HEADER_MODE = RouteMountMode.headerMode(API_VERSION);

    private final JsonMapper jsonMapper = JsonMapper.defaultJsonMapper();

    private SliceRouter router() {
        var registry = SliceVersionRegistry.sliceVersionRegistry("/api/catalog",
                                                                 false,
                                                                 Option.option(2),
                                                                 List.of(VersionInfo.versionInfo(1, false, Option.none()),
                                                                         VersionInfo.versionInfo(2, false, Option.none())));
        var source = versionedSource(registry);
        return SliceRouter.sliceRouter(source, ErrorMapper.defaultMapper(), jsonMapper, HEADER_MODE);
    }

    private RouteSource versionedSource(SliceVersionRegistry registry) {
        record versionedSource(SliceVersionRegistry versionRegistry, List<Route<?>> all) implements RouteSource {
            @Override
            public Stream<Route<?>> routes() {
                return all.stream();
            }
        }
        return new versionedSource(registry,
                                   Stream.concat(versionRoutes(1).stream(), versionRoutes(2).stream())
                                         .toList());
    }

    private List<Route<?>> versionRoutes(int version) {
        var list = Route.<String>get("/items")
                        .to(_ -> Promise.success("list-v" + version))
                        .versioned(version)
                        .as(CommonContentType.APPLICATION_JSON);
        var get = Route.<String>get("/items/")
                       .withPath(aLong())
                       .to(id -> Promise.success("get-v" + version + "-" + id))
                       .versioned(version)
                       .as(CommonContentType.APPLICATION_JSON);
        var image = Route.<String>get("/items/")
                         .withPath(aLong(), spacer("image"))
                         .to((id, _) -> Promise.success("image-v" + version + "-" + id))
                         .versioned(version)
                         .as(CommonContentType.APPLICATION_OCTET_STREAM);
        return List.of(list, get, image);
    }

    private HttpResponseData send(String path, int version) {
        return send(path, Map.of(API_VERSION.toLowerCase(), List.of(String.valueOf(version))), router());
    }

    private HttpResponseData sendNoHeader(String path, SliceRouter router) {
        return send(path, Map.of(), router);
    }

    private HttpResponseData send(String path, Map<String, List<String>> headers, SliceRouter router) {
        var request = HttpRequestContext.httpRequestContext(path, "GET", Map.of(), headers, "req_test");
        return router.handle(request)
                     .await()
                     .unwrap();
    }

    private static String body(HttpResponseData response) {
        return new String(response.body());
    }

    private static String contentType(HttpResponseData response) {
        return response.headers()
                       .getOrDefault("Content-Type", "");
    }

    @Test
    void collectionRoute_isReachable_forSelectedVersion() {
        var v2 = send("/api/catalog/items", 2);
        assertThat(v2.statusCode()).isEqualTo(200);
        assertThat(body(v2)).contains("list-v2");

        var v1 = send("/api/catalog/items", 1);
        assertThat(v1.statusCode()).isEqualTo(200);
        assertThat(body(v1)).contains("list-v1");
    }

    @Test
    void nestedSpacerRoute_isReachable_andKeepsContentType() {
        var v2 = send("/api/catalog/items/1/image", 2);
        assertThat(v2.statusCode()).isEqualTo(200);
        assertThat(body(v2)).isEqualTo("image-v2-1");
        assertThat(contentType(v2)).isEqualTo(CommonContentType.APPLICATION_OCTET_STREAM.headerText());

        var v1 = send("/api/catalog/items/9/image", 1);
        assertThat(v1.statusCode()).isEqualTo(200);
        assertThat(body(v1)).isEqualTo("image-v1-9");
        assertThat(contentType(v1)).isEqualTo(CommonContentType.APPLICATION_OCTET_STREAM.headerText());
    }

    @Test
    void getRoute_stillResolves_forSelectedVersion() {
        var v2 = send("/api/catalog/items/1", 2);
        assertThat(v2.statusCode()).isEqualTo(200);
        assertThat(body(v2)).contains("get-v2-1");

        var v1 = send("/api/catalog/items/1", 1);
        assertThat(v1.statusCode()).isEqualTo(200);
        assertThat(body(v1)).contains("get-v1-1");
    }

    @Test
    void noHeader_selectsDefaultVersion_acrossShapes() {
        // defaultVersion = 2; latest-wins also resolves to 2.
        var list = sendNoHeader("/api/catalog/items", router());
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(body(list)).contains("list-v2");

        var image = sendNoHeader("/api/catalog/items/3/image", router());
        assertThat(image.statusCode()).isEqualTo(200);
        assertThat(body(image)).isEqualTo("image-v2-3");
    }

    @Test
    void unknownVersionHeader_returnsNotFound() {
        var response = send("/api/catalog/items", 9);
        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void noHeader_firesMissingHeaderCounter_onlyForRoutablePath() {
        var counter = new MissingHeaderCounter();
        var observed = router().withObservability("catalog", counter);

        sendNoHeader("/api/catalog/items", observed);
        assertThat(counter.count).isEqualTo(1);

        // A bare path that matches no route in any version must NOT fire the counter (it short-circuits
        // to notFound before version selection), preserving the prior dispatcher's metric semantics.
        var missing = sendNoHeader("/api/catalog/unknown", observed);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(counter.count).isEqualTo(1);
    }

    private static final class MissingHeaderCounter implements VersioningMetricsSink {
        private int count;

        @Override
        @Contract
        public void versionedRequest(String slice, int version, String method, int status) {}

        @Override
        @Contract
        public void deprecatedRequest(String slice, int version) {}

        @Override
        @Contract
        public void missingVersionHeader(String slice) {
            count++;
        }
    }
}
