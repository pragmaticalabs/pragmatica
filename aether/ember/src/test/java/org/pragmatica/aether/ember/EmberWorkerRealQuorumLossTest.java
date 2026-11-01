// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.util.List;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.deployment.cluster.NodeReplacementPlanner;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementValue;
import org.pragmatica.aether.worker.isolation.CoreAbsenceSnapshot;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #2543, the other side of the consequence test: when the core quorum is REALLY lost, a worker must still stop serving. The worker's
/// quorum-loss detector is not the tier that sees this (with no leader its member states do not move), so the test accepts either of the
/// two ways a worker leaves service: it is gone from the cluster (a self-drain), or its core-absence fence fired. What it pins is that
/// SOMETHING does, within a bound, both on the genesis electorate and on an electorate the worker learned through #2543 after two
/// replacements.
@PortBudget
class EmberWorkerRealQuorumLossTest {
    private static final int CORES = 5;
    private static final int SLOTS = 2 * CORES + 6;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_OFFSET = 80;
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final long DONE_BOUND_MS = 240_000L;
    private static final long ROSTER_BOUND_MS = 30_000L;
    private static final long LEAVES_SERVICE_BOUND_MS = 240_000L;
    private static final String TIMINGS_PROPERTY = NodeReplacementPlanner.Timings.OVERRIDE_PROPERTY;

    private EmberCluster cluster;
    private NodeId workerId;
    private String priorTimings;

    @BeforeEach
    void shortBudgets() {
        priorTimings = System.getProperty(TIMINGS_PROPERTY);
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
    @Timeout(900)
    void workerLeavesService_whenThreeOfFiveGenesisCoresAreLost() {
        var originals = startWithWorker("wrq");
        var leader = awaitLeader();

        assertThat(snapshotArmed()).as("arming: the worker's detector has seen a quorum").isTrue();
        loseQuorum(originals, leader.self());
    }

    /// The leader is among the lost: no member state moves after the loss, so whatever drains the worker here is not the leader's update.
    @Test
    @Timeout(900)
    void workerLeavesService_whenThreeOfFiveGenesisCoresIncludingTheLeaderAreLost() {
        var originals = startWithWorker("wrl");
        var leader = awaitLeader().self();

        assertThat(snapshotArmed()).as("arming: the worker's detector has seen a quorum").isTrue();
        var others = originals.stream().filter(id -> !id.equals(leader)).limit(2);

        leaveServiceAfterLosing(java.util.stream.Stream.concat(java.util.stream.Stream.of(leader), others).toList());
    }

    @Test
    @Timeout(1500)
    void workerLeavesService_whenThreeOfFiveCurrentVotersAreLost_afterTwoReplacements() {
        var originals = startWithWorker("wrr");
        var leader = awaitLeader();
        var replaceable = originals.stream().filter(id -> !id.equals(leader.self())).toList();

        replaceAndAwaitWorker(replaceable.get(0));
        replaceAndAwaitWorker(replaceable.get(1));
        var current = installed(awaitLeader()).members();

        assertThat(installed(worker()).epoch()).as("precondition: the worker holds a post-genesis electorate").isPositive();
        loseQuorum(current, awaitLeader().self());
    }

    /// Blackholes three of the five voters, sparing the leader and one more, so two remain: no quorum.
    private void loseQuorum(List<NodeId> voters, NodeId leader) {
        leaveServiceAfterLosing(voters.stream().filter(id -> !id.equals(leader)).limit(3).toList());
    }

    private void leaveServiceAfterLosing(List<NodeId> victims) {
        assertThat(victims).hasSize(3);
        victims.forEach(id -> cluster.blackhole(id.id()).await(START_BOUND));
        var start = System.currentTimeMillis();
        var deadline = start + LEAVES_SERVICE_BOUND_MS;
        var how = "";

        while (how.isEmpty() && System.currentTimeMillis() < deadline) {
            how = leftServiceBy();
            sleep(500);
        }

        System.out.println("REALQL victims=" + victims + " left-service-by=" + (how.isEmpty() ? "NOTHING" : how)
                           + " after-ms=" + (System.currentTimeMillis() - start));
        assertThat(how).as("within %d ms of losing the core quorum the worker leaves service", LEAVES_SERVICE_BOUND_MS).isNotEmpty();
    }

    private String leftServiceBy() {
        var node = cluster.getNode(workerId.id());

        if (node.isEmpty()) {
            return "self-drain (removed from the cluster)";
        }

        var quorumLoss = node.unwrap().quorumLossSnapshot();

        quorumLoss.filter(snapshot -> snapshot.belowThreshold())
                  .onPresent(snapshot -> System.out.println("REALQL worker quorum-loss below threshold: " + snapshot));

        return node.unwrap().coreAbsenceSnapshot().map(CoreAbsenceSnapshot::fenced).or(false)
               ? "core-absence fence"
               : "";
    }

    private boolean snapshotArmed() {
        return worker().quorumLossSnapshot().unwrap().armed();
    }

    private List<NodeId> startWithWorker(String prefix) {
        cluster = EmberTestPorts.startedCluster(new EmberTestPorts.Block(EmberTestPorts.POOL_FIRST,
                                                                          EmberTestPorts.POOL_LAST,
                                                                          EmberTestPorts.POOL_STEP,
                                                                          SLOTS,
                                                                          MGMT_OFFSET,
                                                                          APP_OFFSET),
                                                basePort -> emberCluster(CORES, basePort, basePort + MGMT_OFFSET, basePort + APP_OFFSET, prefix),
                                                START_BOUND);
        awaitLeader();
        var originals = cluster.allNodes().stream().map(AetherNode::self).toList();
        var added = cluster.addWorkerNode().await(START_BOUND);

        assertThat(added.isSuccess()).as("the worker process starts").isTrue();
        workerId = added.unwrap();
        awaitCondition("the worker becomes ready", 120_000L, () -> cluster.getNode(workerId.id()).filter(AetherNode::isReady).isPresent());

        return originals;
    }

    private AetherNode worker() {
        return cluster.getNode(workerId.id()).unwrap();
    }

    private void replaceAndAwaitWorker(NodeId victim) {
        awaitLeader().nodeReplacementService().begin(victim, "").await(START_BOUND).onFailure(cause -> {
            throw new AssertionError(cause.message());
        });
        awaitCondition("the replacement of " + victim.id() + " reaches DONE", DONE_BOUND_MS, () -> replacementDone(victim));
        awaitCondition("the leader's electorate excludes " + victim.id(), 120_000L, () -> !installed(awaitLeader()).contains(victim));
        awaitCondition("the worker holds the leader's electorate", ROSTER_BOUND_MS, () -> installed(worker()).equals(installed(awaitLeader())));
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
