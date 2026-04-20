// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.generation;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPing;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.SnapshotPayload;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;


class NodeSnapshotCacheTest {
    private static final NodeId SELF = new NodeId("node-self");
    private static final NodeId LEADER = new NodeId("node-leader");

    private static final byte[] PAYLOAD_BYTES = new byte[]{1, 2, 3};

    private static Function<byte[], Option<ClusterGenerationSnapshot>> decoderReturning(ClusterGenerationSnapshot snapshot) {
        return _ -> Option.some(snapshot);
    }

    private static ClusterSyncPing ping(long rabiaTerm, Epoch epoch, Option<SnapshotPayload> snapshot) {
        return new ClusterSyncPing(LEADER,
                               Map.of(),
                               rabiaTerm,
                               epoch.rabiaTerm(),
                               epoch.localCounter(),
                               snapshot);
    }

    @Test
    void current_beforeAnyPing_none() {
        var cache = NodeSnapshotCache.nodeSnapshotCache(SELF);

        assertThat(cache.current().isEmpty()).isTrue();
        assertThat(cache.observedRabiaTerm()).isEqualTo(0L);
        assertThat(cache.observedEpoch()).isEqualTo(Epoch.ZERO);
    }

    @Test
    void onClusterSyncPing_firstValidPing_updatesAllFields() {
        var snapshot = ClusterGenerationSnapshot.empty(5L);
        var cache = NodeSnapshotCache.nodeSnapshotCache(SELF, decoderReturning(snapshot));

        cache.onClusterSyncPing(ping(5L, Epoch.epoch(5L, 3L), Option.some(new SnapshotPayload(PAYLOAD_BYTES))));

        assertThat(cache.observedRabiaTerm()).isEqualTo(5L);
        assertThat(cache.observedEpoch()).isEqualTo(Epoch.epoch(5L, 3L));
        assertThat(cache.current().isPresent()).isTrue();
        assertThat(cache.current().unwrap()).isEqualTo(snapshot);
    }

    @Test
    void onClusterSyncPing_staleRabiaTerm_rejects() {
        var firstSnapshot = ClusterGenerationSnapshot.empty(7L);
        var cache = NodeSnapshotCache.nodeSnapshotCache(SELF, decoderReturning(firstSnapshot));
        cache.onClusterSyncPing(ping(7L, Epoch.epoch(7L, 1L), Option.some(new SnapshotPayload(PAYLOAD_BYTES))));

        cache.onClusterSyncPing(ping(6L, Epoch.epoch(6L, 99L), Option.some(new SnapshotPayload(PAYLOAD_BYTES))));

        assertThat(cache.observedRabiaTerm()).isEqualTo(7L);
        assertThat(cache.observedEpoch()).isEqualTo(Epoch.epoch(7L, 1L));
        assertThat(cache.current().unwrap()).isEqualTo(firstSnapshot);
    }

    @Test
    void onClusterSyncPing_newerRabiaTerm_acceptsAndResetsEpoch() {
        var firstSnapshot = ClusterGenerationSnapshot.empty(7L);
        var laterSnapshot = ClusterGenerationSnapshot.empty(8L);
        var snapshotRef = new java.util.concurrent.atomic.AtomicReference<>(firstSnapshot);
        var cache = NodeSnapshotCache.nodeSnapshotCache(SELF, _ -> Option.some(snapshotRef.get()));
        cache.onClusterSyncPing(ping(7L, Epoch.epoch(7L, 99L), Option.some(new SnapshotPayload(PAYLOAD_BYTES))));

        snapshotRef.set(laterSnapshot);
        cache.onClusterSyncPing(ping(8L, Epoch.epoch(8L, 0L), Option.some(new SnapshotPayload(PAYLOAD_BYTES))));

        assertThat(cache.observedRabiaTerm()).isEqualTo(8L);
        assertThat(cache.observedEpoch()).isEqualTo(Epoch.epoch(8L, 0L));
        assertThat(cache.current().unwrap()).isEqualTo(laterSnapshot);
    }

    @Test
    void onClusterSyncPing_sameEpochReordered_ignores() {
        var firstSnapshot = ClusterGenerationSnapshot.empty(4L);
        var laterSnapshot = ClusterGenerationSnapshot.empty(4L).withDesiredCoreSize(42);
        var snapshotRef = new java.util.concurrent.atomic.AtomicReference<>(firstSnapshot);
        var cache = NodeSnapshotCache.nodeSnapshotCache(SELF, _ -> Option.some(snapshotRef.get()));
        cache.onClusterSyncPing(ping(4L, Epoch.epoch(4L, 10L), Option.some(new SnapshotPayload(PAYLOAD_BYTES))));

        snapshotRef.set(laterSnapshot);
        cache.onClusterSyncPing(ping(4L, Epoch.epoch(4L, 5L), Option.some(new SnapshotPayload(PAYLOAD_BYTES))));
        cache.onClusterSyncPing(ping(4L, Epoch.epoch(4L, 10L), Option.some(new SnapshotPayload(PAYLOAD_BYTES))));

        assertThat(cache.observedEpoch()).isEqualTo(Epoch.epoch(4L, 10L));
        assertThat(cache.current().unwrap()).isEqualTo(firstSnapshot);
    }

    @Test
    void onClusterSyncPing_strictlyNewerEpoch_accepts() {
        var firstSnapshot = ClusterGenerationSnapshot.empty(3L);
        var laterSnapshot = ClusterGenerationSnapshot.empty(3L).withDesiredCoreSize(7);
        var snapshotRef = new java.util.concurrent.atomic.AtomicReference<>(firstSnapshot);
        var cache = NodeSnapshotCache.nodeSnapshotCache(SELF, _ -> Option.some(snapshotRef.get()));
        cache.onClusterSyncPing(ping(3L, Epoch.epoch(3L, 1L), Option.some(new SnapshotPayload(PAYLOAD_BYTES))));

        snapshotRef.set(laterSnapshot);
        cache.onClusterSyncPing(ping(3L, Epoch.epoch(3L, 2L), Option.some(new SnapshotPayload(PAYLOAD_BYTES))));

        assertThat(cache.observedEpoch()).isEqualTo(Epoch.epoch(3L, 2L));
        assertThat(cache.current().unwrap()).isEqualTo(laterSnapshot);
    }

    @Test
    void onClusterSyncPing_snapshotAbsent_retainsPreviousSnapshot() {
        var firstSnapshot = ClusterGenerationSnapshot.empty(2L);
        var cache = NodeSnapshotCache.nodeSnapshotCache(SELF, decoderReturning(firstSnapshot));
        cache.onClusterSyncPing(ping(2L, Epoch.epoch(2L, 1L), Option.some(new SnapshotPayload(PAYLOAD_BYTES))));

        cache.onClusterSyncPing(ping(2L, Epoch.epoch(2L, 2L), Option.none()));

        assertThat(cache.observedEpoch()).isEqualTo(Epoch.epoch(2L, 2L));
        assertThat(cache.current().unwrap()).isEqualTo(firstSnapshot);
    }

    @Test
    void onClusterSyncPing_concurrent_consistent() throws InterruptedException {
        var snapshot = ClusterGenerationSnapshot.empty(1L);
        var cache = NodeSnapshotCache.nodeSnapshotCache(SELF, decoderReturning(snapshot));

        int threadCount = 8;
        int pingsPerThread = 500;
        var ready = new CountDownLatch(threadCount);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threadCount);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);

        for (int t = 0; t < threadCount; t++) {
            int threadIndex = t;
            pool.submit(() -> runPingLoop(cache,
                                          threadIndex,
                                          pingsPerThread,
                                          ready,
                                          start,
                                          done));
        }

        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(cache.observedRabiaTerm()).isEqualTo(1L);
        assertThat(cache.observedEpoch().rabiaTerm()).isEqualTo(1L);
        assertThat(cache.observedEpoch().localCounter()).isEqualTo((long) pingsPerThread);
        assertThat(cache.current().unwrap()).isEqualTo(snapshot);
    }

    private static void runPingLoop(NodeSnapshotCache cache,
                                    int threadIndex,
                                    int pingsPerThread,
                                    CountDownLatch ready,
                                    CountDownLatch start,
                                    CountDownLatch done) {
        ready.countDown();
        awaitUnchecked(start);
        for (int i = 1; i <= pingsPerThread; i++) {
            cache.onClusterSyncPing(ping(1L,
                                     Epoch.epoch(1L, (long) i),
                                     Option.some(new SnapshotPayload(new byte[]{(byte) threadIndex, (byte) i}))));
        }
        done.countDown();
    }

    private static void awaitUnchecked(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}
