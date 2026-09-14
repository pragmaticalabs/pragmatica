// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;
import static org.assertj.core.api.Assertions.assertThat;


/// #1112 wiring pin: each of the three teardown paths — [EmberCluster#abortStart],
/// [EmberCluster#handleStartResults], [EmberCluster#stop] — must reach the caller only AFTER the
/// registry clear has returned (#913 contract). `EmberClusterClearBeforeOutcomeTest` pins the shared
/// helper; a call site rewired back to the old `onSuccess` chain left every ember test green, so
/// this class drives the REAL paths over fake nodes whose `stop()` it resolves itself.
///
/// Deterministic, not a loop: the registry's `clear()` is HELD by a `computeIfPresent` in flight on
/// the same map (a bin lock the clear must take), and the pin is that the outcome is still
/// unresolved while the clear is held. The old chain dispatched the clear to another thread and
/// resolved the outcome at once, so under it the caller sees the failure while the clear is blocked
/// — red every time, not 0.2–0.7 % of the time.
class EmberClusterTeardownWiringTest {
    private static final Cause START_FAILED = Causes.cause("start failed");
    private static final TimeSpan HELD = TimeSpan.timeSpan(300).millis();
    private static final TimeSpan SETTLED = TimeSpan.timeSpan(10).seconds();
    private static final int BASE_PORT = 45_100;
    private static final int BASE_MGMT_PORT = 45_200;

    @Test
    @Timeout(30)
    void abortStart_doesNotReachTheCaller_untilTheClearHasReturned() {
        var harness = Harness.seeded(3);
        var outcome = harness.cluster().abortStart(START_FAILED,
                                                   Map.of("wire-1", START_FAILED.message()));

        harness.assertOutcomeWaitsForTheClear(outcome, List.of(0, 1, 2));
        assertThat(outcome.await(SETTLED)).isEqualTo(START_FAILED.result());
        harness.assertRegistryEmpty();
    }

    @Test
    @Timeout(30)
    void handleStartResults_doesNotReachTheCaller_untilTheClearHasReturned() {
        var harness = Harness.seeded(3);
        var results = List.of(success(started("wire-1", 0)),
                              success(started("wire-2", 1)),
                              success(EmberCluster.NodeStartResult.nodeStartResult("wire-3",
                                                                                   BASE_PORT + 2,
                                                                                   BASE_MGMT_PORT + 2,
                                                                                   some(START_FAILED))));
        var outcome = harness.cluster().handleStartResults(results,
                                                           Map.of("wire-3", START_FAILED.message()));
        // Only the nodes that STARTED are stopped; the failed one's stop is never invoked.
        harness.assertOutcomeWaitsForTheClear(outcome, List.of(0, 1));
        assertThat(outcome.await(SETTLED)).isEqualTo(START_FAILED.result());
        harness.assertRegistryEmpty();
    }

    @Test
    @Timeout(30)
    void stop_doesNotReachTheCaller_untilTheClearHasReturned() {
        var harness = Harness.seeded(3);
        var outcome = harness.cluster().stop();

        harness.assertOutcomeWaitsForTheClear(outcome, List.of(0, 1, 2));
        assertThat(outcome.await(SETTLED)).isEqualTo(success(unit()));
        harness.assertRegistryEmpty();
    }

    private static EmberCluster.NodeStartResult started(String id, int slot) {
        return EmberCluster.NodeStartResult.nodeStartResult(id, BASE_PORT + slot, BASE_MGMT_PORT + slot, none());
    }

    private record Harness(EmberCluster cluster, List<Promise<Unit>> stops) {
        static Harness seeded(int size) {
            var cluster = emberCluster(size, BASE_PORT, BASE_MGMT_PORT, "wire");
            var stops = new ArrayList<Promise<Unit>>();

            for (int i = 1; i <= size; i++) {
                var id = nodeId("wire-" + i).unwrap();
                var stop = Promise.<Unit> promise();

                cluster.adoptNode(NodeInfo.nodeInfo(id, nodeAddress("localhost", BASE_PORT + i - 1).unwrap()),
                                  fakeNode(id, stop));
                stops.add(stop);
            }

            return new Harness(cluster, stops);
        }

        /// Holds the registry clear, resolves the given stops from another thread (as a node's stop
        /// callback does), and demands that `outcome` is still unresolved while the clear is held.
        void assertOutcomeWaitsForTheClear(Promise<Unit> outcome, List<Integer> stopsToResolve) {
            var entered = Promise.<Unit> promise();
            var release = Promise.<Unit> promise();
            var released = Promise.<Unit> promise();

            Thread.ofPlatform().start(() -> cluster.nodeRegistry()
                                                   .computeIfPresent("wire-1",
                                                                     (_, node) -> hold(node, entered, release, released)));
            entered.await();
            assertThat(cluster.allNodes()).as("seeded registry before the teardown").hasSize(stops.size());
            Thread.startVirtualThread(() -> stopsToResolve.forEach(i -> stops.get(i)
                                                                             .succeed(unit())));
            var whileHeld = outcome.await(HELD);
            var resolvedWhileHeld = outcome.isResolved();

            release.succeed(unit());
            released.await();
            assertThat(resolvedWhileHeld).as("the outcome must not reach the caller while the registry clear is still running; saw %s",
                                             whileHeld)
                      .isFalse();
        }

        void assertRegistryEmpty() {
            assertThat(cluster.allNodes()).isEmpty();
            assertThat(cluster.status().nodes()).isEmpty();
        }

        /// Runs INSIDE the registry's bin lock: the entry is left as it was, the lock is what matters.
        private static AetherNode hold(AetherNode node,
                                       Promise<Unit> entered,
                                       Promise<Unit> release,
                                       Promise<Unit> released) {
            entered.succeed(unit());
            release.await();
            released.succeed(unit());

            return node;
        }
    }

    /// Everything the three real paths touch on a node — `stop()`, and what `status()` reads for the
    /// #727 snapshot — answers; anything else throws, so an unexpected access fails loudly.
    private static AetherNode fakeNode(NodeId id, Promise<Unit> stop) {
        return (AetherNode) Proxy.newProxyInstance(AetherNode.class.getClassLoader(),
                                                   new Class<?>[]{AetherNode.class},
                                                   (proxy, method, args) -> switch (method.getName()) {
            case "stop" -> stop;
            case "self" -> id;
            case "isReady" -> false;
            case "leader" -> Option.empty();
            case "toString" -> "fake(" + id.id() + ")";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException("fake AetherNode does not answer " + method.getName());
        });
    }
}
