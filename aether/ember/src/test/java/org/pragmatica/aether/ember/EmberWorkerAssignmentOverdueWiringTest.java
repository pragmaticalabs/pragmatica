// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.pragmatica.aether.api.ClusterEvent;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #1840 — a worker holds its SWIM ANNOUNCE until its activation directive commits, and a hold that outlasts the
/// community-absence window is reported, through the REAL wiring of a node: the window comes from the node's
/// `timeouts.cluster.communityAbsence`, the warning reaches the node's own event aggregator through the sink the
/// assembly binds to the SWIM detector, and the directive's arrival releases the hold and raises the recovery.
///
/// Three cores start with the defaults; the window is then shortened to 100 ms for the worker alone, which is
/// created afterwards. A worker is normally assigned between 0.3 s and 1.3 s after it boots, so the shortened
/// window is overdue first, and the directive's arrival closes it.
@PortBudget
class EmberWorkerAssignmentOverdueWiringTest {
    private static final int CORES = 3;
    private static final int SLOTS = 2 * CORES + 1;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_HTTP_OFFSET = 80;
    private static final EmberTestPorts.Block PORTS = new EmberTestPorts.Block(EmberTestPorts.POOL_FIRST,
                                                                               EmberTestPorts.POOL_LAST,
                                                                               EmberTestPorts.POOL_STEP,
                                                                               SLOTS,
                                                                               MGMT_OFFSET,
                                                                               APP_HTTP_OFFSET);
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(180).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.stop().await(STOP_BOUND);
        }
    }

    @Test
    @Timeout(420)
    void heldWorker_pastTheCommunityAbsenceWindow_raisesTheWarning_andTheDirectiveRaisesTheRecovery() {
        cluster = EmberTestPorts.startedCluster(PORTS,
                                                basePort -> emberCluster(CORES,
                                                                         basePort,
                                                                         basePort + MGMT_OFFSET,
                                                                         basePort + APP_HTTP_OFFSET,
                                                                         "wao"),
                                                START_BOUND);
        awaitCondition(() -> "leader", 90_000L, () -> cluster.getLeaderManagementPort().isPresent());
        cluster.withCommunityAbsenceForNewNodes(TimeSpan.timeSpan(100).millis());
        var workerId = cluster.addWorkerNode().await(START_BOUND).unwrap().id();
        var worker = cluster.getNode(workerId).unwrap();

        awaitCondition(() -> "worker-assignment-overdue for " + workerId + "; events: " + codesSeenBy(worker),
                       60_000L,
                       () -> count(worker, "worker-assignment-overdue", workerId) >= 1);
        awaitCondition(() -> "worker-assigned for " + workerId + "; events: " + codesSeenBy(worker),
                       60_000L,
                       () -> count(worker, "worker-assigned", workerId) >= 1);
        assertThat(count(worker, "worker-assignment-overdue", workerId)).as("one warning per hold").isEqualTo(1);
        assertThat(count(worker, "worker-assigned", workerId)).as("one recovery per warning").isEqualTo(1);
    }

    private static long count(AetherNode node, String code, String subject) {
        return events(node).stream()
                           .filter(e -> e instanceof ClusterEvent.OperatorWarning && code.equals(e.details().get("code"))
                                        && subject.equals(e.details().get("subject")))
                           .count();
    }

    private static List<String> codesSeenBy(AetherNode node) {
        return events(node).stream().map(e -> e.getClass().getSimpleName() + "/" + e.details().get("code")).toList();
    }

    private static List<ClusterEvent> events(AetherNode node) {
        return node.eventAggregator().events().await().or(List.of());
    }

    private static void awaitCondition(Supplier<String> what, long budgetMs, BooleanSupplier condition) {
        var deadline = System.currentTimeMillis() + budgetMs;

        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleepQuietly(250L);
        }
        throw new AssertionError("timed out: " + what.get());
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
