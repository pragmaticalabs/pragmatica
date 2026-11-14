// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.lang.reflect.Proxy;
import java.util.function.Supplier;

import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;
import static org.assertj.core.api.Assertions.assertThat;

/// #3173/#3174 part B. A node whose stop does not resolve inside the per-node bound is still running with its QUIC port bound.
/// The cluster stop must not report success then (the next cluster's bind fails), and a self-exiting node must not be
/// reported as exited before its stop has resolved. The stops here are fakes the test resolves, or never resolves, itself.
class EmberClusterStopBoundTest {
    private static final TimeSpan PAST_THE_BOUND = TimeSpan.timeSpan(15).seconds();
    private static final TimeSpan BRIEFLY = TimeSpan.timeSpan(300).millis();
    private static final int PORT = EmberTestPorts.POOL_FIRST;

    @Test
    @Timeout(40)
    void clusterStop_failsNamingTheNode_whenANodeStopNeverResolves() {
        var cluster = emberCluster(2, PORT, PORT + 100, "sb");

        adopt(cluster, "sb-1", PORT, () -> Promise.success(unit()));
        adopt(cluster, "sb-2", PORT + 1, Promise::<Unit> promise);

        var outcome = cluster.stop().await(PAST_THE_BOUND);

        assertThat(outcome.isFailure()).as("a node whose stop expired its bound must fail the cluster stop, got %s", outcome).isTrue();
        outcome.onFailure(cause -> assertThat(cause.message()).contains("sb-2").doesNotContain("sb-1")
                                                                      .as("the failure names the bound that expired")
                                                                      .contains(TimeSpan.timeSpan(10).seconds().toString()));
        assertThat(cluster.allNodes()).as("the registry is still cleared").isEmpty();
    }

    @Test
    @Timeout(40)
    void clusterStop_succeeds_whenEveryNodeStopResolves() {
        var cluster = emberCluster(2, PORT, PORT + 100, "sb");

        adopt(cluster, "sb-1", PORT, () -> Promise.success(unit()));
        adopt(cluster, "sb-2", PORT + 1, () -> Promise.success(unit()));

        assertThat(cluster.stop().await(PAST_THE_BOUND).isSuccess()).isTrue();
    }

    @Test
    @Timeout(40)
    void selfExit_isReportedOnlyAfterTheStopResolved() {
        var cluster = emberCluster(1, PORT, PORT + 100, "sb");
        var stop = Promise.<Unit> promise();

        adopt(cluster, "sb-1", PORT, () -> stop);
        cluster.onNodeExit("sb-1", AetherNode.EXIT_IDENTITY_REFUSED);

        assertThat(cluster.exitCodeOf("sb-1")).as("the stop has not resolved: not exited yet").isEqualTo(Option.none());
        stop.succeed(unit());
        awaitExitCode(cluster, "sb-1");
        assertThat(cluster.exitCodeOf("sb-1")).isEqualTo(Option.some(AetherNode.EXIT_IDENTITY_REFUSED));
    }

    @Test
    @Timeout(40)
    void selfExit_isNotReported_whenTheStopNeverResolves() {
        var cluster = emberCluster(1, PORT, PORT + 100, "sb");

        adopt(cluster, "sb-1", PORT, Promise::<Unit> promise);
        cluster.onNodeExit("sb-1", AetherNode.EXIT_IDENTITY_REFUSED);
        Promise.<Unit> promise().await(PAST_THE_BOUND);

        assertThat(cluster.exitCodeOf("sb-1")).as("the stop bound expired; the node may still hold its ports").isEqualTo(Option.none());
    }

    /// A node already out of the registry is being stopped by another path (`killNode`); its exit is recorded at once.
    @Test
    @Timeout(40)
    void selfExit_ofANodeAlreadyOutOfTheRegistry_isReportedAtOnce() {
        var cluster = emberCluster(1, PORT, PORT + 100, "sb");

        cluster.onNodeExit("sb-1", AetherNode.EXIT_DRAINED);

        assertThat(cluster.exitCodeOf("sb-1")).isEqualTo(Option.some(AetherNode.EXIT_DRAINED));
    }

    /// F1: a node removed by a self-drain with its stop still in flight is not in the registry, but still holds its ports.
    @Test
    @Timeout(40)
    void clusterStop_failsNamingTheNode_whenARemovedNodesStopIsStillInFlight() {
        var cluster = emberCluster(2, PORT, PORT + 100, "sb");

        adopt(cluster, "sb-1", PORT, () -> Promise.success(unit()));
        adopt(cluster, "sb-2", PORT + 1, Promise::<Unit> promise);
        cluster.onNodeExit("sb-2", AetherNode.EXIT_DRAINED);

        var outcome = cluster.stop().await(PAST_THE_BOUND);

        assertThat(outcome.isFailure()).as("a removed node still stopping must fail the cluster stop, got %s", outcome).isTrue();
        outcome.onFailure(cause -> assertThat(cause.message()).contains("sb-2").doesNotContain("sb-1"));
    }

    /// F2 (documented contract): a failed stop clears the registry, so a retry does not re-stop the leaked node, but it
    /// awaits that node's pending stop and fails naming it again instead of reporting success.
    @Test
    @Timeout(40)
    void retriedClusterStop_stillFailsNamingTheLeakedNode_whileItsStopIsPending() {
        var cluster = emberCluster(1, PORT, PORT + 100, "sb");

        adopt(cluster, "sb-1", PORT, Promise::<Unit> promise);

        assertThat(cluster.stop().await(PAST_THE_BOUND).isFailure()).isTrue();
        var retry = cluster.stop().await(PAST_THE_BOUND);

        assertThat(retry.isFailure()).as("the leaked node's stop is still pending, got %s", retry).isTrue();
        retry.onFailure(cause -> assertThat(cause.message()).contains("sb-1"));
    }

    /// F4: a second exit report for a node whose first self-drain stop is pending waits for that stop too.
    @Test
    @Timeout(40)
    void secondSelfExit_whileTheStopIsPending_isNotReportedUntilItResolves() {
        var cluster = emberCluster(1, PORT, PORT + 100, "sb");
        var stop = Promise.<Unit> promise();

        adopt(cluster, "sb-1", PORT, () -> stop);
        cluster.onNodeExit("sb-1", AetherNode.EXIT_DRAINED);
        cluster.onNodeExit("sb-1", AetherNode.EXIT_DRAINED);

        assertThat(cluster.exitCodeOf("sb-1")).as("the stop is in flight: not exited yet").isEqualTo(Option.none());
        stop.succeed(unit());
        awaitExitCode(cluster, "sb-1");
        assertThat(cluster.exitCodeOf("sb-1")).isEqualTo(Option.some(AetherNode.EXIT_DRAINED));
    }

    /// A stop that expires its bound and resolves later still reports the exit, once it has.
    @Test
    @Timeout(40)
    void selfExit_isReported_whenTheStopResolvesAfterItsBound() {
        var cluster = emberCluster(1, PORT, PORT + 100, "sb");
        var stop = Promise.<Unit> promise();

        adopt(cluster, "sb-1", PORT, () -> stop);
        cluster.onNodeExit("sb-1", AetherNode.EXIT_IDENTITY_REFUSED);
        Promise.<Unit> promise().await(PAST_THE_BOUND);
        assertThat(cluster.exitCodeOf("sb-1")).as("still stopping past the bound").isEqualTo(Option.none());

        stop.succeed(unit());
        awaitExitCode(cluster, "sb-1");

        assertThat(cluster.exitCodeOf("sb-1")).isEqualTo(Option.some(AetherNode.EXIT_IDENTITY_REFUSED));
    }

    /// The bound inside stopRemoved is what lets killNode return, and killNode swallows the expiry (behaviour kept from before
    /// #3173): without the bound killNode would hang, without the recover it would fail.
    @Test
    @Timeout(40)
    void killNode_succeeds_whenTheStopNeverResolves() {
        var cluster = emberCluster(1, PORT, PORT + 100, "sb");

        adopt(cluster, "sb-1", PORT, Promise::<Unit> promise);

        assertThat(cluster.killNode("sb-1", false).await(TimeSpan.timeSpan(5).seconds()).isSuccess()).isTrue();
    }

    @Test
    @Timeout(40)
    void selfExit_whoseStopExpires_logsAWarningNamingTheNode() {
        var cluster = emberCluster(1, PORT, PORT + 100, "sb");
        var capture = StreamTripwireSupport.captureLogs();

        try {
            adopt(cluster, "sb-1", PORT, Promise::<Unit> promise);
            cluster.onNodeExit("sb-1", AetherNode.EXIT_DRAINED);
            Promise.<Unit> promise().await(PAST_THE_BOUND);

            assertThat(StreamTripwireSupport.logLines(capture, Level.WARN)).anyMatch(line -> line.contains("Self-drain stop of sb-1 did not resolve"));
        } finally {
            capture.close();
        }
    }

    private static void awaitExitCode(EmberCluster cluster, String id) {
        var deadline = System.nanoTime() + 5_000_000_000L;

        while (cluster.exitCodeOf(id).isEmpty() && System.nanoTime() < deadline) {
            Promise.<Unit> promise().await(BRIEFLY);
        }
    }

    private static void adopt(EmberCluster cluster, String id, int port, Supplier<Promise<Unit>> stop) {
        var nodeId = nodeId(id).unwrap();

        cluster.adoptNode(NodeInfo.nodeInfo(nodeId, nodeAddress("localhost", port).unwrap()), fakeNode(nodeId, stop));
    }

    private static AetherNode fakeNode(NodeId id, Supplier<Promise<Unit>> stop) {
        return (AetherNode) Proxy.newProxyInstance(AetherNode.class.getClassLoader(),
                                                   new Class<?>[]{AetherNode.class},
                                                   (proxy, method, args) -> switch (method.getName()) {
            case "stop" -> stop.get();
            case "self" -> id;
            case "isReady", "isLeader" -> false;
            case "leader" -> Option.empty();
            case "toString" -> "fake(" + id.id() + ")";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException("fake AetherNode does not answer " + method.getName());
        });
    }
}
