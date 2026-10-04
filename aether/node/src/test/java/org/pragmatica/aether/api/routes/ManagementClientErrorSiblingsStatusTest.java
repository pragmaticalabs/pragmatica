// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.api.ManagementApiResponses;
import org.pragmatica.aether.deployment.cluster.BlueprintService;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.TopologyEntry;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.aether.stream.consumer.ConsumerGroupCoordinator;
import org.pragmatica.aether.update.AbTestManager;
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

import io.netty.handler.codec.http.HttpHeaders;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


/// #954, the siblings of [ManagementPostClientErrorStatusTest]: management routes whose own client-side refusals
/// were bare `Causes.cause(...)` values and so answered `500`. Same method: the REAL route handler, then the exact
/// `ProblemResponses.writeProblem` call the router makes. A missing resource is 404, a state the cluster refuses
/// is 409, a request the caller got wrong is 400.
class ManagementClientErrorSiblingsStatusTest {
    private static final String REQUEST_ID = "req-1";
    private static final String INSTANCE = "/api/v1/management";
    private static final String COORDS = "org.example:app:1.0.0";

    @Test
    void sliceConfig_answers404_whenTheSliceIsNotLoaded() {
        var store = mock(SliceStore.class);

        when(store.sliceComposite(org.mockito.ArgumentMatchers.any())).thenReturn(Option.none());

        var routes = SliceRoutes.sliceRoutes(() -> node(Map.of("sliceStore", store)));

        assertThat(statusOf(routes.routes(), ManagementRoute.SLICE_CONFIG, List.of(COORDS), null, Map.of()))
            .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void blueprintGet_answers404_whenTheBlueprintIsUnknown() {
        var routes = SliceRoutes.sliceRoutes(() -> node(Map.of("blueprintService", emptyBlueprintService())));

        assertThat(statusOf(routes.routes(), ManagementRoute.BLUEPRINT_GET, List.of("org.example:bp:1.0.0"), null, Map.of()))
            .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void sliceScale_answers409_whenTheSliceIsInNoActiveBlueprint() {
        var routes = SliceRoutes.sliceRoutes(() -> node(Map.of("blueprintService", emptyBlueprintService())));
        var body = new SliceRoutes.ScaleRequest(COORDS, 3, null);

        assertThat(statusOf(routes.routes(), ManagementRoute.SLICE_SCALE, List.of(), body, Map.of()))
            .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void abTestGet_answers404_whenTheTestIsUnknown() {
        var manager = mock(AbTestManager.class);

        when(manager.getTest(org.mockito.ArgumentMatchers.any())).thenReturn(Option.none());

        var routes = AbTestRoutes.abTestRoutes(() -> node(Map.of("abTestManager", manager)));

        assertThat(statusOf(routes.routes(), ManagementRoute.AB_TEST_GET, List.of("no-such-test"), null, Map.of()))
            .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void consumerGroupJoin_answers400_whenStreamNameIsMissing() {
        var routes = StreamRoutes.streamRoutes(() -> node(Map.of()), ConsumerGroupCoordinator.noOp(), null);
        var body = new StreamRoutes.JoinGroupRequest("g1", null, 4, "c1");

        assertThat(statusOf(routes.routes(), ManagementRoute.CONSUMER_GROUP_JOIN, List.of(), body, Map.of()))
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void consumerGroupLeave_answers400_whenStreamNameIsMissing() {
        var routes = StreamRoutes.streamRoutes(() -> node(Map.of()), ConsumerGroupCoordinator.noOp(), null);
        var body = new StreamRoutes.LeaveGroupRequest("g1", null, "c1");

        assertThat(statusOf(routes.routes(), ManagementRoute.CONSUMER_GROUP_LEAVE, List.of(), body, Map.of()))
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void clusterJournal_answers400_whenLayerIsUnknown() {
        var routes = ClusterJournalRoutes.clusterJournalRoutes(() -> node(Map.of()));

        assertThat(statusOf(routes.routes(), ManagementRoute.CLUSTER_JOURNAL, List.of(), null, Map.of("layer", List.of("bogus"))))
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void awaitQuiesced_answers400_whenEpochIsMissing() {
        var routes = ClusterAwaitQuiescedRoute.clusterAwaitQuiescedRoute(() -> node(Map.of()));

        assertThat(statusOf(routes.routes(), ManagementRoute.CLUSTER_AWAIT_QUIESCED, List.of(), null, Map.of()))
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void awaitQuiesced_answers400_whenEpochIsMalformed() {
        var routes = ClusterAwaitQuiescedRoute.clusterAwaitQuiescedRoute(() -> node(Map.of()));

        assertThat(statusOf(routes.routes(), ManagementRoute.CLUSTER_AWAIT_QUIESCED, List.of(), null, Map.of("epoch", List.of("not-an-epoch"))))
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void awaitQuiesced_answers400_whenTimeoutIsMalformed() {
        var routes = ClusterAwaitQuiescedRoute.clusterAwaitQuiescedRoute(() -> node(Map.of()));
        var query = Map.of("epoch", List.of("1:2:3"), "timeout", List.of("soon"));

        assertThat(statusOf(routes.routes(), ManagementRoute.CLUSTER_AWAIT_QUIESCED, List.of(), null, query))
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /// A topology manager that is not (yet) on this node is transient and server-side: 503, not a bare 500.
    @Test
    void clusterCircuitBreakerStatus_answers503_whenTheTopologyManagerIsNotOnThisNode() {
        var routes = ClusterTopologyRoutes.clusterTopologyRoutes(() -> node(Map.of("clusterTopologyManager", Option.none())));

        assertThat(statusOf(routes.routes(), ManagementRoute.CLUSTER_CIRCUIT_BREAKER_STATUS, List.of(), null, Map.of()))
            .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    /// The tail subscription is a deferred feature (#212): 501, with the polling alternative in the message.
    @Test
    void streamsTail_answers501_becauseTheSubscriptionIsDeferred() {
        var routes = StreamApiRoutes.streamApiRoutes(() -> node(Map.of()), null, ConsumerGroupCoordinator.noOp(), null);

        assertThat(statusOf(routes.routes(), ManagementRoute.STREAMS_TAIL, List.of("ns", "orders", "1.0.0", "tail"), null, Map.of()))
            .isEqualTo(HttpStatus.NOT_IMPLEMENTED);
    }

    /// The commit of a cluster config needs a committed core leader; without one it is a state the cluster refuses
    /// (409), not a server fault.
    @Test
    void clusterConfigApply_answers409_whenNoCoreLeaderIsCommitted() {
        @SuppressWarnings("unchecked")
        var store = (KVStore<AetherKey, AetherValue>) mock(KVStore.class);
        var seed = ClusterConfigValue.bootstrapSeed("prod", "1.0.0", List.of(new TopologyEntry("hetzner", "core", 3)), 3, 9, "hetzner", 1);

        when(store.get(org.mockito.ArgumentMatchers.<AetherKey> any())).thenReturn(Option.some(seed));
        when(store.getTyped(org.mockito.ArgumentMatchers.<AetherKey> any(), org.mockito.ArgumentMatchers.<Class<AetherValue>> any()))
            .thenReturn(Option.none());

        var routes = ClusterConfigRoutes.clusterConfigRoutes(() -> node(Map.of("kvStore", store, "isLeader", true)));
        var body = new ManagementApiResponses.ApplyConfigRequest(OPERATOR_TOML, 0L);

        assertThat(statusOf(routes.routes(), ManagementRoute.CLUSTER_CONFIG_APPLY, List.of(), body, Map.of()))
            .isEqualTo(HttpStatus.CONFLICT);
    }

    private static final String OPERATOR_TOML = """
        config_version = "1.0.0"

        [cluster]
        name = "prod"
        version = "1.0.0"

        [runtime.node]
        type = "container"
        image = "ghcr.io/pragmaticalabs/aether-node:1.0.0"

        [source.hetzner]
        type = "cloud"
        provider = "hetzner"
        region = "eu-central"

        [source.hetzner.core]
        count = 3
        runtime = "node"
        """;

    private static BlueprintService emptyBlueprintService() {
        var service = mock(BlueprintService.class);

        when(service.get(org.mockito.ArgumentMatchers.any())).thenReturn(Option.none());
        when(service.list()).thenReturn(List.of());

        return service;
    }

    private static HttpStatus statusOf(java.util.stream.Stream<Route<?>> routes,
                                       ManagementRoute which,
                                       List<String> pathParams,
                                       Object body,
                                       Map<String, List<String>> query) {
        var route = routes.filter(candidate -> candidate.name().equals(which.name())).findFirst().orElseThrow();
        var holder = new AtomicReference<Cause>();

        route.handler()
             .handle(new StubRequestContext(pathParams, body, query, INSTANCE))
             .await()
             .onSuccess(value -> org.junit.jupiter.api.Assertions.fail("Route " + which.name() + " must fail, got: " + value))
             .onFailure(holder::set);

        var recorder = new RecordingResponseWriter();

        ProblemResponses.writeProblem(recorder, holder.get(), INSTANCE, REQUEST_ID);

        return recorder.status();
    }

    /// Answers only what the test names; any other call means the handler got past the refusal under test.
    private static ManageableNode node(Map<String, Object> stubs) {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, _) -> {
                                                           if (stubs.containsKey(method.getName())) {
                                                               return stubs.get(method.getName());
                                                           }
                                                           throw new UnsupportedOperationException(
                                                               "Reached past the refusal under test: " + method.getName());
                                                       });
    }

    private record StubRequestContext(List<String> pathParams,
                                      Object requestBody,
                                      Map<String, List<String>> query,
                                      String path) implements RequestContext {
        @Override
        public QueryParams queryParams() {
            return QueryParams.queryParams(query);
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
