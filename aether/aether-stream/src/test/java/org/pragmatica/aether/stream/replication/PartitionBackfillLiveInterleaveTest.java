// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.CommittedStreamOwnerSource;
import org.pragmatica.aether.stream.OffHeapRingBuffer;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.IntStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.replication.PartitionBackfill.partitionBackfill;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.CatchupResponse.catchupResponse;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateEvents.replicateEvents;
import static org.pragmatica.aether.stream.replication.ReplicationReceiveHandler.replicationReceiveHandler;

/// s27 02-chaos repro: a non-owner replica's owner catch-up computes `fromOffset` from its local ring head
/// BEFORE the request ({@link PartitionBackfill} `backfillFromOwner`) and appends the response at the local
/// TAIL after it returns (`applyEvents` → `appendRecoveredEvent`), with nothing serialising the pair against
/// the live receive path. The live batch for the same offset lands in between, the catch-up copy is appended
/// one offset too high, and the next live event is classified a stale duplicate and ACKed without being held.
///
/// Real: both {@link StreamPartitionManager}s (owner and replica rings), {@link PartitionBackfill} (production
/// factory shape, HRW owner path), {@link ReplicationReceiveHandler} (production verifying factory: real
/// `appendRecovered`, `nextExpectedOffset`, `syncReplicated`), {@link ReplicaRegistry}, and the production
/// `SelfWatermark` lambda. Stubbed: the network — the catch-up transport serves the owner's real
/// `readAppended` exactly as `ForwardCatchupTransport` packs it, but resolves when the test says, which is
/// the only thing the interleaving needs (a response that arrives after a live batch from the same owner).
class PartitionBackfillLiveInterleaveTest {
    private static final String STREAM = "repl-failover-events";
    private static final int PARTITION = 0;
    private static final int OWNER_EVENTS = 15;
    private static final int REPLICA_PREFIX = 13;
    private static final NodeId NODE_A = NodeId.nodeId("node-a").unwrap();
    private static final NodeId NODE_B = NodeId.nodeId("node-b").unwrap();
    private static final List<NodeId> MEMBERS = List.of(NODE_A, NODE_B);

    private final NodeId owner = ReplicaPlacement.rank(STREAM, PARTITION, MEMBERS).getFirst();
    private final NodeId self = owner.equals(NODE_A) ? NODE_B : NODE_A;

    private StreamPartitionManager ownerLog;
    private StreamPartitionManager replica;
    private ReplicaRegistry registry;
    private final ConcurrentLinkedQueue<ReplicationMessage.ReplicateAck> acks = new ConcurrentLinkedQueue<>();
    private final Promise<ReplicationMessage.CatchupResponse> catchupInFlight = Promise.promise();
    private final ConcurrentLinkedQueue<ReplicationMessage.CatchupRequest> catchupRequests = new ConcurrentLinkedQueue<>();

    @BeforeEach
    void setUp() {
        ownerLog = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);
        ownerLog.createStream(StreamConfig.streamConfig(STREAM));
        IntStream.range(0, OWNER_EVENTS)
                 .forEach(i -> ownerLog.appendRecovered(STREAM, PARTITION, marker(i), 1000L + i).unwrap());

        replica = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);
        replica.createStream(StreamConfig.streamConfig(STREAM));

        registry = replicaRegistry();
        registry.registerReplica(STREAM, PARTITION, owner);
        registry.registerReplica(STREAM, PARTITION, self);
    }

    @Test
    void backfill_liveBatchLandsDuringCatchup_nextLiveEventIsHeldAtItsOwnerOffset() {
        var handler = receiveHandler();
        var backfill = backfill();

        // Replica received [0..12] live; it is SYNCING (never promoted), so the redrive runs a direct owner pull.
        handler.onReplicateEvents(liveBatch(0, REPLICA_PREFIX));
        assertThat(replica.nextExpectedOffset(STREAM, PARTITION)).isEqualTo(REPLICA_PREFIX);

        // 1. Backfill starts: fromOffset = local head + 1 = 13; the owner's catch-up response is in flight.
        var run = backfill.backfill(STREAM, PARTITION);
        assertThat(catchupRequests).extracting(ReplicationMessage.CatchupRequest::fromOffset)
                                   .containsExactly((long) REPLICA_PREFIX);

        // 2. The owner's live batch for offset 13 arrives first and is applied at 13.
        handler.onReplicateEvents(liveBatch(13, 1));

        // 3. The catch-up response (owner offsets from 13, served from the owner's real ring) now arrives.
        catchupInFlight.resolve(Result.success(ownerResponse(13)));
        run.await();

        // 4. The owner's live batch for offset 14.
        handler.onReplicateEvents(liveBatch(14, 1));

        var held = replica.readAppended(STREAM, PARTITION, 0, 100).unwrap();

        assertThat(markersOf(held)).as("replica log must equal the owner's log offset-for-offset; ack log = %s",
                                       ackOffsets())
                                   .containsExactlyElementsOf(markersOf(ownerLog.readAppended(STREAM,
                                                                                              PARTITION,
                                                                                              0,
                                                                                              100).unwrap()));
        assertThat(ackOffsets()).as("an ack for offset 14 counts this replica toward min-sync for marker-14")
                                .doesNotContain(14L);
    }

    /// Control: the same three messages with the catch-up response arriving BEFORE the live batch for 13. The
    /// live batch is then a genuine stale duplicate and the log matches the owner — so the assertion above can
    /// pass, and what reddens it is the arrival order alone.
    @Test
    void backfill_catchupArrivesBeforeLiveBatch_logMatchesOwner() {
        var handler = receiveHandler();
        var backfill = backfill();

        handler.onReplicateEvents(liveBatch(0, REPLICA_PREFIX));
        var run = backfill.backfill(STREAM, PARTITION);
        catchupInFlight.resolve(Result.success(ownerResponse(13)));
        run.await();
        handler.onReplicateEvents(liveBatch(13, 1));
        handler.onReplicateEvents(liveBatch(14, 1));

        assertThat(markersOf(replica.readAppended(STREAM, PARTITION, 0, 100).unwrap()))
                .containsExactlyElementsOf(markersOf(ownerLog.readAppended(STREAM, PARTITION, 0, 100).unwrap()));
    }

    private ReplicationReceiveHandler receiveHandler() {
        return replicationReceiveHandler(self,
                                         replica::appendRecovered,
                                         replica::nextExpectedOffset,
                                         (_, message) -> acks.add((ReplicationMessage.ReplicateAck) message),
                                         (_, _) -> {},
                                         replica::syncReplicated,
                                         CommittedStreamOwnerSource.none());
    }

    private PartitionBackfill backfill() {
        return partitionBackfill(registry,
                                 replica::appendRecovered,
                                 this::deferredCatchup,
                                 ReplicationTransport.NOOP,
                                 (_, _, _) -> Causes.cause("no probe").promise(),
                                 (stream, partition) -> replica.partitionInfo(stream, partition)
                                                               .map(StreamPartitionManager.PartitionInfo::headOffset)
                                                               .or(-1L),
                                 self,
                                 TimeSpan.timeSpan(3600).seconds(),
                                 () -> MEMBERS,
                                 CommittedStreamOwnerSource.none(),
                                 replica::syncReplicated);
    }

    private Promise<ReplicationMessage.CatchupResponse> deferredCatchup(NodeId target,
                                                                       ReplicationMessage.CatchupRequest request) {
        catchupRequests.add(request);
        return catchupInFlight;
    }

    /// Packed as `ForwardCatchupTransport.toResponse` packs it: `fromOffset` is the REQUEST's, `toOffset` the
    /// last returned event's offset. At request time the owner held [0..13]; marker-14 was not yet published.
    private ReplicationMessage.CatchupResponse ownerResponse(long fromOffset) {
        var events = ownerLog.readAppended(STREAM, PARTITION, fromOffset, 1).unwrap();

        return catchupResponse(owner,
                               STREAM,
                               PARTITION,
                               fromOffset,
                               events.getLast().offset(),
                               events.stream().map(OffHeapRingBuffer.RawEvent::data).toList(),
                               events.stream().map(OffHeapRingBuffer.RawEvent::timestamp).toList());
    }

    private ReplicationMessage.ReplicateEvents liveBatch(int fromOffset, int count) {
        var range = IntStream.range(fromOffset, fromOffset + count);

        return replicateEvents(owner,
                               STREAM,
                               PARTITION,
                               fromOffset,
                               range.mapToObj(PartitionBackfillLiveInterleaveTest::marker).toList(),
                               IntStream.range(fromOffset, fromOffset + count).mapToObj(i -> 1000L + i).toList(),
                               Epoch.ZERO);
    }

    private List<Long> ackOffsets() {
        return acks.stream().map(ReplicationMessage.ReplicateAck::confirmedOffset).toList();
    }

    private static List<String> markersOf(List<OffHeapRingBuffer.RawEvent> events) {
        return events.stream().map(event -> event.offset() + "=" + new String(event.data(), UTF_8)).toList();
    }

    private static byte[] marker(int i) {
        return ("marker-" + i).getBytes(UTF_8);
    }
}
