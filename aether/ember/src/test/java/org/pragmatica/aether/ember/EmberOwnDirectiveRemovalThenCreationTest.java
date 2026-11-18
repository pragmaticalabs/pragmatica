// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #3330: a worker's halt on the removal of its own directive is decided from the delivered change alone. Over real
/// consensus (3 cores) a live, directed worker has its directive removed in one transaction and a directive for ANOTHER
/// community created for it in the very next one, so the worker's store may already hold the later directive when the
/// removal is handled. In every run the worker halts through the local drain (`AetherNode.EXIT_DRAINED`); before #3330
/// the outcome depended on apply timing (a store read at handle time could see the later directive, skip the halt and
/// let the put move the runtime in place). Each run admits a fresh worker, so the 20 runs are 20 independent workers;
/// each one's `own-directive-retired` warning is counted from the run's log (one per worker id).
@PortBudget
class EmberOwnDirectiveRemovalThenCreationTest {
    private static final int CORES = 3;
    private static final int RUNS = 20;
    private static final int SLOTS = CORES + RUNS + 4;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_OFFSET = 80;
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final long HALT_BOUND_MS = 60_000L;

    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        Option.option(cluster).onPresent(started -> started.stop().await(STOP_BOUND));
    }

    @Test
    @Timeout(1800)
    void removalThenCreationInAnotherCommunity_workerHaltsInEveryRun() {
        cluster = EmberTestPorts.startedCluster(new EmberTestPorts.Block(EmberTestPorts.POOL_FIRST,
                                                                          EmberTestPorts.POOL_LAST,
                                                                          EmberTestPorts.POOL_STEP,
                                                                          SLOTS,
                                                                          MGMT_OFFSET,
                                                                          APP_OFFSET),
                                                basePort -> emberCluster(CORES, basePort, basePort + MGMT_OFFSET, basePort + APP_OFFSET, "odr"),
                                                START_BOUND);
        awaitCondition("a leader is elected", 120_000L, () -> cluster.currentLeader().isPresent());

        for (int run = 0; run < RUNS; run++) {
            removeThenCreateAndAwaitHalt(run);
        }
    }

    private void removeThenCreateAndAwaitHalt(int run) {
        var workerId = admitDirectedWorker();
        var key = new AetherKey.ActivationDirectiveKey(workerId);
        var prior = leader().kvStore().getTyped(key, AetherValue.ActivationDirectiveValue.class).unwrap();
        var community = leader().kvStore()
                                .getTyped(new AetherKey.CommunityKey(prior.communityId()), AetherValue.CommunityValue.class)
                                .unwrap();
        var movedTo = new AetherKey.CommunityKey("odr-moved-" + run);
        var moved = new AetherValue.ActivationDirectiveValue(prior.role(), movedTo.communityId(), "");

        assertThat(commit(List.of(new KVCommand.Mutation<>(key, Option.some(prior), Option.none()))))
            .as("run %d: the removal is committed", run).isTrue();
        assertThat(commit(List.of(new KVCommand.Mutation<>(movedTo, Option.none(), Option.some(community)),
                                  new KVCommand.Mutation<>(key, Option.none(), Option.some(moved)))))
            .as("run %d: the creation in another community is committed right after", run).isTrue();
        awaitCondition("run " + run + ": worker " + workerId.id() + " halts", HALT_BOUND_MS,
                       () -> cluster.exitCodeOf(workerId.id()).isPresent());

        assertThat(cluster.exitCodeOf(workerId.id())).as("run %d: the worker halts through the local drain", run)
                                                     .isEqualTo(Option.some(AetherNode.EXIT_DRAINED));
        System.out.println("F3330-RUN " + run + " worker=" + workerId.id() + " exit=" + cluster.exitCodeOf(workerId.id()).unwrap());
    }

    private NodeId admitDirectedWorker() {
        var added = cluster.addWorkerNode().await(START_BOUND);

        assertThat(added.isSuccess()).as("the worker process starts").isTrue();
        var workerId = added.unwrap();
        var key = new AetherKey.ActivationDirectiveKey(workerId);

        awaitCondition("worker " + workerId.id() + " is ready and directed", 120_000L,
                       () -> cluster.getNode(workerId.id())
                                    .filter(AetherNode::isReady)
                                    .filter(node -> node.kvStore().getTyped(key, AetherValue.ActivationDirectiveValue.class).isPresent())
                                    .isPresent());
        return workerId;
    }

    private boolean commit(List<KVCommand.Mutation<AetherKey, AetherValue>> mutations) {
        var node = leader();
        var transactionId = UUID.randomUUID().toString();
        var command = new KVCommand.LeaderTransaction<AetherKey, AetherValue>(mutations.getFirst().key(),
                                                                              transactionId,
                                                                              node.kvStore().getTyped(LeaderKey.INSTANCE, LeaderValue.class).unwrap(),
                                                                              List.of(),
                                                                              mutations);
        var results = node.<Object> apply(List.of(command)).await(START_BOUND).unwrap();

        return results.stream()
                      .anyMatch(value -> value instanceof KVCommand.TransactionResult result
                                         && result.transactionId().equals(transactionId)
                                         && result.accepted());
    }

    private AetherNode leader() {
        return cluster.currentLeader().flatMap(cluster::getNode).unwrap();
    }

    private static void awaitCondition(String what, long boundMs, BooleanSupplier condition) {
        var deadline = System.currentTimeMillis() + boundMs;

        while (!condition.getAsBoolean()) {
            assertThat(System.currentTimeMillis() <= deadline).as("timed out waiting: %s", what).isTrue();
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
