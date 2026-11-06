// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.deployment.cluster.NodeReplacementPlanner;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.rabia.VoterConfiguration;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #2543. A worker runs consensus as a passive client: it applies no §4 command and does no sync, so its installed electorate stayed the
/// genesis one for its whole life, while every worker-side reader of it (the quorum-loss detector first among them) counted against
/// cores that had long been replaced. The leader's installed electorate now rides the cluster directory and the worker installs it
/// when its epoch is newer.
///
/// Red before the fix: the worker keeps the genesis epoch and roster after a core replacement. Mutations: drop the leader's `voters` from
/// the directory (`DirectoryProducer`), drop the install call of `DirectoryInstaller`, or drop the engine's epoch guard.
@PortBudget
class EmberWorkerVoterRosterTest {
    private static final int CORES = 5;
    private static final int SLOTS = 2 * CORES + 4;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_OFFSET = 80;
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final long DONE_BOUND_MS = 240_000L;
    private static final long ROSTER_BOUND_MS = 30_000L;
    private static final String TIMINGS_PROPERTY = NodeReplacementPlanner.Timings.OVERRIDE_PROPERTY;

    private EmberCluster cluster;
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
    @Timeout(900)
    void workerVoterConfiguration_afterCoreReplacement_matchesCoreEpoch() {
        cluster = EmberTestPorts.startedCluster(new EmberTestPorts.Block(EmberTestPorts.POOL_FIRST,
                                                                          EmberTestPorts.POOL_LAST,
                                                                          EmberTestPorts.POOL_STEP,
                                                                          SLOTS,
                                                                          MGMT_OFFSET,
                                                                          APP_OFFSET),
                                                basePort -> emberCluster(CORES, basePort, basePort + MGMT_OFFSET, basePort + APP_OFFSET, "wvr"),
                                                START_BOUND);
        var leader = awaitLeader();
        var victim = followerOf(leader);
        var added = cluster.addWorkerNode().await(START_BOUND);

        assertThat(added.isSuccess()).as("the worker process starts").isTrue();
        var worker = cluster.getNode(added.unwrap().id()).unwrap();

        awaitCondition("the worker becomes ready", 120_000L, worker::isReady);
        var genesis = installed(worker);

        assertThat(genesis.epoch()).as("arming: the worker starts at the genesis epoch").isZero();

        leader.nodeReplacementService().begin(victim, "").await(START_BOUND).onFailure(cause -> throwBecause(cause.message()));
        awaitCondition("the replacement of " + victim.id() + " reaches DONE", DONE_BOUND_MS, () -> replacementDone(victim));
        awaitCondition("the leader's electorate moved past genesis and excludes the replaced core",
                       120_000L,
                       () -> installed(awaitLeader()).epoch() > genesis.epoch() && !installed(awaitLeader()).contains(victim));

        awaitRosterMatch(worker);
    }

    private void awaitRosterMatch(AetherNode worker) {
        var deadline = System.currentTimeMillis() + ROSTER_BOUND_MS;

        while (!installed(worker).equals(installed(awaitLeader()))) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("the worker's electorate never caught up within " + ROSTER_BOUND_MS + " ms: worker=" + installed(worker)
                                         + " leader=" + installed(awaitLeader()));
            }

            sleep(250);
        }
    }

    private boolean replacementDone(NodeId victim) {
        return cluster.allNodes()
                      .stream()
                      .map(node -> node.nodeReplacementService().status(victim).or((NodeReplacementValue) null))
                      .anyMatch(record -> record != null && record.phase() == NodeReplacementPhase.DONE);
    }

    private static VoterConfiguration installed(AetherNode node) {
        return EmberNodeReplacementTest.runtime(node).voterConfiguration().unwrap();
    }

    private AetherNode awaitLeader() {
        var found = new AetherNode[1];

        awaitCondition("a leader is elected", 120_000L, () -> {
            found[0] = cluster.allNodes().stream().filter(AetherNode::isLeader).findFirst().orElse(null);

            return found[0] != null;
        });

        return found[0];
    }

    private NodeId followerOf(AetherNode leader) {
        return cluster.allNodes().stream().filter(node -> !node.self().equals(leader.self())).findFirst().orElseThrow().self();
    }

    private static void throwBecause(String message) {
        throw new AssertionError(message);
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
