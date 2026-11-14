// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.controller.ControlLoop;
import org.pragmatica.aether.controller.fsm.ControlLoopContext;
import org.pragmatica.aether.metrics.ExecutionOutcomeKeys;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.aether.slice.kvstore.AetherValue.Generation;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPong;
import org.pragmatica.cluster.metrics.MetricObservation;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// F5b (#3113): the two AetherNode call sites that hand the FSM2 observed-row readers the node's admission seam
/// (`deploymentWiring.admissions()`), pinned on a real 3-node FSM2 cluster.
///
/// A single node cannot pin them: its boot-token registry holds peers only and its own token reads 0, so every token
/// a single-node test can ask about is 0 and a fresh seam (every token 0) is indistinguishable from the real one. Here
/// a peer is admitted through the transport, so the leader's seam reads a non-zero token for it (asserted as the
/// arming step), and a row written under a DIFFERENT non-zero token is stale (R-1.6.2) for the real seam and current
/// for a fresh one.
///
/// - `stalePeerRow_isIgnoredByTheControlLoop`: a stale LOADING row, then a current one on another artifact; once the
///   current one registers and is removed, no slice state may remain. Kills the control-loop put route handed a fresh seam.
/// - `stalePeerRow_isNotAHostForTheAllInstancesFailedDetector`: two ACTIVE rows of the same peer, one current and one
///   stale, and defect pongs under the peer's real incarnation for both artifacts. The current artifact raises the alert
///   (the control: injection reaches the detector), the stale artifact must not. Kills the detector handed a fresh seam.
///
/// Rows are written to the leader's own store: the readers under test are the leader's local notification routes.
@PortBudget
class EmberObservedSourceBootTokenTest {
    private static final int CORES = 3;
    private static final int SLOTS = 2 * CORES;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_OFFSET = 80;
    private static final EmberTestPorts.Block PORTS = new EmberTestPorts.Block(EmberTestPorts.POOL_FIRST,
                                                                                EmberTestPorts.POOL_LAST,
                                                                                EmberTestPorts.POOL_STEP,
                                                                                SLOTS,
                                                                                MGMT_OFFSET,
                                                                                APP_OFFSET);
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final long WAIT_BOUND_MS = 60_000L;
    private static final long NEGATIVE_WINDOW_MS = 8_000L;
    private static final long POLL_MS = 200L;
    private static final long SEQUENCE_LEAP = 1_000_000L;
    private static final String METHOD = "run";
    private static final Artifact CURRENT = Artifact.artifact("org.example:tok-current:1.0.0").unwrap();
    private static final Artifact STALE = Artifact.artifact("org.example:tok-stale:1.0.0").unwrap();

    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.stop().await(STOP_BOUND);
        }
    }

    private static EmberCluster fsm2Cluster(int base) {
        var built = emberCluster(CORES, base, base + MGMT_OFFSET, base + APP_OFFSET, "tok");

        built.withDeploymentEngine(DeploymentEngine.FSM2);
        return built;
    }

    @Test
    @Timeout(600)
    void stalePeerRow_isIgnoredByTheControlLoop() {
        var leader = startedLeader();
        var peer = peerOf(leader);
        var token = admittedToken(leader, peer);
        var loop = leader.controlLoop();

        assertThat(slicesInProgress(loop)).as("arming: nothing in progress before the rows").isFalse();
        put(leader, peer, STALE, SliceObservedState.LOADING, token + 1);
        put(leader, peer, CURRENT, SliceObservedState.LOADING, token);
        awaitCondition("the current row reaches the loop", () -> slicesInProgress(loop));
        remove(leader, peer, CURRENT, SliceObservedState.LOADING, token);

        awaitCondition("the current row's removal clears the loop; a recorded stale row would keep it in progress",
                       () -> !slicesInProgress(loop),
                       () -> "in progress: " + slicesInProgress(loop));
    }

    @Test
    @Timeout(600)
    void stalePeerRow_isNotAHostForTheAllInstancesFailedDetector() {
        var leader = startedLeader();
        var peer = peerOf(leader);
        var token = admittedToken(leader, peer);
        awaitCondition("the leader holds an observation of the peer",
                                  () -> leader.metricsCollector().allObservations().containsKey(peer),
                                  () -> leader.metricsCollector().allObservations().keySet().toString());

        put(leader, peer, CURRENT, SliceObservedState.ACTIVE, token);
        put(leader, peer, STALE, SliceObservedState.ACTIVE, token + 1);
        var observation = leader.metricsCollector().allObservations().get(peer);
        var incarnation = observation.incarnation();
        var sequence = observation.sequence() + SEQUENCE_LEAP;

        pong(leader, peer, incarnation, sequence, 0);
        var defects = 0;
        var deadline = System.currentTimeMillis() + WAIT_BOUND_MS;

        while (!alerted(leader, CURRENT)) {
            assertThat(System.currentTimeMillis()).as("control: the current artifact never raised the alert").isLessThan(deadline);
            sleep(1_000L);
            defects += 3;
            pong(leader, peer, incarnation, ++sequence, defects);
        }

        var until = System.currentTimeMillis() + NEGATIVE_WINDOW_MS;

        while (System.currentTimeMillis() < until) {
            sleep(1_000L);
            defects += 3;
            pong(leader, peer, incarnation, ++sequence, defects);
        }

        assertThat(alerted(leader, STALE)).as("the stale row must not make the peer a host of %s (alerts: %s)",
                                              STALE,
                                              leader.alertManager().getActiveSliceFailureAlerts()).isFalse();
    }

    private AetherNode startedLeader() {
        cluster = EmberTestPorts.startedCluster(PORTS, EmberObservedSourceBootTokenTest::fsm2Cluster, START_BOUND);
        awaitCondition("a leader is elected", () -> cluster.currentLeader().flatMap(cluster::getNode).filter(AetherNode::isLeader).isPresent());
        return cluster.currentLeader().flatMap(cluster::getNode).unwrap();
    }

    private NodeId peerOf(AetherNode leader) {
        return cluster.allNodes()
                      .stream()
                      .map(AetherNode::self)
                      .filter(id -> !id.equals(leader.self()))
                      .findFirst()
                      .orElseThrow();
    }

    /// The leader's own admitted token for the peer; non-zero or the test cannot tell a fresh seam from the real one.
    private static long admittedToken(AetherNode leader, NodeId peer) {
        awaitCondition("arming: the leader's seam holds a non-zero boot token for " + peer.id(),
                       () -> leader.deploymentWiring().admissions().tokenOf(peer) != 0L);
        return leader.deploymentWiring().admissions().tokenOf(peer);
    }

    private static void put(AetherNode node, NodeId peer, Artifact artifact, SliceObservedState state, long token) {
        var store = node.kvStore();

        store.process(store.createBatch(List.of(new KVCommand.Put<AetherKey, AetherValue>(new SliceObservedKey(peer, artifact),
                                                                                         row(state, token)))));
    }

    private static void remove(AetherNode node, NodeId peer, Artifact artifact, SliceObservedState state, long token) {
        var store = node.kvStore();

        store.process(store.createBatch(List.of(new KVCommand.Remove<AetherKey>(new SliceObservedKey(peer, artifact),
                                                                               Option.some(row(state, token))))));
    }

    private static SliceObservedValue row(SliceObservedState state, long token) {
        return new SliceObservedValue(state,
                                      1L,
                                      new Generation(1L, 1L),
                                      new ProcessIncarnation(1L, token),
                                      Option.none(),
                                      false,
                                      0,
                                      List.of(),
                                      0L);
    }

    /// A pong in the peer's real metric incarnation, ahead of its real sequence so the peer's own pongs no longer replace it.
    private static void pong(AetherNode leader, NodeId peer, long incarnation, long sequence, int defects) {
        var values = Map.of(ExecutionOutcomeKeys.defectKey(CURRENT, METHOD), (double) defects,
                            ExecutionOutcomeKeys.defectKey(STALE, METHOD), (double) defects);

        leader.metricsCollector()
              .onClusterSyncPong(new ClusterSyncPong(peer,
                                                     new MetricObservation(incarnation, sequence, System.currentTimeMillis(), values),
                                                     1L,
                                                     0L,
                                                     0L,
                                                     0L,
                                                     0L,
                                                     "ACTIVE",
                                                     List.of(),
                                                     List.of(),
                                                     List.of(),
                                                     Option.none()));
    }

    private static boolean alerted(AetherNode leader, Artifact artifact) {
        return leader.alertManager().getActiveSliceFailureAlerts().stream().anyMatch(alert -> alert.artifact().equals(artifact));
    }

    /// The loop's slice-state map is not on its interface; its adapter record exposes the context, which is where the routes deliver.
    private static boolean slicesInProgress(ControlLoop loop) {
        try {
            var accessor = loop.getClass().getDeclaredMethod("ctx");

            accessor.setAccessible(true);
            return ((ControlLoopContext) accessor.invoke(loop)).anySliceInProgress();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("the control loop adapter no longer exposes ctx()", e);
        }
    }

    private static void awaitCondition(String what, BooleanSupplier condition) {
        awaitCondition(what, condition, () -> "");
    }

    private static void awaitCondition(String what, BooleanSupplier condition, Supplier<String> state) {
        var deadline = System.currentTimeMillis() + WAIT_BOUND_MS;

        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError(what + " - not reached in " + WAIT_BOUND_MS + " ms; " + state.get());
            }
            sleep(POLL_MS);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting", e);
        }
    }
}
