// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.stream.consumer.ConsumerGroupCoordinator;
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

import io.netty.handler.codec.http.HttpHeaders;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


/// #3794: a malformed version (`1.x.0`, whose parse fails with the `NumberFormatException` of the digit parse) on every
/// management route that parses a caller-supplied coordinate answers 400 and a body that carries no trace of the throwable.
/// The real route handler, then the exact `ProblemResponses.writeProblem` call the router makes.
class ManagementMalformedVersionNoTraceTest {
    private static final String REQUEST_ID = "req-1";
    private static final String INSTANCE = "/api/v1/management";
    private static final String MALFORMED = "org.example:app:1.x.0";

    @Test
    void deployStart_malformedBlueprintVersion_isABadRequestWithNoTrace() {
        var routes = DeployRoutes.deployRoutes(() -> node(false));
        var body = new DeployRoutes.DeployRequest(MALFORMED, "rolling", null, null, null, null, null, null);

        assertCleanBadRequest(routes.routes(), ManagementRoute.DEPLOY_START, List.of(), body);
    }

    @Test
    void blueprintGet_malformedVersion_isABadRequestWithNoTrace() {
        assertCleanBadRequest(SliceRoutes.sliceRoutes(() -> node(false)).routes(), ManagementRoute.BLUEPRINT_GET, List.of(MALFORMED), null);
    }

    @Test
    void blueprintStatus_malformedVersion_isABadRequestWithNoTrace() {
        assertCleanBadRequest(SliceRoutes.sliceRoutes(() -> node(false)).routes(), ManagementRoute.BLUEPRINT_STATUS, List.of(MALFORMED), null);
    }

    @Test
    void blueprintDelete_malformedVersion_isABadRequestWithNoTrace() {
        assertCleanBadRequest(SliceRoutes.sliceRoutes(() -> node(false)).routes(), ManagementRoute.BLUEPRINT_DELETE, List.of(MALFORMED), null);
    }

    @Test
    void sliceConfig_malformedVersion_isABadRequestWithNoTrace() {
        assertCleanBadRequest(SliceRoutes.sliceRoutes(() -> node(false)).routes(), ManagementRoute.SLICE_CONFIG, List.of(MALFORMED), null);
    }

    @Test
    void sliceScale_malformedVersion_isABadRequestWithNoTrace() {
        var body = new SliceRoutes.ScaleRequest(MALFORMED, 3, null);

        assertCleanBadRequest(SliceRoutes.sliceRoutes(() -> node(true)).routes(), ManagementRoute.SLICE_SCALE, List.of(), body);
    }

    @Test
    void abTestCreate_malformedVariantVersion_isABadRequestWithNoTrace() {
        var body = new AbTestRoutes.AbTestCreateRequest("org.example:app", Map.of("a", "1.x.0"), null, null);

        assertCleanBadRequest(AbTestRoutes.abTestRoutes(() -> node(true)).routes(), ManagementRoute.AB_TEST_CREATE, List.of(), body);
    }

    @Test
    void abTestCreate_malformedArtifactBase_isABadRequestWithNoTrace() {
        var body = new AbTestRoutes.AbTestCreateRequest("1.x.0:::", Map.of("a", "1.0.0"), null, null);

        assertCleanBadRequest(AbTestRoutes.abTestRoutes(() -> node(true)).routes(), ManagementRoute.AB_TEST_CREATE, List.of(), body);
    }

    @Test
    void streamsMetadata_malformedVersion_isABadRequestWithNoTrace() {
        var routes = StreamApiRoutes.streamApiRoutes(() -> node(false), null, ConsumerGroupCoordinator.noOp(), null);

        assertCleanBadRequest(routes.routes(), ManagementRoute.STREAMS_METADATA, List.of("ns", "orders", "1.x.0"), null);
    }

    @Test
    void topicsGroups_malformedVersion_isABadRequestWithNoTrace() {
        assertCleanBadRequest(TopicRoutes.topicRoutes(() -> node(false)).routes(), ManagementRoute.TOPICS_GROUPS, List.of("ns", "events", "1.x.0", "groups"), null);
    }

    private static void assertCleanBadRequest(Stream<Route<?>> routes, ManagementRoute which, List<String> pathParams, Object requestBody) {
        var route = routes.filter(candidate -> candidate.name().equals(which.name())).findFirst().orElseThrow();
        var failure = new AtomicReference<Cause>();

        route.handler()
             .handle(new StubRequestContext(pathParams, requestBody))
             .await()
             .onSuccess(value -> org.junit.jupiter.api.Assertions.fail("Route " + which.name() + " must fail, got: " + value))
             .onFailure(failure::set);

        var status = new AtomicReference<HttpStatus>();
        var text = new AtomicReference<String>();

        ProblemResponses.writeProblem(new ResponseWriter() {
            @Override
            public void write(HttpStatus responseStatus, byte[] bytes, ContentType contentType) {
                status.set(responseStatus);
                text.set(new String(bytes, StandardCharsets.UTF_8));
            }

            @Override
            public ResponseWriter header(String name, String value) {
                return this;
            }
        }, failure.get(), INSTANCE, REQUEST_ID);

        assertThat(status.get()).as(which.name()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(text.get()).as(which.name())
                              .doesNotContain("NumberFormatException")
                              .doesNotContain("\\tat ")
                              .doesNotContain("\tat ")
                              .doesNotContain("at java");
    }

    /// Answers only what the leader check and the validation read; any other call means the handler got past the refusal under test.
    private static ManageableNode node(boolean leader) {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, _) -> switch (method.getName()) {
                                                           case "isLeader" -> leader;
                                                           case "leader" -> Option.none();
                                                           case "kvStore" -> emptyKvStore();
                                                           default -> throw new UnsupportedOperationException("Reached past the refusal under test: " + method.getName());
                                                       });
    }

    @SuppressWarnings("unchecked")
    private static KVStore<AetherKey, AetherValue> emptyKvStore() {
        var store = (KVStore<AetherKey, AetherValue>) mock(KVStore.class);

        when(store.get(any())).thenReturn(Option.none());

        return store;
    }

    private record StubRequestContext(List<String> pathParams, Object requestBody) implements RequestContext {
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

        @Override
        public String path() {
            return INSTANCE;
        }
    }
}
