// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.lang.reflect.Proxy;

import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.Unit.unit;
import static org.assertj.core.api.Assertions.assertThat;

/// #2734 wiring pin: [EmberCluster#startHeldBackNodes] must reach the caller only AFTER the started node is in the running
/// registry, so `getNode` right after it answers the node. The registration was an `onSuccess` side effect, which a promise
/// runs on another thread after its dependent steps: the outcome resolved first, and
/// `EmberColdRestartAckedLossTripwireTest.runLateReturn` failed on `cluster.getNode(ownerId).unwrap()` (CI run 42070110928, #2734).
///
/// Deterministic, not a loop, in the #1112 shape (`EmberClusterTeardownWiringTest`): the registry entry is HELD by a
/// `computeIfAbsent` in flight on the same key, a bin lock the registration must take. Under the fixed chain the outcome
/// stays unresolved while the lock is held; under the old chain it resolved at once with the registration still blocked.
class EmberClusterHeldBackRegistrationTest {
    private static final String HELD_ID = "held-1";
    private static final TimeSpan HELD = TimeSpan.timeSpan(300).millis();
    private static final TimeSpan SETTLED = TimeSpan.timeSpan(10).seconds();
    private static final int BASE_PORT = EmberTestPorts.POOL_FIRST;
    private static final int BASE_MGMT_PORT = EmberTestPorts.POOL_FIRST + 100;

    @Test
    @Timeout(30)
    void startHeldBackNodes_doesNotReachTheCaller_untilTheStartedNodeIsRegistered() {
        var cluster = emberCluster(1, BASE_PORT, BASE_MGMT_PORT, "held");
        var start = Promise.<Unit> promise();

        cluster.adoptHeldBackNode(HELD_ID, fakeNode(nodeId(HELD_ID).unwrap(), start));

        var entered = Promise.<Unit> promise();
        var release = Promise.<Unit> promise();
        var released = Promise.<Unit> promise();

        Thread.ofPlatform().start(() -> cluster.nodeRegistry()
                                               .computeIfAbsent(HELD_ID, _ -> hold(entered, release, released)));
        entered.await();

        var outcome = cluster.startHeldBackNodes();

        Thread.startVirtualThread(() -> start.succeed(unit()));
        var whileHeld = outcome.await(HELD);
        var resolvedWhileHeld = outcome.isResolved();

        release.succeed(unit());
        released.await();

        assertThat(resolvedWhileHeld).as("startHeldBackNodes must not reach the caller while the node's registration is still blocked; saw %s",
                                         whileHeld)
                                     .isFalse();
        assertThat(outcome.await(SETTLED).isSuccess()).as("the held-back start completes once the registry is free").isTrue();
        assertThat(cluster.getNode(HELD_ID).isPresent()).as("the started held-back node is registered when the caller sees the outcome")
                                                        .isTrue();
        assertThat(cluster.heldBackNode(HELD_ID).isPresent()).as("and is no longer held back").isFalse();
    }

    /// Runs INSIDE the registry's bin lock for the held id and maps nothing: the lock is what matters.
    private static AetherNode hold(Promise<Unit> entered, Promise<Unit> release, Promise<Unit> released) {
        entered.succeed(unit());
        release.await();
        released.succeed(unit());

        return null;
    }

    /// Answers `start()` with the test's own promise; anything else the path touches answers, the rest throws.
    private static AetherNode fakeNode(NodeId id, Promise<Unit> start) {
        return (AetherNode) Proxy.newProxyInstance(AetherNode.class.getClassLoader(),
                                                   new Class<?>[]{AetherNode.class},
                                                   (proxy, method, args) -> switch (method.getName()) {
            case "start" -> start;
            case "stop" -> Promise.success(unit());
            case "self" -> id;
            case "isReady" -> false;
            case "leader" -> Option.empty();
            case "isLeader" -> false;
            case "toString" -> "fake(" + id.id() + ")";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException("fake AetherNode does not answer " + method.getName());
        });
    }
}
