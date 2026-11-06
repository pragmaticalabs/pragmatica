// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.utility.react.ReactExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// Wiring pin for [AetherNode#stop]: the leader-toggled control-plane components deactivate through
/// `router.route(ClusterStateNotification.passive())` and must have done so before `router.quiesce()`. The
/// passive notification must be delivered while the router still routes (`LeaderManager` turns it into
/// `QuorumDisappeared`, then `QuorumLost`, then `LeaderChange(none, false)`, which every
/// `toggle*OnLeaderChange` route receives); once the router is quiesced it silently drops everything, so
/// a quiesce that does not wait for that delivery leaves the components active on a stopped node.
///
/// Real nodes, real QUIC and Rabia: a single node cannot elect (the election is skipped on an empty
/// transport view), so the leader of a formed three-node cluster is stopped on its own and read after
/// `stop()` has returned. The control reading before the stop proves the observed components are active on
/// the leader, so an inactive reading afterwards is the deactivation and not a component that never
/// started. Only the components `AetherNode` exposes are read; the other leader-toggled components share
/// the same `LeaderChange` route and are not observable from here.
///
/// #2688: `LeaderChange` is routed onto the `LeaderManager` react executor (D1-react), so the toggles run on that thread
/// after `route(passive)` has returned. `stop()` must therefore not quiesce the router, nor resolve, before that reaction
/// has run. The first test reads the outcome and was flaky under load (the reaction sometimes lost the race);
/// the second forces the interleaving: the executor is held by a latch until `stop()` has returned its promise, so the
/// reaction runs strictly after the synchronous part of `stop()` - red at the pre-fix tip (the quiesced router drops it,
/// `deploymentManager` and `abTestManager` stay active), green when the quiesce and the promise wait for it; it also reads the
/// promise `stop()` returned as still unresolved while the reaction is held. The third holds the executor past the stop's
/// bound on that wait (`LEADER_DEACTIVATION_BOUND`): `stop()` must resolve anyway.
///
/// Mutation record (both red, three runs each): swapping `router.route(passive)` and `router.quiesce()` in
/// `AetherNode.stop`, and making `DelegateRouter.route` asynchronous, each leave `deploymentManager` and
/// `abTestManager` active after `stop()`.
@PortBudget
class EmberStopDeactivatesLeaderTogglesTest {
    private static final int CLUSTER_SIZE = 3;
    private static final int SLOTS = 2 * CLUSTER_SIZE;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_HTTP_OFFSET = 80;
    private static final EmberTestPorts.Block PORTS = new EmberTestPorts.Block(EmberTestPorts.POOL_FIRST,
                                                                                EmberTestPorts.POOL_LAST,
                                                                                EmberTestPorts.POOL_STEP,
                                                                                SLOTS,
                                                                                MGMT_OFFSET,
                                                                                APP_HTTP_OFFSET);
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    /// The deactivation bound plus the rest of the stop (backup flush 10 s, sealer 5 s, run beside each other, and the stop
    /// sequence); well under STOP_BOUND so a stop that does not give up at the bound still has headroom to show as a failure.
    private static final TimeSpan HELD_STOP_BOUND = TimeSpan.timeSpan(AetherNode.LEADER_DEACTIVATION_BOUND.millis() + 25_000L).millis();
    private static final long HELD_MS = 300L;
    private static final long POLL_MS = 250L;
    private static final long LEADER_BOUND_MS = 90_000L;
    private static final long ACTIVE_BOUND_MS = 60_000L;

    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.stop().await(STOP_BOUND);
        }
    }

    @Test
    @Timeout(420)
    void stop_deactivatesLeaderToggledComponents_onceStopHasReturned() {
        cluster = EmberTestPorts.startedCluster(PORTS,
                                                basePort -> emberCluster(CLUSTER_SIZE,
                                                                         basePort,
                                                                         basePort + MGMT_OFFSET,
                                                                         basePort + APP_HTTP_OFFSET,
                                                                         "stopdeact"),
                                                START_BOUND);
        var leader = awaitLeaderNode();

        awaitActive(leader);
        assertThat(states(leader)).as("control: every pinned component is active on the elected leader")
                                  .allSatisfy((name, active) -> assertThat(active).as(name).isTrue());
        assertThat(leader.stop().await(STOP_BOUND).isSuccess()).as("the leader stops").isTrue();

        assertThat(states(leader)).as("leader-toggled components after stop() returned")
                                  .allSatisfy((name, active) -> assertThat(active).as(name).isFalse());
    }

    @Test
    @Timeout(420)
    void stop_deactivatesLeaderToggledComponents_whenTheLeaderChangeReactionRunsAfterStopHasReturnedItsPromise()
    throws InterruptedException {
        cluster = EmberTestPorts.startedCluster(PORTS,
                                                basePort -> emberCluster(CLUSTER_SIZE,
                                                                         basePort,
                                                                         basePort + MGMT_OFFSET,
                                                                         basePort + APP_HTTP_OFFSET,
                                                                         "stopdeact2"),
                                                START_BOUND);
        var leader = awaitLeaderNode();

        awaitActive(leader);
        assertThat(states(leader)).as("control: every pinned component is active on the elected leader")
                                  .allSatisfy((name, active) -> assertThat(active).as(name).isTrue());

        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        leaderManagerExecutor(leader).undo("hold", () -> hold(held, release));
        assertThat(held.await(30, TimeUnit.SECONDS)).as("the LeaderManager executor is held").isTrue();

        var stopped = leader.stop();

        Thread.sleep(HELD_MS);
        assertThat(stopped.isResolved()).as("stop() has not resolved while the LeaderChange reaction is held").isFalse();
        release.countDown();
        assertThat(stopped.await(STOP_BOUND).isSuccess()).as("the leader stops").isTrue();
        assertThat(states(leader)).as("leader-toggled components once stop() resolved, reaction held past its return")
                                  .allSatisfy((name, active) -> assertThat(active).as(name).isFalse());
    }

    /// F2 (#2688): a wedged `LeaderManager` reaction cannot hold the stop. The executor is held for the whole stop; `stop()`
    /// resolves anyway, after the bound on the deactivation's delivery.
    @Test
    @Timeout(420)
    void stop_resolves_whenTheLeaderManagerExecutorIsHeldPastTheBound() throws InterruptedException {
        cluster = EmberTestPorts.startedCluster(PORTS,
                                                basePort -> emberCluster(CLUSTER_SIZE,
                                                                         basePort,
                                                                         basePort + MGMT_OFFSET,
                                                                         basePort + APP_HTTP_OFFSET,
                                                                         "stopdeact3"),
                                                START_BOUND);
        var leader = awaitLeaderNode();

        awaitActive(leader);

        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        leaderManagerExecutor(leader).undo("hold", () -> hold(held, release));
        assertThat(held.await(30, TimeUnit.SECONDS)).as("the LeaderManager executor is held").isTrue();
        try {
            var stopped = leader.stop();

            assertThat(stopped.await(HELD_STOP_BOUND).isSuccess()).as("the leader stops with its LeaderManager executor held").isTrue();
        } finally {
            release.countDown();
        }
    }

    private static ReactExecutor leaderManagerExecutor(AetherNode node) {
        return node.reactExecutors()
                   .stream()
                   .filter(executor -> executor.component().equals("LeaderManager"))
                   .findFirst()
                   .orElseThrow(() -> new AssertionError("no LeaderManager react executor"));
    }

    private static void hold(CountDownLatch held, CountDownLatch release) {
        held.countDown();
        try {
            release.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private AetherNode awaitLeaderNode() {
        var deadline = System.currentTimeMillis() + LEADER_BOUND_MS;

        while (System.currentTimeMillis() < deadline) {
            var leader = cluster.allNodes().stream().filter(AetherNode::isLeader).findFirst();

            if (leader.isPresent()) {
                return leader.get();
            }
            sleep();
        }
        throw new AssertionError("no node claims leadership within " + LEADER_BOUND_MS + " ms");
    }

    private static void awaitActive(AetherNode leader) {
        var deadline = System.currentTimeMillis() + ACTIVE_BOUND_MS;

        while (System.currentTimeMillis() < deadline && !states(leader).values().stream().allMatch(active -> active)) {
            sleep();
        }
    }

    private static Map<String, Boolean> states(AetherNode node) {
        var states = new LinkedHashMap<String, Boolean>();

        states.put("controlLoop", node.controlLoop().isActive());
        states.put("deploymentManager", node.deploymentManager().isActive());
        states.put("abTestManager", node.abTestManager().isActive());
        states.put("rollbackManager", node.rollbackManager().isActive());
        return states;
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }
}
