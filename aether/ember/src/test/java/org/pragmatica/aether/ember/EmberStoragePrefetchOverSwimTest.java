// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.StorageInstance.TierInfo;
import org.pragmatica.storage.TierLevel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// Cross-node storage prefetch, end to end on real nodes: a hot block read on one node is copied into the cache tier of
/// a peer that never read it, by hints that travelled over the real SWIM transport.
///
/// Three nodes, real QUIC, Rabia, DHT and SWIM UDP. One node writes a block; a second reads it again and again (its
/// reads are reported to its own prefetch manager by the instance's real `get`); on each probe round the second node's
/// manager hands its hottest blocks to SWIM's application gossip, which sends them to ONE random peer in a datagram of
/// its own. When that peer is the third node, which holds nothing, its manager fetches the block and keeps it in its
/// memory tier as a cache-only record. A hint that lands on the writer finds the block already local and does nothing,
/// so the reader keeps reading until the third node has it: each round has an even chance of choosing it.
///
/// Pins, through the real path: the managers wake when the node reports ready (a node whose managers never woke
/// gossips and fetches nothing), `AetherNode.assembleNode` registers the channel on SWIM, `SwimProtocol` sends and
/// accepts the datagram, the node's codec registry carries `AppGossip` and the payload, and the instance's own `get`
/// reports reads.
@PortBudget
class EmberStoragePrefetchOverSwimTest {
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
    private static final TimeSpan OP_BOUND = TimeSpan.timeSpan(30).seconds();
    private static final String INSTANCE = "content";
    /// More than the default hint threshold (10), so every round the reader has a hint to send.
    private static final int READS_PER_ROUND = 14;
    private static final long ROUND_MS = 1_000L;
    private static final long ACTIVE_BOUND_MS = 90_000L;
    private static final long PREFETCH_BOUND_MS = 150_000L;

    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.stop().await(STOP_BOUND);
        }
    }

    @Test
    @Timeout(600)
    void hotBlockReadOnOneNode_isCachedByAPeerThatNeverReadIt_overSwim() {
        cluster = EmberTestPorts.startedCluster(PORTS,
                                                basePort -> emberCluster(CLUSTER_SIZE,
                                                                         basePort,
                                                                         basePort + MGMT_OFFSET,
                                                                         basePort + APP_HTTP_OFFSET,
                                                                         "prefetch"),
                                                START_BOUND);
        var nodes = List.copyOf(cluster.allNodes());

        assertThat(nodes).hasSize(CLUSTER_SIZE);
        awaitPrefetchWoken(nodes);

        var writer = nodes.get(0);
        var reader = nodes.get(1);
        var peer = nodes.get(2);
        var content = "a-hot-block-read-on-one-node-wanted-on-another".getBytes(StandardCharsets.UTF_8);
        var id = writer.storageSetups().get(INSTANCE).instance().put(content).await(OP_BOUND).unwrap();

        assertThat(cachedBytes(peer)).as("control: the peer holds nothing in memory before the reads").isZero();
        assertThat(peer.storageSetups().get(INSTANCE).metadataStore().getLifecycle(id).isPresent()).isFalse();

        var deadline = System.currentTimeMillis() + PREFETCH_BOUND_MS;

        while (System.currentTimeMillis() < deadline && cachedBytes(peer) == 0) {
            readRound(reader, id);
            sleep();
        }

        assertThat(cachedBytes(peer)).as("the peer fetched the hot block after hints reached it over SWIM")
                                     .isEqualTo(content.length);

        var record = peer.storageSetups().get(INSTANCE).metadataStore().getLifecycle(id).unwrap();

        assertThat(record.cacheOnly()).as("the peer did not write the block, it only caches it").isTrue();
        assertThat(record.presentIn()).contains(TierLevel.MEMORY);
        assertThat(peer.storageSetups().get(INSTANCE).prefetchManager().stats().warmed()).isPositive();
        assertThat(reader.storageSetups().get(INSTANCE).prefetchManager().stats().hintsSent()).isPositive();

        assertThat(peer.stop().await(STOP_BOUND).isSuccess()).as("the peer stops").isTrue();
        assertThat(peer.storageSetups().get(INSTANCE).prefetchManager().isActive())
            .as("a stopped node neither gossips nor fetches")
            .isFalse();
    }

    private static void readRound(AetherNode reader, BlockId id) {
        var instance = reader.storageSetups().get(INSTANCE).instance();

        for (var i = 0; i < READS_PER_ROUND; i++) {
            instance.get(id).await(OP_BOUND).unwrap();
        }
    }

    private static long cachedBytes(AetherNode node) {
        return node.storageSetups()
                   .get(INSTANCE)
                   .instance()
                   .tierInfo()
                   .stream()
                   .filter(info -> info.level() == TierLevel.MEMORY)
                   .mapToLong(TierInfo::usedBytes)
                   .sum();
    }

    private static void awaitPrefetchWoken(List<AetherNode> nodes) {
        var deadline = System.currentTimeMillis() + ACTIVE_BOUND_MS;

        while (System.currentTimeMillis() < deadline && !allActive(nodes)) {
            sleep();
        }

        assertThat(nodes).as("every node's prefetch manager woke when the node reported ready")
                         .allSatisfy(node -> assertThat(node.storageSetups().get(INSTANCE).prefetchManager().isActive()).isTrue());
    }

    private static boolean allActive(List<AetherNode> nodes) {
        return nodes.stream().allMatch(node -> node.storageSetups().get(INSTANCE).prefetchManager().isActive());
    }

    private static void sleep() {
        try {
            Thread.sleep(ROUND_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }
}
