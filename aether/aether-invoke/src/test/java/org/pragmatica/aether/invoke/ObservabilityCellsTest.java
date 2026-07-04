// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.endpoint.EndpointRegistry;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.ObservabilityCellRegistrar;
import org.pragmatica.aether.slice.ObservabilityStrategyCell;
import org.pragmatica.aether.slice.ObservabilityStrategyCell.InvocationStrategy;
import org.pragmatica.aether.slice.SliceBridge;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/// #277 increment 2, east-west/topic/timer seam. Proves the cell attaches at the invoker's dispatch
/// sites: `ObservabilityCells.around` resolves the per-injection-point cell from the bridge and wraps the
/// interceptor unit (single fire, decorates when swapped, passthrough when absent); InvocationHandler
/// registers a bridge's cells at load and drops them at unload; and a real same-node `invokeLocal` fires
/// the cell exactly once (no double-wrap).
class ObservabilityCellsTest {
    private static final Artifact ARTIFACT = Artifact.artifact("com.example:my-slice:1.0.0").unwrap();
    private static final MethodName METHOD = MethodName.methodName("echo").unwrap();

    private final AtomicInteger fires = new AtomicInteger();

    // A counting "around" strategy: named method (not a multi-statement lambda) so a swap is observable.
    private Promise<?> counting(Fn0<Promise<?>> proceed) {
        fires.incrementAndGet();

        return proceed.apply();
    }

    private ObservabilityStrategyCell countingCell() {
        var cell = ObservabilityStrategyCell.observabilityStrategyCell("com.example:my-slice", "echo");

        cell.swap(this::counting);

        return cell;
    }

    @Nested
    class WrapHelper {
        @Test
        void around_passesThrough_whenBridgeHasNoCell() {
            var bridge = new StubBridge(Map.of());
            var inner = Promise.success("payload");

            var returned = ObservabilityCells.around(bridge, "echo", () -> inner);

            assertThat(returned).isSameAs(inner);
        }

        @Test
        void around_firesTheCellOnce_andDecorates_whenCellSwapped() {
            var cell = ObservabilityStrategyCell.observabilityStrategyCell("com.example:my-slice", "echo");
            cell.swap(decorating());
            var bridge = new StubBridge(Map.of("echo", cell));

            ObservabilityCells.around(bridge, "echo", () -> Promise.success("payload"))
                              .await()
                              .onFailure(cause -> Assertions.fail(cause.message()))
                              .onSuccess(value -> assertThat(value).isEqualTo("decorated:payload"));
        }

        @Test
        void around_firesTheCellExactlyOnce_perCall() {
            var bridge = new StubBridge(Map.of("echo", countingCell()));

            ObservabilityCells.around(bridge, "echo", () -> Promise.success("payload"))
                              .await();

            assertThat(fires.get()).isEqualTo(1);
        }
    }

    @Nested
    class Lifecycle {
        @Test
        void registerSlice_registersEveryBridgeCell_andUnregisterSlice_deregistersThem() {
            var cellA = ObservabilityStrategyCell.observabilityStrategyCell("com.example:my-slice", "a");
            var cellB = ObservabilityStrategyCell.observabilityStrategyCell("com.example:my-slice", "b");
            var bridge = new StubBridge(Map.of("a", cellA, "b", cellB));
            var registrar = new RecordingRegistrar();
            var handler = InvocationHandler.invocationHandler(new NodeId("self"), new StubClusterNetwork());

            handler.setObservabilityCellRegistrar(registrar);
            handler.registerSlice(ARTIFACT, bridge);

            assertThat(registrar.registered).containsExactlyInAnyOrder(cellA, cellB);
            assertThat(registrar.deregistered).isEmpty();

            handler.unregisterSlice(ARTIFACT);

            assertThat(registrar.deregistered).containsExactlyInAnyOrder(cellA, cellB);
        }
    }

    @Nested
    class NoDoubleFire {
        @Test
        void invokeLocal_firesTheCellExactlyOnce_forASameNodeCall() {
            var self = new NodeId("self");
            var network = new StubClusterNetwork();
            var handler = InvocationHandler.invocationHandler(self, network);
            var bridge = new StubBridge(Map.of("echo", countingCell()));

            handler.registerSlice(ARTIFACT, bridge);
            var invoker = SliceInvoker.sliceInvoker(self,
                                                    network,
                                                    EndpointRegistry.endpointRegistry(),
                                                    handler,
                                                    new StubSerializer(),
                                                    new StubDeserializer(),
                                                    new StubDeploymentManager(),
                                                    ObservabilityInterceptor.noOp());

            invoker.invokeLocal(ARTIFACT, METHOD, "hi", new TypeToken<Object>() {})
                   .await()
                   .onFailure(cause -> Assertions.fail(cause.message()))
                   .onSuccess(value -> assertThat(value).isEqualTo("result"));

            assertThat(fires.get()).isEqualTo(1);
        }
    }

    private static InvocationStrategy decorating() {
        return proceed -> proceed.apply().map(value -> "decorated:" + value);
    }

    // Configurable bridge: maps method name -> cell, with canned encode/invoke/decode so a local invoke
    // round-trips to a fixed "result" without a real codec.
    private record StubBridge(Map<String, ObservabilityStrategyCell> cells) implements SliceBridge {
        @Override
        public Option<ObservabilityStrategyCell> observabilityCell(String methodName) {
            return Option.option(cells.get(methodName));
        }

        @Override
        public List<ObservabilityStrategyCell> observabilityCells() {
            return List.copyOf(cells.values());
        }

        @Override
        public Promise<byte[]> encode(Object input) {
            return Promise.success(new byte[0]);
        }

        @Override
        public Promise<Object> decode(byte[] bytes) {
            return Promise.success("result");
        }

        @Override
        public Promise<byte[]> invoke(String methodName, byte[] input) {
            return Promise.success(new byte[0]);
        }

        @Override
        public Promise<Unit> start() {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.unitPromise();
        }

        @Override
        public ClassLoader classLoader() {
            return StubBridge.class.getClassLoader();
        }

        @Override
        public List<String> methodNames() {
            return List.copyOf(cells.keySet());
        }
    }

    private static final class RecordingRegistrar implements ObservabilityCellRegistrar {
        private final List<ObservabilityStrategyCell> registered = new ArrayList<>();
        private final List<ObservabilityStrategyCell> deregistered = new ArrayList<>();

        @Override
        public Unit register(ObservabilityStrategyCell cell) {
            registered.add(cell);

            return Unit.unit();
        }

        @Override
        public Unit deregister(ObservabilityStrategyCell cell) {
            deregistered.add(cell);

            return Unit.unit();
        }
    }
}
