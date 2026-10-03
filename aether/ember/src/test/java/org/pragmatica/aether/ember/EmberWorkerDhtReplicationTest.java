// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.config.cluster.ReplicationDefaultsParser;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.dht.DHTNode;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #1777 track 1 (CTO ruling B): a WORKER's DHT resolves its replication factors. A worker runs consensus as a passive
/// client — it never restores state, so the core-side `onStateRestored` resolution never fires on it — and it is never
/// served the cluster TOML (#1390). Its only source is the DHT-replication record the core derives into every worker
/// projection. Ember runs FULL replication by default, which needs no resolution at all, so this test runs the
/// production shape (`withDhtReplication`) — without it, an Ember green says nothing about workers.
///
/// Red at `242f1113c` (track 1 before the worker fix): the worker stays `ReplicationUnresolved` forever.
@PortBudget
class EmberWorkerDhtReplicationTest {
    private static final int CORES = 3;
    private static final int SLOTS = 2 * CORES;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_OFFSET = 80;
    private static final EmberTestPorts.Block PORTS = new EmberTestPorts.Block(EmberTestPorts.POOL_FIRST,
                                                                                EmberTestPorts.POOL_LAST,
                                                                                EmberTestPorts.POOL_STEP,
                                                                                SLOTS,
                                                                                MGMT_OFFSET,
                                                                                APP_OFFSET,
                                                                                List.of());
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan REQUEST = TimeSpan.timeSpan(30).seconds();
    private static final long BOUND_MS = 120_000L;

    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.stop().await(STOP_BOUND);
        }
    }

    @Test
    @Timeout(420)
    void nonFullWorker_resolvesItsDhtReplication_andReResolvesALiveChange() {
        cluster = EmberTestPorts.startedCluster(PORTS, candidate -> {
            var created = emberCluster(CORES, candidate, candidate + MGMT_OFFSET, candidate + APP_OFFSET, "wdr");

            created.withDhtReplication(DHTConfig.DEFAULT);

            return created;
        }, START_BOUND);
        awaitCondition("a leader is elected", () -> cluster.currentLeader().isPresent());

        var added = cluster.addWorkerNode().await(START_BOUND);

        assertThat(added.isSuccess()).as("the worker process starts").isTrue();
        var worker = cluster.getNode(added.unwrap().id()).unwrap();
        var workerDht = dhtNode(worker);

        assertThat(workerDht.config().isFullReplication()).as("arming: the worker runs a placed, non-FULL DHT").isFalse();
        awaitCondition("the worker becomes ready", worker::isReady);
        awaitCondition("the worker's DHT resolves its replication from its projection", workerDht::replicationResolved);

        var key = ("never-written-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        var lastRead = new AtomicReference<String>("not read");

        awaitCondition("the worker's DHT client answers instead of refusing", () -> readsAbsent(worker, key, lastRead));

        commitReplication(5, 3);

        awaitCondition("the worker re-resolves the committed live change (owner ruling Q1)",
                       () -> workerDht.config().replicationFactor() == 5 && workerDht.config().writeQuorum() == 3);
        assertThat(cluster.allNodes()).as("every core re-resolved too")
                                      .filteredOn(node -> !node.self().equals(worker.self()))
                                      .allMatch(node -> dhtNode(node).config().replicationFactor() == 5);
    }

    private static boolean readsAbsent(AetherNode worker, byte[] key, AtomicReference<String> lastRead) {
        var read = worker.dhtClient().unwrap().get(key).await(REQUEST);

        lastRead.set(read.toString());

        return read.fold(_ -> false, Option::isEmpty);
    }

    private static DHTNode dhtNode(AetherNode node) {
        return node.dhtNode().unwrap();
    }

    /// Commits `[replication]` with the given factors into the RUNNING cluster through the leader — a live change, no
    /// restart. Armed by parsing the document with the same parser the DHT resolves from.
    private void commitReplication(int replicationFactor, int confirmationFactor) {
        var leader = cluster.currentLeader().flatMap(cluster::getNode).unwrap();
        var before = leader.kvStore().getTyped(ClusterConfigKey.CURRENT, ClusterConfigValue.class).unwrap();
        var toml = before.tomlContent().or("") + "\n[replication]\nreplication_factor = %d\nconfirmation_factor = %d\n".formatted(replicationFactor,
                                                                                                                       confirmationFactor);

        assertThat(ReplicationDefaultsParser.fromClusterToml(Option.some(toml)).map(defaults -> defaults.replicationFactor()).or(0))
            .as("arming: the document carries the new factor")
            .isEqualTo(replicationFactor);
        var value = new ClusterConfigValue(Option.some(toml),
                                           before.clusterName(),
                                           before.version(),
                                           before.desiredTopology(),
                                           before.coreMin(),
                                           before.coreMax(),
                                           before.deploymentType(),
                                           before.configVersion() + 1,
                                           System.currentTimeMillis());
        var id = UUID.randomUUID().toString();
        var authority = leader.kvStore().getTyped(LeaderKey.INSTANCE, LeaderValue.class).unwrap();
        var transaction = new KVCommand.LeaderTransaction<AetherKey, AetherValue>(ClusterConfigKey.CURRENT,
                                                                                  id,
                                                                                  authority,
                                                                                  List.of(),
                                                                                  List.of(new KVCommand.Mutation<>(ClusterConfigKey.CURRENT,
                                                                                                                   Option.some(before),
                                                                                                                   Option.some(value))));

        assertThat(leader.<Object>apply(List.of(transaction)).await(REQUEST).unwrap())
            .anyMatch(outcome -> outcome instanceof KVCommand.TransactionResult accepted && accepted.transactionId().equals(id)
                                 && accepted.accepted());
    }

    private static void awaitCondition(String what, BooleanSupplier condition) {
        var deadline = System.currentTimeMillis() + BOUND_MS;

        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting: " + what);
            }

            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted waiting: " + what);
            }
        }
    }
}
