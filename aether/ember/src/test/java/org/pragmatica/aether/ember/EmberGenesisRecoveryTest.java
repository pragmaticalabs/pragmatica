// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #1554 — which recovery actions clear a stuck genesis on REAL transport, where #1545's boot tokens apply.
///
/// The stuck state: three configured cores, one of which (`-3`) never started, so `-1` and `-2` wait in
/// genesis. `GenesisRecoveryActionsTest` shows at the view layer that a running pending core never forgets a
/// core it saw; this class adds what the boot tokens add:
///
/// - (a) STOP every pending core, THEN start all of them → genesis forms. No running peer holds an old token.
/// - (b) restart the pending cores ONE AT A TIME while the others keep running → the restarted process is
///   refused by the live peer that recorded its old token and exits. It never becomes a live member.
///   Observed, not asserted: with Ember's fixed `cluster.genesis_voters`, the surviving pending core still
///   holds the dead process's earlier view reports, so once the missing core starts it forms epoch 0 with the
///   refused identity as a (dead) member — a live majority of two, and a seat that needs a §4 swap.
/// - (c) start the cores under FRESH identities after stopping the old ones → genesis forms.
///
/// [unverified: worker/governor peers] A worker or governor that connected to the pending cores also records
/// their tokens, so it must be stopped too before the cores start; Ember admits workers only into a formed
/// cluster, so this class cannot put one next to pending cores, and the worker half of the procedure is
/// stated from the #1545 mechanism, not measured here.
class EmberGenesisRecoveryTest {
    private static final int CLUSTER_SIZE = 3;
    private static final int SLOTS = 2 * CLUSTER_SIZE;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_HTTP_OFFSET = 80;
    /// Disjoint from every other Ember/Forge test's port range (highest below is 43900, next above 45100).
    private static final int FIRST_CANDIDATE_BASE = 44100;
    private static final int LAST_CANDIDATE_BASE = 44900;
    private static final int CANDIDATE_STEP = 200;
    /// #1667: probed through the shared EmberTestPorts, which also probes each node's SWIM UDP port.
    private static final EmberTestPorts.Block PORTS = new EmberTestPorts.Block(FIRST_CANDIDATE_BASE,
                                                                                LAST_CANDIDATE_BASE,
                                                                                CANDIDATE_STEP,
                                                                                SLOTS,
                                                                                MGMT_OFFSET,
                                                                                APP_HTTP_OFFSET);
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final long PENDING_SETTLE_MS = 5_000L;
    private static final long CONDITION_BOUND_MS = 60_000L;
    /// Long enough for a relaunch retry or a late dial to reach the survivor.
    private static final long REFUSAL_WINDOW_MS = 20_000L;

    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.stop().await(STOP_BOUND);
        }
    }

    @Test
    @Timeout(300)
    void stopEveryPendingCore_thenStartThemAll_formsGenesis() {
        var base = EmberTestPorts.freeBase(PORTS);

        stuckPending(base, "gra");
        stopCluster(base);
        cluster = emberCluster(CLUSTER_SIZE, base, base + MGMT_OFFSET, base + APP_HTTP_OFFSET, "gra");
        assertThat(cluster.start().await(START_BOUND).fold(Cause::message, _ -> "started")).isEqualTo("started");

        awaitCondition("every core installs the genesis roster", this::allFormed);
    }

    @Test
    @Timeout(300)
    void restartingOnePendingCoreWhileAnotherRuns_isRefusedAndExits() {
        var base = EmberTestPorts.freeBase(PORTS);

        stuckPending(base, "grb");
        assertThat(cluster.killNode("grb-1", false).await(STOP_BOUND).isSuccess()).isTrue();
        var relaunch = cluster.relaunchNode("grb-1", false);

        awaitCondition("the restarted process is refused by the live peer that holds its old token, and exits",
                       () -> cluster.getNode("grb-1").isEmpty());
        assertThat(relaunch.await(START_BOUND).fold(Cause::message, _ -> "launched")).isNotEqualTo("launched");

        cluster.startHeldBackNodes().await(START_BOUND);
        var survivor = node("grb-2");
        var deadline = System.currentTimeMillis() + REFUSAL_WINDOW_MS;

        while (System.currentTimeMillis() < deadline) {
            assertThat(cluster.getNode("grb-1").isEmpty()).as("the refused identity never runs again").isTrue();
            assertThat(EmberAmnesiacRestartTest.runtime(survivor).network().connectedPeers())
                .as("no live process under the refused identity reaches the survivor")
                .doesNotContain(NodeId.nodeId("grb-1").unwrap());
            sleep(500);
        }
    }

    @Test
    @Timeout(300)
    void stopEveryPendingCore_thenStartUnderFreshIdentities_formsGenesis() {
        var base = EmberTestPorts.freeBase(PORTS);

        stuckPending(base, "grc");
        stopCluster(base);
        cluster = emberCluster(CLUSTER_SIZE, base, base + MGMT_OFFSET, base + APP_HTTP_OFFSET, "grf");
        assertThat(cluster.start().await(START_BOUND).fold(Cause::message, _ -> "started")).isEqualTo("started");

        awaitCondition("the fresh identities form genesis", this::allFormed);
    }

    /// Cores `-1` and `-2` started, `-3` held back: both wait in genesis for the fixed roster.
    private void stuckPending(int base, String prefix) {
        cluster = emberCluster(CLUSTER_SIZE, base, base + MGMT_OFFSET, base + APP_HTTP_OFFSET, prefix);
        var _ = cluster.startWithLateGenesisMembers(Set.of(prefix + "-3"));

        awaitCondition("the two started cores run", () -> cluster.getNode(prefix + "-1").isPresent()
                                                          && cluster.getNode(prefix + "-2").isPresent());
        sleep(PENDING_SETTLE_MS);
        assertThat(List.of(node(prefix + "-1"), node(prefix + "-2")))
            .as("both started cores are still genesis-pending")
            .allMatch(core -> EmberAmnesiacRestartTest.runtime(core).voterConfiguration().isEmpty());
    }

    private void stopCluster(int base) {
        cluster.stop().await(STOP_BOUND);
        cluster = null;
        awaitCondition("the stopped cluster's ports are free", () -> EmberTestPorts.isFree(PORTS, base));
    }

    private boolean allFormed() {
        return cluster.allNodes().size() == CLUSTER_SIZE
               && cluster.allNodes()
                         .stream()
                         .allMatch(core -> EmberAmnesiacRestartTest.runtime(core)
                                                                   .voterConfiguration()
                                                                   .filter(configuration -> configuration.members()
                                                                                                         .size() == CLUSTER_SIZE)
                                                                   .isPresent());
    }

    private AetherNode node(String id) {
        return cluster.getNode(id).unwrap();
    }

    private static void awaitCondition(String what, BooleanSupplier condition) {
        var deadline = System.currentTimeMillis() + CONDITION_BOUND_MS;

        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting: " + what);
            }
            sleep(250);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
