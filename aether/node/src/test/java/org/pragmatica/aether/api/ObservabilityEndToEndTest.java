// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.http.adapter.ErrorMapper;
import org.pragmatica.aether.http.adapter.SliceRouter;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.invoke.InvocationHandler;
import org.pragmatica.aether.invoke.InvocationMessage.InvokeRequest;
import org.pragmatica.aether.slice.DefaultSliceBridge;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.ObservabilityStrategyCell;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ObservabilityConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ObservabilityConfigValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.http.CommonContentType;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.routing.Handler;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// #277 increment 3, end-to-end proof: a KV observability-config put flips a live method's behaviour on
/// the NEXT invocation, through the exact production wiring chain — real ObservabilityConfigRegistry
/// bound as the InvocationHandler's cell registrar (as AetherNode binds it), a real DefaultSliceBridge
/// over a toy slice, and config driven through the registry's real KV-notification entry point
/// (onObservabilityConfigPut) rather than the write path. Behaviour is the embryonic counting metrics
/// facet: OFF = identity passthrough (method runs, no count); non-off = counting (method runs AND the
/// registry-visible counter increments once per call).
///
/// East-west/topic/timer seam is driven through InvocationHandler.onInvokeRequest (the real dispatch site
/// that wraps the interceptor unit in ObservabilityCells.around). The north-south seam is driven through
/// a real SliceRouter with the registrar-bound decorator (mint cell, register with the registry, wrap the
/// handler over cell.around) — the same shape HttpRoutePublisher uses; the full HttpRoutePublisher publish
/// path (route source discovery, mount modes, forwarder) is left to increment 5's management triad tests.
class ObservabilityEndToEndTest {
    private static final Artifact ARTIFACT = Artifact.artifact("com.example:my-slice:1.0.0").unwrap();
    private static final String ARTIFACT_BASE = "com.example:my-slice";
    private static final String METHOD = "echo";
    private static final String ROUTE_KEY = "getEcho";
    private static final NodeId SELF = new NodeId("self");

    private final AtomicInteger methodCalls = new AtomicInteger();
    private final SliceCodec codec = FrameworkCodecs.frameworkCodecs();

    private ObservabilityConfigRegistry registry;
    private InvocationHandler handler;
    private DefaultSliceBridge bridge;

    @BeforeEach
    void setUp() {
        registry = ObservabilityConfigRegistry.observabilityConfigRegistry(null, kvStoreStub());
        handler = InvocationHandler.invocationHandler(SELF, Mockito.mock(ClusterNetwork.class));
        // Bind the registrar exactly like AetherNode does: registerSlice now auto-registers the bridge's
        // per-method cells with the live registry.
        handler.setObservabilityCellRegistrar(registry);
        bridge = DefaultSliceBridge.defaultSliceBridge(ARTIFACT, echoSlice(), codec);
        handler.registerSlice(ARTIFACT, bridge);
    }

    @Nested
    class EastWestSeam {
        @Test
        void identityBeforeConfig_runsMethodButDoesNotCount() {
            invokeViaHandler();
            awaitMethodCalls(1);

            assertThat(registry.invocationCount(ARTIFACT_BASE, METHOD)).isEqualTo(Option.some(0L));
        }

        @Test
        void metricsConfigPut_countsOnEachSubsequentInvocation() {
            registry.onObservabilityConfigPut(put(METHOD, false, true, false, false, 0));

            invokeViaHandler();
            awaitMethodCalls(1);
            assertThat(registry.invocationCount(ARTIFACT_BASE, METHOD)).isEqualTo(Option.some(1L));

            invokeViaHandler();
            awaitMethodCalls(2);
            assertThat(registry.invocationCount(ARTIFACT_BASE, METHOD)).isEqualTo(Option.some(2L));
        }

        @Test
        void offConfigPut_freezesCounter_andRestoresIdentity() {
            registry.onObservabilityConfigPut(put(METHOD, false, true, false, false, 0));
            invokeViaHandler();
            invokeViaHandler();
            awaitMethodCalls(2);
            assertThat(registry.invocationCount(ARTIFACT_BASE, METHOD)).isEqualTo(Option.some(2L));

            registry.onObservabilityConfigPut(put(METHOD, false, false, false, false, 0));
            invokeViaHandler();
            invokeViaHandler();
            awaitMethodCalls(4);

            assertThat(registry.invocationCount(ARTIFACT_BASE, METHOD)).isEqualTo(Option.some(2L));
        }

        @Test
        void unregisterSlice_deregistersCells_andLaterPutHasNoDanglingEffect() {
            registry.onObservabilityConfigPut(put(METHOD, false, true, false, false, 0));
            invokeViaHandler();
            awaitMethodCalls(1);
            assertThat(registry.invocationCount(ARTIFACT_BASE, METHOD)).isEqualTo(Option.some(1L));

            handler.unregisterSlice(ARTIFACT);
            assertThat(registry.invocationCount(ARTIFACT_BASE, METHOD)).isEqualTo(Option.<Long>none());

            // A later put updates the config snapshot only: no live cell remains, so nothing is swapped
            // and nothing throws.
            registry.onObservabilityConfigPut(put(METHOD, true, true, true, true, 5));
            assertThat(registry.getConfig(ARTIFACT_BASE, METHOD).logging()).isTrue();
            assertThat(registry.invocationCount(ARTIFACT_BASE, METHOD)).isEqualTo(Option.<Long>none());
        }

        private void invokeViaHandler() {
            var payload = bridge.encode("hi").await().unwrap();
            var request = InvokeRequest.invokeRequest(SELF,
                                                      "corr",
                                                      "req",
                                                      ARTIFACT,
                                                      MethodName.methodName(METHOD).unwrap(),
                                                      payload,
                                                      false);

            handler.onInvokeRequest(request);
        }
    }

    @Nested
    class NorthSouthSeam {
        @Test
        void routeHandler_countsAfterPut_underTheRouteKey() {
            var router = routerWithRegisteredCell();

            handleRoute(router);
            assertThat(registry.invocationCount(ARTIFACT_BASE, ROUTE_KEY)).isEqualTo(Option.some(0L));

            registry.onObservabilityConfigPut(put(ROUTE_KEY, false, true, false, false, 0));
            handleRoute(router);
            handleRoute(router);

            assertThat(registry.invocationCount(ARTIFACT_BASE, ROUTE_KEY)).isEqualTo(Option.some(2L));
        }

        private SliceRouter routerWithRegisteredCell() {
            Route<String> route = Route.route(HttpMethod.GET,
                                             "/echo",
                                             ctx -> Promise.success("echo"),
                                             CommonContentType.APPLICATION_JSON,
                                             List.of(),
                                             ROUTE_KEY);
            RouteSource source = () -> Stream.of(route);

            return SliceRouter.sliceRouter(source, ErrorMapper.defaultMapper(), JsonMapper.defaultJsonMapper())
                              .withInvocationCells(this::decorate);
        }

        private Route<?> decorate(Route<?> route) {
            var cell = ObservabilityStrategyCell.observabilityStrategyCell(ARTIFACT_BASE, route.name());

            registry.register(cell);

            return wrap(cell, route);
        }

        private void handleRoute(SliceRouter router) {
            var request = HttpRequestContext.httpRequestContext("/echo", "GET", Map.of(), Map.of(), "req_test");

            router.handle(request)
                  .await()
                  .unwrap();
        }
    }

    private static <T> Route<T> wrap(ObservabilityStrategyCell cell, Route<T> route) {
        var original = route.handler();
        Handler<T> wrapped = ctx -> cell.around(() -> original.handle(ctx));

        return Route.route(route.method(),
                           route.path(),
                           wrapped,
                           route.contentType(),
                           route.spacers(),
                           route.name(),
                           route.security(),
                           route.version(),
                           route.pathParamCount());
    }

    private Slice echoSlice() {
        return () -> List.of(echoMethod());
    }

    private SliceMethod<?, ?> echoMethod() {
        return new SliceMethod<>(MethodName.methodName(METHOD).unwrap(),
                                 this::echo,
                                 new TypeToken<String>() {},
                                 new TypeToken<String>() {});
    }

    private Promise<String> echo(String value) {
        methodCalls.incrementAndGet();

        return Promise.success("echo:" + value);
    }

    // The strategy counter is incremented synchronously in cell.around; the underlying method runs on the
    // async dispatch executor (Result.async().flatMap in DefaultSliceBridge.invoke), so proving it
    // genuinely executed end-to-end needs a bounded wait rather than a synchronous read.
    private void awaitMethodCalls(int expected) {
        var deadlineNanos = System.nanoTime() + 5_000_000_000L;

        while (methodCalls.get() < expected && System.nanoTime() < deadlineNanos) {
            Thread.onSpinWait();
        }

        assertThat(methodCalls.get()).isEqualTo(expected);
    }

    private static ValuePut<ObservabilityConfigKey, ObservabilityConfigValue> put(String method,
                                                                                  boolean logging,
                                                                                  boolean metrics,
                                                                                  boolean spans,
                                                                                  boolean tracing,
                                                                                  int depth) {
        var key = ObservabilityConfigKey.observabilityConfigKey(ARTIFACT_BASE, method);
        var value = ObservabilityConfigValue.observabilityConfigValue(ARTIFACT_BASE,
                                                                      method,
                                                                      logging,
                                                                      metrics,
                                                                      spans,
                                                                      tracing,
                                                                      depth);

        return new ValuePut<>(new KVCommand.Put<>(key, value), Option.none());
    }

    @SuppressWarnings("unchecked")
    private static KVStore<AetherKey, AetherValue> kvStoreStub() {
        return Mockito.mock(KVStore.class);
    }
}
