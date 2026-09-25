// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.CommittedStreamOwnerSource;
import org.pragmatica.aether.stream.OffHeapRingBuffer;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

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
    private final ConcurrentLinkedQueue<ReplicationMessage.ReplicateAck> backfillAcks = new ConcurrentLinkedQueue<>();
    private final Promise<Long> probeAnswer = Promise.promise();

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
        // An ack for offset k counts this replica toward min-sync for the owner's event k, so every acked offset
        // must hold exactly that event here. (The original form, `doesNotContain(14L)`, forbade acking 14 at all;
        // it was never reached on the base because the log assertion above fails first, and it cannot hold once
        // the replica genuinely holds marker-14 at 14 — #1505 replaced it with the property it stood in for.)
        var ownerByOffset = markersOf(ownerLog.readAppended(STREAM, PARTITION, 0, 100).unwrap());

        assertThat(ackOffsets()).as("every acked offset holds the owner's event at that offset; replica log = %s",
                                    markersOf(held))
                                .allSatisfy(offset -> assertThat(markersOf(held)).contains(ownerByOffset.get(offset.intValue())));
        // #1505 F6: `allSatisfy` passes vacuously on an empty ack list, so pin that the live event IS acked.
        assertThat(ackOffsets()).as("the live event for 14 is held and acked").contains(14L);
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

    /// #1505, property 1: the head moved past the WHOLE response while it was in flight (live 13 and 14 both
    /// landed). Every catch-up event is verified at its own offset and none is re-appended; the run promotes at
    /// the owner's tail.
    @Test
    void backfill_liveBatchesCoverWholeResponse_verifiesEveryEvent_appendsNone_promotes() {
        var handler = receiveHandler();
        var backfill = backfill();

        handler.onReplicateEvents(liveBatch(0, REPLICA_PREFIX));
        var run = backfill.backfill(STREAM, PARTITION);
        handler.onReplicateEvents(liveBatch(13, 2));
        catchupInFlight.resolve(Result.success(ownerResponse(13, 2)));

        assertThat(run.await().isSuccess()).isTrue();
        assertThat(markersOf(replica.readAppended(STREAM, PARTITION, 0, 100).unwrap()))
                .containsExactlyElementsOf(markersOf(ownerLog.readAppended(STREAM, PARTITION, 0, 100).unwrap()));
        assertThat(selfDescriptor().state()).isEqualTo(ReplicationState.CAUGHT_UP);
        assertThat(selfDescriptor().confirmedOffset()).isEqualTo(14L);
    }

    /// #1505, property 1: an offset the replica already holds is skipped ONLY after its content is verified. A
    /// catch-up event that differs from the held one is a refusal: the run fails with the conflict, nothing is
    /// appended or overwritten, and self stays SYNCING.
    @Test
    void backfill_catchupDiffersFromHeldEvent_refusesRun_logUnchanged_staysSyncing() {
        var handler = receiveHandler();
        var backfill = backfill();

        handler.onReplicateEvents(liveBatch(0, REPLICA_PREFIX));
        var run = backfill.backfill(STREAM, PARTITION);
        handler.onReplicateEvents(liveBatch(13, 1));
        catchupInFlight.resolve(Result.success(response(13, List.of("forged-13".getBytes(UTF_8)), List.of(1013L))));

        var outcome = run.await();

        outcome.onSuccess(applied -> Assertions.fail("a divergent held event must refuse the run, applied " + applied));
        outcome.onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.ReplicaEntryConflict.class));
        assertThat(markersOf(replica.readAppended(STREAM, PARTITION, 0, 100).unwrap()))
                .containsExactlyElementsOf(markersOf(ownerLog.readAppended(STREAM, PARTITION, 0, 14).unwrap()));
        assertThat(selfDescriptor().state()).isEqualTo(ReplicationState.SYNCING);
    }

    /// #1505, property 1: a response whose first offset is PAST the local next offset (the replica lacks the
    /// offsets in between) is refused rather than appended one or more offsets too low.
    @Test
    void backfill_responseStartsPastLocalHead_refusesWithGap_appendsNothing() {
        var handler = receiveHandler();
        var backfill = backfill();

        handler.onReplicateEvents(liveBatch(0, REPLICA_PREFIX));
        var run = backfill.backfill(STREAM, PARTITION);
        catchupInFlight.resolve(Result.success(ownerResponse(14, 1)));

        var outcome = run.await();

        outcome.onSuccess(applied -> Assertions.fail("a misaligned response must refuse the run, applied " + applied));
        outcome.onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.ReplicaOffsetGap.class));
        assertThat(replica.nextExpectedOffset(STREAM, PARTITION)).isEqualTo(REPLICA_PREFIX);
        assertThat(selfDescriptor().state()).isEqualTo(ReplicationState.SYNCING);
    }

    /// #1505, property 2, on the real ring: a live batch wholly below the local head whose event DIFFERS from the
    /// held one is not acked. Before #1505 the receiver re-acked it by offset alone.
    @Test
    void liveDuplicate_heldEventDiffers_isNotAcked() {
        var handler = receiveHandler();

        handler.onReplicateEvents(liveBatch(0, REPLICA_PREFIX + 1));
        var acksBefore = ackOffsets();

        handler.onReplicateEvents(replicateEvents(owner,
                                                  STREAM,
                                                  PARTITION,
                                                  13,
                                                  List.of("forged-13".getBytes(UTF_8)),
                                                  List.of(1013L),
                                                  Epoch.ZERO));

        assertThat(ackOffsets()).as("no ack for an offset holding a different event").isEqualTo(acksBefore);
        assertThat(markersOf(replica.readAppended(STREAM, PARTITION, 13, 1).unwrap())).containsExactly("13=marker-13");
    }

    /// #1505 F2, the reviewer's S1: a divergent held entry at 13 quarantines the partition, so a LATER live batch
    /// past it (14) must not be acked. An ack is cumulative on the owner, so an ack of 14 would resolve the owner's
    /// min-sync wait for its event 13, which this replica does not hold.
    @Test
    void quarantine_divergentHeldEntry_laterLiveBatch_isNotAckedPastIt() {
        var handler = receiveHandler();

        handler.onReplicateEvents(liveBatch(0, REPLICA_PREFIX));
        replica.appendRecovered(STREAM, PARTITION, 13L, "forged-13".getBytes(UTF_8), 1013L).unwrap();
        handler.onReplicateEvents(liveBatch(13, 1));
        handler.onReplicateEvents(liveBatch(14, 1));

        assertThat(markersOf(replica.readAppended(STREAM, PARTITION, 13, 1).unwrap())).containsExactly("13=forged-13");
        assertThat(ackOffsets()).as("no ack may cover offset 13 while it holds a divergent event; acks=%s", ackOffsets())
                                .allSatisfy(offset -> assertThat(offset).isLessThan(13L));
        assertThat(replica.quarantinedAt(STREAM, PARTITION).or(-1L)).isEqualTo(13L);
        assertThat(replica.quarantinedPartitionsSinceBoot()).isEqualTo(1L);
    }

    /// #1505 F2, the reviewer's S2 with one step added. Once the replica has MET its divergent entry at 13, a
    /// redrive must not pull from 14 and promote CAUGHT_UP. The reviewer's S2 plants the entry without any path
    /// ever comparing it with the owner's; nothing can know about that divergence, so the added live batch at 13
    /// is what makes the replica meet it.
    @Test
    void quarantine_divergentHeldEntry_backfillMustNotPromoteCaughtUp() {
        var handler = receiveHandler();
        var backfill = backfill();

        handler.onReplicateEvents(liveBatch(0, REPLICA_PREFIX));
        replica.appendRecovered(STREAM, PARTITION, 13L, "forged-13".getBytes(UTF_8), 1013L).unwrap();
        handler.onReplicateEvents(liveBatch(13, 1));
        var run = backfill.backfill(STREAM, PARTITION);
        catchupInFlight.resolve(Result.success(ownerResponse(14, 1)));

        run.await()
           .onSuccess(applied -> Assertions.fail("a quarantined partition must refuse the run, applied " + applied))
           .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.ReplicaQuarantined.class));
        assertThat(markersOf(replica.readAppended(STREAM, PARTITION, 13, 1).unwrap())).containsExactly("13=forged-13");
        assertThat(selfDescriptor().state()).as("requests=%s confirmed=%s",
                                                catchupRequests.stream().map(ReplicationMessage.CatchupRequest::fromOffset).toList(),
                                                selfDescriptor().confirmedOffset())
                                            .isNotEqualTo(ReplicationState.CAUGHT_UP);
        assertThat(catchupRequests).as("nothing is pulled past a divergent entry").isEmpty();
        assertThat(backfillAcks).isEmpty();
    }

    /// #1505 F2: a replica that was already CAUGHT_UP and then meets a divergent entry is DEMOTED to SYNCING
    /// below the divergence, and its re-verify sends the owner no completion ack.
    @Test
    void quarantine_caughtUpReplicaMeetsDivergence_isDemotedBelowIt_sendsNoCompletionAck() {
        var handler = receiveHandler();
        var backfill = backfill();

        handler.onReplicateEvents(liveBatch(0, REPLICA_PREFIX));
        var first = backfill.backfill(STREAM, PARTITION);
        catchupInFlight.resolve(Result.success(ownerResponse(13, 1)));
        assertThat(first.await().isSuccess()).isTrue();
        assertThat(selfDescriptor().state()).isEqualTo(ReplicationState.CAUGHT_UP);
        backfillAcks.clear();

        handler.onReplicateEvents(replicateEvents(owner, STREAM, PARTITION, 13, List.of("forged-13".getBytes(UTF_8)), List.of(1013L), Epoch.ZERO));
        // The owner is not ahead, so a re-verify that got past the gate would take the no-op re-ack path.
        probeAnswer.resolve(Result.success(13L));
        var reverify = backfill.backfill(STREAM, PARTITION);

        assertThat(reverify.await().isFailure()).isTrue();
        assertThat(selfDescriptor().state()).isEqualTo(ReplicationState.SYNCING);
        assertThat(selfDescriptor().confirmedOffset()).isEqualTo(12L);
        assertThat(backfillAcks).as("no completion ack from a quarantined partition").isEmpty();
    }

    /// #1505 F2: the quarantine is ALSO checked at the terminal step of an in-flight run, not only at entry. The
    /// run requests 14; while it is in flight the live path meets a divergent entry at 13. The empty response then
    /// takes the #559 at-owner-tail path, and the owner's probed tail (13) would promote self. It must not.
    @Test
    void quarantine_recordedWhileRunInFlight_blocksTheAtOwnerTailPromotion() {
        var handler = receiveHandler();
        var backfill = backfill();

        handler.onReplicateEvents(liveBatch(0, REPLICA_PREFIX + 1));
        var run = backfill.backfill(STREAM, PARTITION);
        assertThat(catchupRequests).extracting(ReplicationMessage.CatchupRequest::fromOffset).containsExactly(14L);

        handler.onReplicateEvents(replicateEvents(owner, STREAM, PARTITION, 13, List.of("forged-13".getBytes(UTF_8)), List.of(1013L), Epoch.ZERO));
        catchupInFlight.resolve(Result.success(response(14, List.of(), List.of())));
        probeAnswer.resolve(Result.success(13L));

        assertThat(run.await().isFailure()).isTrue();
        assertThat(selfDescriptor().state()).isNotEqualTo(ReplicationState.CAUGHT_UP);
        assertThat(backfillAcks).isEmpty();
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
                                 (_, message) -> backfillAcks.add((ReplicationMessage.ReplicateAck) message),
                                 (_, _, _) -> probeAnswer,
                                 (stream, partition) -> replica.partitionInfo(stream, partition)
                                                               .map(StreamPartitionManager.PartitionInfo::headOffset)
                                                               .or(-1L),
                                 self,
                                 TimeSpan.timeSpan(3600).seconds(),
                                 () -> MEMBERS,
                                 CommittedStreamOwnerSource.none(),
                                 replica::syncReplicated,
                                 replica::quarantinedAt);
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

    private ReplicationMessage.CatchupResponse ownerResponse(long fromOffset, int count) {
        var events = ownerLog.readAppended(STREAM, PARTITION, fromOffset, count).unwrap();

        return response(fromOffset,
                        events.stream().map(OffHeapRingBuffer.RawEvent::data).toList(),
                        events.stream().map(OffHeapRingBuffer.RawEvent::timestamp).toList());
    }

    private ReplicationMessage.CatchupResponse response(long fromOffset, List<byte[]> payloads, List<Long> timestamps) {
        return catchupResponse(owner, STREAM, PARTITION, fromOffset, fromOffset + payloads.size() - 1, payloads, timestamps);
    }

    private ReplicaDescriptor selfDescriptor() {
        return registry.replicasFor(STREAM, PARTITION)
                       .stream()
                       .filter(descriptor -> descriptor.nodeId().equals(self))
                       .findFirst()
                       .orElseThrow();
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
