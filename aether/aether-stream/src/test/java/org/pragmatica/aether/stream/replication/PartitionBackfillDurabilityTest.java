// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.stream.CommittedStreamOwnerSource;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.replication.PartitionBackfill.partitionBackfill;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.CatchupResponse.catchupResponse;

/// #1244 (ruling know 801a8b54e): replica WAL frames are no longer fsynced one by one, so a backfill run
/// ends with an explicit commit — the durability barrier — BEFORE it promotes the replica to CAUGHT_UP and
/// acks the owner. Backfill feeds promotion, and a quiet partition would otherwise never sync what it
/// pulled. A failed barrier leaves the replica SYNCING: nothing counts it as a durable copy.
class PartitionBackfillDurabilityTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final int EVENTS = 5;
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final NodeId SOURCE = NodeId.nodeId("source").unwrap();

    private ReplicaRegistry registry;
    private StreamPartitionManager manager;

    @BeforeEach
    void setUp() {
        registry = replicaRegistry();
        manager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);
        manager.createStream(StreamConfig.streamConfig(STREAM));
        registry.registerReplica(STREAM, PARTITION, SOURCE);
        registry.updateWatermark(STREAM, PARTITION, SOURCE, EVENTS - 1);
        registry.registerReplica(STREAM, PARTITION, SELF);
    }

    @Test
    void backfill_promotesOnlyAfterTheDurabilityBarrierResolves() throws InterruptedException {
        var barrier = Promise.<Unit>promise();
        var syncCalls = new ConcurrentLinkedQueue<String>();
        var synced = new CountDownLatch(1);
        var backfill = backfillWith((stream, partition) -> recordSync(syncCalls, synced, stream, partition, barrier));

        var run = backfill.backfill(STREAM, PARTITION);

        assertThat(synced.await(10, TimeUnit.SECONDS)).as("the backfill run must commit what it applied").isTrue();
        assertThat(syncCalls).containsExactly(STREAM + "#" + PARTITION);
        assertThat(selfState()).as("not CAUGHT_UP while the pulled records are not yet durable")
                               .isEqualTo(ReplicationState.SYNCING);

        barrier.resolve(Result.unitResult());

        assertThat(run.await().or(-1L)).isEqualTo((long) EVENTS);
        assertThat(selfState()).isEqualTo(ReplicationState.CAUGHT_UP);
    }

    @Test
    void backfill_staysSyncing_whenTheDurabilityBarrierFails() {
        var backfill = backfillWith((_, _) -> Causes.cause("injected fsync failure").promise());

        assertThat(backfill.backfill(STREAM, PARTITION).await().isFailure()).as("a failed barrier fails the run")
                                                                           .isTrue();
        assertThat(selfState()).as("an un-synced replica must not be counted as a durable copy")
                               .isEqualTo(ReplicationState.SYNCING);
    }

    private PartitionBackfill backfillWith(ReplicationReceiveHandler.ReplicaDurability durability) {
        return partitionBackfill(registry,
                                 manager::appendRecovered,
                                 PartitionBackfillDurabilityTest::eventsFromSource,
                                 ReplicationTransport.NOOP,
                                 (_, _, _) -> Causes.cause("no probe").promise(),
                                 (_, _) -> -1L,
                                 SELF,
                                 TimeSpan.timeSpan(3600).seconds(),
                                 List::of,
                                 CommittedStreamOwnerSource.none(),
                                 durability,
                                 manager.quarantineView());
    }

    private static Promise<Unit> recordSync(ConcurrentLinkedQueue<String> calls,
                                            CountDownLatch synced,
                                            String stream,
                                            int partition,
                                            Promise<Unit> barrier) {
        calls.add(stream + "#" + partition);
        synced.countDown();
        return barrier;
    }

    private static Promise<ReplicationMessage.CatchupResponse> eventsFromSource(NodeId target,
                                                                               ReplicationMessage.CatchupRequest request) {
        var payloads = IntStream.range(0, EVENTS).mapToObj(i -> ("event-" + i).getBytes(UTF_8)).toList();
        var timestamps = IntStream.range(0, EVENTS).mapToObj(i -> 1000L + i).toList();

        return Promise.success(catchupResponse(target, STREAM, PARTITION, 0L, EVENTS - 1, payloads, timestamps));
    }

    private ReplicationState selfState() {
        return registry.replicasFor(STREAM, PARTITION)
                       .stream()
                       .filter(d -> d.nodeId().equals(SELF))
                       .findFirst()
                       .orElseThrow()
                       .state();
    }
}
