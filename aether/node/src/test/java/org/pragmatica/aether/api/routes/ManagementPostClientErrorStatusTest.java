// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.api.DynamicConfigManager;
import org.pragmatica.aether.api.LogLevelRegistry;
import org.pragmatica.aether.api.ManagementApiResponses;
import org.pragmatica.aether.config.ApiKeyEntry;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.QueryParams;
import org.pragmatica.http.routing.RequestContext;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;

import io.netty.handler.codec.http.HttpHeaders;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


/// #833 / #954 — management POSTs answered `500 Internal Server Error` for the caller's own mistakes.
/// `ProblemResponses.resolveStatus` defaults every cause that is not `HttpStatusAware` to 500, so a request
/// missing a required field, and a leader-bound write that reached a non-leader, were indistinguishable on the
/// wire from "the cluster broke". `POST /api/v1/deploy` already answered 400 ([DeployRouteStatusTest]); these
/// are the routes that did not.
///
/// Each test drives the REAL route handler from `<Source>.routes()` and feeds the emerging cause through the
/// exact `ProblemResponses.writeProblem` call `ManagementRouter.writeError` makes, because a hop that re-wraps
/// the cause erases the status mixin. The request body is a record with the offending fields `null`, which is
/// what the wire deserializer hands the handler for an omitted JSON field.
class ManagementPostClientErrorStatusTest {
    private static final String REQUEST_ID = "req-1";
    private static final String INSTANCE = "/api/v1/management";

    @Test
    void configSet_answers400_whenKeyAndValueAreMissing() {
        var routes = ConfigRoutes.configRoutes(mock(DynamicConfigManager.class), () -> node(true));
        var body = new ConfigRoutes.SetConfigRequest(null, null, Option.none());

        assertThat(statusOf(routes.routes(), ManagementRoute.CONFIG_SET, body)).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void logLevelSet_answers400_whenLoggerAndLevelAreMissing() {
        var routes = LogLevelRoutes.logLevelRoutes(mock(LogLevelRegistry.class));
        var body = new LogLevelRoutes.SetLogLevelRequest(null, null);

        assertThat(statusOf(routes.routes(), ManagementRoute.LOG_LEVEL_SET, body)).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void logLevelSet_answers400_whenLevelIsNotAKnownLevel() {
        var routes = LogLevelRoutes.logLevelRoutes(mock(LogLevelRegistry.class));
        var body = new LogLevelRoutes.SetLogLevelRequest("org.example", "LOUD");

        assertThat(statusOf(routes.routes(), ManagementRoute.LOG_LEVEL_SET, body)).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void sliceScale_answers400_whenArtifactAndInstancesAreMissing() {
        var routes = SliceRoutes.sliceRoutes(() -> node(true));
        var body = new SliceRoutes.ScaleRequest(null, null, null);

        assertThat(statusOf(routes.routes(), ManagementRoute.SLICE_SCALE, body)).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void sliceScale_answers400_whenArtifactCoordinatesAreMalformed() {
        var routes = SliceRoutes.sliceRoutes(() -> node(true));
        var body = new SliceRoutes.ScaleRequest("not-a-coordinate", 3, null);

        assertThat(statusOf(routes.routes(), ManagementRoute.SLICE_SCALE, body)).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void clusterKeysCreate_answers400_whenKeyIdAndHashAreMissing() {
        var routes = ApiKeyRoutes.apiKeyRoutes(() -> node(true), Map::of);

        try {
            var body = new ApiKeyRoutes.CreateKeyRequest(null, null, 0L, null, null, null);

            assertThat(statusOf(routes.routes(), ManagementRoute.CLUSTER_KEYS_CREATE, body)).isEqualTo(HttpStatus.BAD_REQUEST);
        } finally {
            routes.stop();
        }
    }

    @Test
    void clusterKeysRevoke_answers404_whenKeyIsUnknown() {
        var routes = ApiKeyRoutes.apiKeyRoutes(() -> node(true), Map::of);

        try {
            var body = new ApiKeyRoutes.RevokeKeyRequest(true, 0L, "");

            assertThat(statusOf(routes.routes(), ManagementRoute.CLUSTER_KEYS_REVOKE, List.of("no-such-key"), body))
                .isEqualTo(HttpStatus.NOT_FOUND);
        } finally {
            routes.stop();
        }
    }

    @Test
    void clusterKeysRevoke_answers409_whenKeyIsDeclaredInConfiguration() {
        var declared = Map.of("ops", ApiKeyEntry.apiKeyEntry("ops", Set.of("service"), "OPERATOR"));
        var routes = ApiKeyRoutes.apiKeyRoutes(() -> node(true), () -> declared);

        try {
            var body = new ApiKeyRoutes.RevokeKeyRequest(true, 0L, "");

            assertThat(statusOf(routes.routes(), ManagementRoute.CLUSTER_KEYS_REVOKE, List.of("config:ops"), body))
                .isEqualTo(HttpStatus.CONFLICT);
        } finally {
            routes.stop();
        }
    }

    @Test
    void clusterConfigApply_answers400_whenTomlIsMissing() {
        var routes = ClusterConfigRoutes.clusterConfigRoutes(() -> node(true));
        var body = new ManagementApiResponses.ApplyConfigRequest(null, 0L);

        assertThat(statusOf(routes.routes(), ManagementRoute.CLUSTER_CONFIG_APPLY, body)).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void abTestCreate_answers409_whenNodeIsNotLeader() {
        var routes = AbTestRoutes.abTestRoutes(() -> node(false));
        var body = new AbTestRoutes.AbTestCreateRequest("org.example:app", Map.of("a", "1.0.0"), null, null);

        assertThat(statusOf(routes.routes(), ManagementRoute.AB_TEST_CREATE, body)).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void abTestConclude_answers409_whenNodeIsNotLeader() {
        var routes = AbTestRoutes.abTestRoutes(() -> node(false));
        var body = new AbTestRoutes.AbTestConcludeRequest("a");

        assertThat(statusOf(routes.routes(), ManagementRoute.AB_TEST_CONCLUDE, List.of("test-1"), body)).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void abTestCreate_answers400_whenArtifactBaseIsMissing() {
        var routes = AbTestRoutes.abTestRoutes(() -> node(true));
        var body = new AbTestRoutes.AbTestCreateRequest(null, Map.of("a", "1.0.0"), null, null);

        assertThat(statusOf(routes.routes(), ManagementRoute.AB_TEST_CREATE, body)).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void abTestCreate_answers400_whenVariantsAreMissing() {
        var routes = AbTestRoutes.abTestRoutes(() -> node(true));
        var body = new AbTestRoutes.AbTestCreateRequest("org.example:app", null, null, null);

        assertThat(statusOf(routes.routes(), ManagementRoute.AB_TEST_CREATE, body)).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void abTestCreate_answers400_whenAVariantVersionIsMalformed() {
        var routes = AbTestRoutes.abTestRoutes(() -> node(true));
        var body = new AbTestRoutes.AbTestCreateRequest("org.example:app", Map.of("a", "not-a-version"), null, null);

        assertThat(statusOf(routes.routes(), ManagementRoute.AB_TEST_CREATE, body)).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private static HttpStatus statusOf(java.util.stream.Stream<Route<?>> routes, ManagementRoute which, Object body) {
        return statusOf(routes, which, List.of(), body);
    }

    private static HttpStatus statusOf(java.util.stream.Stream<Route<?>> routes,
                                       ManagementRoute which,
                                       List<String> pathParams,
                                       Object body) {
        var route = routes.filter(candidate -> candidate.name().equals(which.name())).findFirst().orElseThrow();
        var holder = new AtomicReference<Cause>();

        route.handler()
             .handle(new StubRequestContext(pathParams, body, INSTANCE))
             .await()
             .onSuccess(value -> org.junit.jupiter.api.Assertions.fail("Route " + which.name() + " must fail, got: " + value))
             .onFailure(holder::set);

        var recorder = new RecordingResponseWriter();

        ProblemResponses.writeProblem(recorder, holder.get(), INSTANCE, REQUEST_ID);

        return recorder.status();
    }

    /// Only what the leader check reads; any other call means the handler got past validation.
    private static ManageableNode node(boolean leader) {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, _) -> switch (method.getName()) {
                                                           case "isLeader" -> leader;
                                                           case "leader" -> Option.none();
                                                           case "kvStore" -> emptyKvStore();
                                                           default -> throw new UnsupportedOperationException(
                                                               "Reached past request validation: " + method.getName());
                                                       });
    }

    @SuppressWarnings("unchecked")
    private static KVStore<AetherKey, AetherValue> emptyKvStore() {
        var store = (KVStore<AetherKey, AetherValue>) mock(KVStore.class);

        when(store.get(any())).thenReturn(Option.none());

        return store;
    }

    private record StubRequestContext(List<String> pathParams, Object requestBody, String path) implements RequestContext {
        @Override
        public QueryParams queryParams() {
            return QueryParams.queryParams(Map.of());
        }

        @Override
        public Route<?> route() {
            throw new UnsupportedOperationException("route");
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Result<T> fromJson(TypeToken<T> literal) {
            return Result.success((T) requestBody);
        }

        @Override
        public HttpHeaders responseHeaders() {
            throw new UnsupportedOperationException("responseHeaders");
        }

        @Override
        public String requestId() {
            return REQUEST_ID;
        }

        @Override
        public HttpMethod method() {
            throw new UnsupportedOperationException("method");
        }

        @Override
        public Headers headers() {
            throw new UnsupportedOperationException("headers");
        }

        @Override
        public byte[] body() {
            throw new UnsupportedOperationException("body");
        }
    }

    private static final class RecordingResponseWriter implements ResponseWriter {
        private final AtomicReference<HttpStatus> status = new AtomicReference<>();

        @Override
        public void write(HttpStatus status, byte[] body, ContentType contentType) {
            this.status.set(status);
        }

        @Override
        public ResponseWriter header(String name, String value) {
            return this;
        }

        HttpStatus status() {
            return status.get();
        }
    }
}
