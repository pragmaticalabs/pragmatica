// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.ReplicationDefaultsConfig;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.ConsistentHashRing;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.dht.DHTNode;
import org.pragmatica.hlc.HlcClock;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// #1777 track 1: the node's DHT takes its factors from the cluster's committed `[replication]` section and the
/// cache namespace from `[cache]`, never from the node-local placeholder it boots with.
class AetherNodeDhtReplicationTest {
    private static final NodeId SELF = new NodeId("node-0");

    @Test
    void applyDhtReplication_committedFactors_replaceThePlaceholder() {
        var node = awaiting();
        var cache = new AtomicReference<>(DHTConfig.CACHE_DEFAULT);

        AetherNode.applyDhtReplication(new ReplicationDefaultsConfig(5, 3, 1, 2, 1, ReplicationDefaultsConfig.DEFAULT_TOMBSTONE_RETENTION),
                                       node,
                                       DHTConfig.DEFAULT,
                                       DHTConfig.CACHE_DEFAULT,
                                       cache);

        assertThat(node.replicationResolved()).isTrue();
        assertThat(node.config().replicationFactor()).isEqualTo(5);
        assertThat(node.config().writeQuorum()).isEqualTo(3);
        assertThat(node.config().readQuorum()).isEqualTo(3);
        assertThat(cache.get().replicationFactor()).isEqualTo(2);
        assertThat(cache.get().writeQuorum()).isEqualTo(1);
        assertThat(cache.get().readQuorum()).isEqualTo(2);
    }

    /// #1777 track 3: the tombstone retention is cluster-wide like the factors — every replica must agree which
    /// tombstones have expired.
    @Test
    void applyDhtReplication_committedTombstoneRetention_reachesTheNode() {
        var node = awaiting();
        var retention = org.pragmatica.lang.io.TimeSpan.timeSpan(2).hours();

        AetherNode.applyDhtReplication(new ReplicationDefaultsConfig(3, 2, 1, 1, 1, retention),
                                       node,
                                       DHTConfig.DEFAULT,
                                       DHTConfig.CACHE_DEFAULT,
                                       new AtomicReference<>());

        assertThat(node.tombstoneRetention()).isEqualTo(retention);
    }

    @Test
    void applyDhtReplication_builtInDefaults_areRf3Cf2AndASingleCopyCache() {
        var node = awaiting();
        var cache = new AtomicReference<>(DHTConfig.DEFAULT);

        AetherNode.applyDhtReplication(ReplicationDefaultsConfig.BUILT_IN, node, DHTConfig.DEFAULT, DHTConfig.CACHE_DEFAULT, cache);

        assertThat(node.config().replicationFactor()).isEqualTo(3);
        assertThat(node.config().writeQuorum()).isEqualTo(2);
        assertThat(node.config().readQuorum()).isEqualTo(2);
        assertThat(cache.get().replicationFactor()).isEqualTo(1);
    }

    /// Two nodes booted with different node-local placeholders place identically once both read the same
    /// committed factors — the property a node-local `target_rf` could not give.
    @Test
    void applyDhtReplication_differentPlaceholders_resolveToTheSamePlacement() {
        var first = awaiting(DHTConfig.DEFAULT);
        var second = awaiting(DHTConfig.SINGLE_NODE);
        var committed = new ReplicationDefaultsConfig(5, 3, 1, 1, 1, ReplicationDefaultsConfig.DEFAULT_TOMBSTONE_RETENTION);

        AetherNode.applyDhtReplication(committed, first, DHTConfig.DEFAULT, DHTConfig.CACHE_DEFAULT, new AtomicReference<>());
        AetherNode.applyDhtReplication(committed, second, DHTConfig.SINGLE_NODE, DHTConfig.CACHE_DEFAULT, new AtomicReference<>());

        assertThat(first.config().replicationFactor()).isEqualTo(second.config().replicationFactor());
        assertThat(first.config().writeQuorum()).isEqualTo(second.config().writeQuorum());
        assertThat(first.config().readQuorum()).isEqualTo(second.config().readQuorum());
    }

    @Test
    void applyDhtReplication_declaredFullReplication_isKept() {
        var node = DHTNode.dhtNode(SELF, memoryStorageEngine(), ring(), DHTConfig.FULL);

        AetherNode.applyDhtReplication(ReplicationDefaultsConfig.BUILT_IN, node, DHTConfig.FULL, DHTConfig.CACHE_DEFAULT, new AtomicReference<>());

        assertThat(node.config().isFullReplication()).isTrue();
    }

    /// #1777 track 1 (CTO ruling B), core side: the worker projection's record is the committed `[replication]` and
    /// `[cache]` factors — the built-in ones while nothing is committed.
    @Test
    void workerDhtReplication_isDerivedFromTheCommittedSections() {
        var toml = "[replication]\nreplication_factor = 5\nconfirmation_factor = 3\n\n[cache]\nreplication_factor = 2\nconfirmation_factor = 1\n";
        var committed = new org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue(org.pragmatica.lang.Option.some(toml), "c", "1", java.util.List.of(), 5, 5, "forge", 1, 0);

        assertThat(AetherNode.workerDhtReplication(org.pragmatica.lang.Option.none()).unwrap())
            .isEqualTo(new org.pragmatica.aether.worker.metadata.WorkerMetadataMessage.DhtReplication(3, 2, 1, 1));
        assertThat(AetherNode.workerDhtReplication(org.pragmatica.lang.Option.some(committed)).unwrap())
            .isEqualTo(new org.pragmatica.aether.worker.metadata.WorkerMetadataMessage.DhtReplication(5, 3, 2, 1));
    }

    /// #1777 track 1 (CTO ruling B), worker side: a projection's record resolves a worker's DHT that never restores
    /// consensus state — and a later record re-resolves it live (owner ruling Q1).
    @Test
    void applyWorkerDhtReplication_resolvesAndReResolvesAWorkersDht() {
        var node = awaiting();
        var cache = new AtomicReference<>(DHTConfig.CACHE_DEFAULT);

        assertThat(node.replicationResolved()).as("control: a worker starts unresolved").isFalse();

        AetherNode.applyWorkerDhtReplication(new org.pragmatica.aether.worker.metadata.WorkerMetadataMessage.DhtReplication(3, 2, 2, 1),
                                             node,
                                             DHTConfig.DEFAULT,
                                             DHTConfig.CACHE_DEFAULT,
                                             cache);

        assertThat(node.replicationResolved()).isTrue();
        assertThat(node.config().replicationFactor()).isEqualTo(3);
        assertThat(cache.get().replicationFactor()).isEqualTo(2);

        AetherNode.applyWorkerDhtReplication(new org.pragmatica.aether.worker.metadata.WorkerMetadataMessage.DhtReplication(5, 3, 1, 1),
                                             node,
                                             DHTConfig.DEFAULT,
                                             DHTConfig.CACHE_DEFAULT,
                                             cache);

        assertThat(node.config().replicationFactor()).isEqualTo(5);
        assertThat(node.config().writeQuorum()).isEqualTo(3);
    }

    /// #1777 (CTO ruling R2 / Q4): the bare `DHTClient` extension — what idempotency resolves — is the REPLICATED DHT at
    /// the committed `[replication]` factors; the cache namespace's `[cache]` client is a separate extension type.
    @Test
    void registerDhtExtensions_bindsTheReplicatedClientAsDhtClient_andTheCacheClientSeparately() {
        var node = awaiting();

        AetherNode.applyDhtReplication(new ReplicationDefaultsConfig(5, 3, 1, 1, 1, ReplicationDefaultsConfig.DEFAULT_TOMBSTONE_RETENTION),
                                       node,
                                       DHTConfig.DEFAULT,
                                       DHTConfig.CACHE_DEFAULT,
                                       new AtomicReference<>());
        var replicated = org.pragmatica.dht.DistributedDHTClient.distributedDHTClient(node,
                                                                                    (_, _) -> {},
                                                                                    org.pragmatica.dht.OwnerEpochSource.zero());
        var cache = replicated.scoped(() -> DHTConfig.CACHE_DEFAULT);
        var captured = new AtomicReference<org.pragmatica.aether.slice.ProvisioningContext>();
        var spi = org.pragmatica.aether.resource.SpiResourceProvider.spiResourceProvider(java.util.List.of(new CapturingFactory(captured)),
                                                                                         (_, _) -> org.pragmatica.lang.Result.success("probe"));

        AetherNode.registerDhtExtensions(spi, replicated, cache);
        spi.provide(Probe.class, "probe", org.pragmatica.aether.slice.ProvisioningContext.provisioningContext()).await();

        var dhtClient = captured.get().extension(org.pragmatica.dht.DHTClient.class).unwrap();
        var cacheClient = captured.get().extension(org.pragmatica.aether.resource.interceptor.CacheDhtClient.class).unwrap().client();

        assertThat(dhtClient.config().replicationFactor()).as("idempotency's DHT: the [replication] RF").isEqualTo(5);
        assertThat(dhtClient.config().writeQuorum()).as("idempotency's DHT: the [replication] CF").isEqualTo(3);
        assertThat(cacheClient.config().replicationFactor()).as("the cache namespace keeps its own").isEqualTo(1);
    }

    record Probe() {}

    private record CapturingFactory(AtomicReference<org.pragmatica.aether.slice.ProvisioningContext> captured)
                                   implements org.pragmatica.aether.resource.ResourceFactory<Probe, String> {
        @Override
        public Class<Probe> resourceType() {
            return Probe.class;
        }

        @Override
        public Class<String> configType() {
            return String.class;
        }

        @Override
        public org.pragmatica.lang.Promise<Probe> provision(String config) {
            return org.pragmatica.lang.Promise.success(new Probe());
        }

        @Override
        public org.pragmatica.lang.Promise<Probe> provision(String config, org.pragmatica.aether.slice.ProvisioningContext context) {
            captured.set(context);

            return provision(config);
        }
    }

    private static DHTNode awaiting() {
        return awaiting(DHTConfig.DEFAULT);
    }

    private static DHTNode awaiting(DHTConfig placeholder) {
        return DHTNode.dhtNodeAwaitingReplication(SELF, memoryStorageEngine(), ring(), placeholder, HlcClock.hlcClock(SELF));
    }

    private static ConsistentHashRing<NodeId> ring() {
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();

        for (int i = 0; i < 5; i++) {
            ring.addNode(new NodeId("node-" + i));
        }

        return ring;
    }
}
