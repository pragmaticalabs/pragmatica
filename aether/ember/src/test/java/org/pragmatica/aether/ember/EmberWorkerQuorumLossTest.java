// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.deployment.cluster.NodeReplacementPlanner;
import org.pragmatica.aether.deployment.membership.ntt.QuorumLossSnapshot;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #2543, the consequence. A worker's quorum-loss detector counts against the electorate the worker holds. Before the fix that stayed the
/// genesis one, so once three of the five ORIGINAL cores were gone (two replaced, one crashed) every worker read 2 of 3 and self-drained
/// while four of the five real voters were alive.
///
/// - `doesNotDrain`: replace two original cores, then crash a third; the worker's detector stays at or above its threshold for the whole
///   observation, and the worker is still in the cluster.
/// - `stillFires` is its positive control: crash three cores with NO replacement; the worker's detector goes below its threshold and the
///   worker drains. Without it the first test would pass on a detector that was simply switched off.
@PortBudget
class EmberWorkerQuorumLossTest {
    private static final int CORES = 5;
    private static final int SLOTS = 2 * CORES + 6;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_OFFSET = 80;
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final long DONE_BOUND_MS = 240_000L;
    private static final long ROSTER_BOUND_MS = 30_000L;
    /// The quorum-loss drain threshold is the 15 s split timeout; three times that, sampled every 250 ms, is the window in which a wrongly
    /// counting worker has long drained.
    private static final long OBSERVE_MS = 45_000L;
    private static final String TIMINGS_PROPERTY = NodeReplacementPlanner.Timings.OVERRIDE_PROPERTY;

    private EmberCluster cluster;
    private NodeId workerId;
    private String priorTimings;

    @BeforeEach
    void shortBudgets() {
        priorTimings = System.getProperty(TIMINGS_PROPERTY);
        // provisioning, joining, swapping, canary, canaryWait, draining, retiring (ms): the replacement test's budgets
        System.setProperty(TIMINGS_PROPERTY, "60000,60000,90000,60000,3000,90000,60000");
    }

    @AfterEach
    void tearDown() {
        if (priorTimings == null) {
            System.clearProperty(TIMINGS_PROPERTY);
        } else {
            System.setProperty(TIMINGS_PROPERTY, priorTimings);
        }

        if (cluster != null) {
            cluster.stop().await(STOP_BOUND);
        }
    }

    @Test
    @Timeout(1500)
    void workerQuorumLoss_afterThirdOriginalCoreGone_withReplacements_doesNotDrain() {
        var originals = startWithWorker();
        var worker = onlyWorker();
        var leader = awaitLeader();
        var replaceable = originals.stream().filter(id -> !id.equals(leader.self())).toList();

        replaceAndAwaitWorker(replaceable.get(0), worker);
        replaceAndAwaitWorker(replaceable.get(1), worker);
        assertThat(snapshotOf(worker).armed()).as("arming: the worker's detector has seen a quorum, so it can fire").isTrue();

        var third = replaceable.get(2);

        blackhole(third);
        var below = observeBelowThreshold(worker, OBSERVE_MS);

        assertThat(below).as("no sample of the worker's detector below its threshold while four of five voters live").isEmpty();
        assertThat(snapshotOf(worker).strictMemberCount()).as("the worker counts the live voters it was told of").isGreaterThanOrEqualTo(3);
        assertThat(cluster.getNode(worker.self().id()).isPresent()).as("the worker did not self-drain").isTrue();
    }

    @Test
    @Timeout(900)
    void workerQuorumLoss_afterThreeCoresGone_withoutReplacements_stillFires() {
        var originals = startWithWorker();
        var worker = onlyWorker();

        assertThat(snapshotOf(worker).armed()).as("arming: the worker's detector has seen a quorum, so it can fire").isTrue();
        originals.stream().limit(3).forEach(this::blackhole);
        awaitCondition("the worker's detector goes below its threshold", 120_000L, () -> snapshotOf(worker).belowThreshold());
        awaitCondition("the worker drains", 240_000L, () -> cluster.getNode(worker.self().id()).isEmpty());
    }

    private List<NodeId> startWithWorker() {
        cluster = EmberTestPorts.startedCluster(new EmberTestPorts.Block(EmberTestPorts.POOL_FIRST,
                                                                          EmberTestPorts.POOL_LAST,
                                                                          EmberTestPorts.POOL_STEP,
                                                                          SLOTS,
                                                                          MGMT_OFFSET,
                                                                          APP_OFFSET),
                                                basePort -> emberCluster(CORES, basePort, basePort + MGMT_OFFSET, basePort + APP_OFFSET, "wql"),
                                                START_BOUND);
        awaitLeader();
        var originals = cluster.allNodes().stream().map(AetherNode::self).toList();
        var added = cluster.addWorkerNode().await(START_BOUND);

        assertThat(added.isSuccess()).as("the worker process starts").isTrue();
        workerId = added.unwrap();
        awaitCondition("the worker becomes ready", 120_000L, () -> cluster.getNode(added.unwrap().id()).filter(AetherNode::isReady).isPresent());

        return originals;
    }

    private AetherNode onlyWorker() {
        return cluster.getNode(workerId.id()).unwrap();
    }

    private void replaceAndAwaitWorker(NodeId victim, AetherNode worker) {
        awaitLeader().nodeReplacementService().begin(victim, "").await(START_BOUND).onFailure(cause -> {
            throw new AssertionError(cause.message());
        });
        awaitCondition("the replacement of " + victim.id() + " reaches DONE", DONE_BOUND_MS, () -> replacementDone(victim));
        awaitCondition("the leader's electorate excludes " + victim.id(), 120_000L, () -> !installed(awaitLeader()).contains(victim));
        awaitCondition("the worker holds the leader's electorate", ROSTER_BOUND_MS, () -> installed(worker).equals(installed(awaitLeader())));
    }

    private List<Boolean> observeBelowThreshold(AetherNode worker, long boundMs) {
        var below = new ArrayList<Boolean>();
        var deadline = System.currentTimeMillis() + boundMs;

        while (System.currentTimeMillis() < deadline) {
            if (snapshotOf(worker).belowThreshold()) {
                below.add(Boolean.TRUE);
            }

            sleep(250);
        }

        return below;
    }

    private void blackhole(NodeId id) {
        cluster.blackhole(id.id()).await(START_BOUND);
    }

    private static QuorumLossSnapshot snapshotOf(AetherNode node) {
        return node.quorumLossSnapshot().unwrap();
    }

    private static org.pragmatica.consensus.rabia.VoterConfiguration installed(AetherNode node) {
        return EmberNodeReplacementTest.runtime(node).voterConfiguration().unwrap();
    }

    private boolean replacementDone(NodeId victim) {
        return cluster.allNodes()
                      .stream()
                      .map(node -> node.nodeReplacementService().status(victim).or((NodeReplacementValue) null))
                      .anyMatch(record -> record != null && record.phase() == NodeReplacementPhase.DONE);
    }

    private AetherNode awaitLeader() {
        var found = new AetherNode[1];

        awaitCondition("a leader is elected", 120_000L, () -> {
            found[0] = cluster.allNodes().stream().filter(AetherNode::isLeader).findFirst().orElse(null);

            return found[0] != null;
        });

        return found[0];
    }

    private static void awaitCondition(String what, long boundMs, BooleanSupplier condition) {
        var deadline = System.currentTimeMillis() + boundMs;

        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting: " + what);
            }

            sleep(250);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
