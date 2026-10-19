// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// Wiring pin for [AetherNode#stop]: the leader-toggled control-plane components deactivate only through
/// the synchronous chain `router.route(ClusterStateNotification.passive())` then `router.quiesce()`. The
/// passive notification must be delivered while the router still routes (`LeaderManager` turns it into
/// `QuorumDisappeared`, then `QuorumLost`, then `LeaderChange(none, false)`, which every
/// `toggle*OnLeaderChange` route receives); once the router is quiesced it silently drops everything, so
/// the same two calls in the other order, or a `route` that hands the message to another thread, leave the
/// components active on a stopped node.
///
/// Real nodes, real QUIC and Rabia: a single node cannot elect (the election is skipped on an empty
/// transport view), so the leader of a formed three-node cluster is stopped on its own and read after
/// `stop()` has returned. The control reading before the stop proves the observed components are active on
/// the leader, so an inactive reading afterwards is the deactivation and not a component that never
/// started. Only the components `AetherNode` exposes are read; the other leader-toggled components share
/// the same `LeaderChange` route and are not observable from here. `ttmManager` is not pinned either: TTM is
/// disabled in this fixture, so it is never active and cannot discriminate.
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
