// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream.forward;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.CommittedStreamOwnerSource;
import org.pragmatica.aether.stream.OffHeapRingBuffer;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForward;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.aether.stream.replication.PartitionBackfill;
import org.pragmatica.aether.stream.replication.ReplicaDescriptor;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.aether.stream.replication.ReplicationMessage;
import org.pragmatica.aether.stream.segment.SealedSegment;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.aether.stream.segment.SegmentSink;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.forward.StreamForwardClient.streamForwardClient;
import static org.pragmatica.aether.stream.forward.StreamForwardHandler.streamForwardHandler;
import static org.pragmatica.aether.stream.replication.ForwardCatchupTransport.forwardCatchupTransport;
import static org.pragmatica.aether.stream.replication.PartitionBackfill.partitionBackfill;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationManager.replicationManager;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateEvents.replicateEvents;
import static org.pragmatica.aether.stream.replication.ReplicationState.CAUGHT_UP;
import static org.pragmatica.aether.stream.replication.ReplicationState.SYNCING;
import static org.pragmatica.aether.stream.replication.ReplicationReceiveHandler.replicationReceiveHandler;
import static org.pragmatica.aether.stream.segment.SegmentSealer.segmentSealer;
import static org.pragmatica.aether.stream.segment.StorageSegmentSink.storageSegmentSink;
import static org.pragmatica.aether.stream.segment.TieredStreamReader.tieredStreamReader;

/// #1383: a replacement replica's catch-up read is served from the owner's ring, or from the owner's tier for a
/// prefix the ring has evicted but the tier retains, bounded by the APPENDED head. Before the fix the owner's
/// forward handler answered the catch-up read ring-only, so a replica whose catch-up started below the ring
/// tail got `CursorExpired` on every redrive, never acked, and the partition's visible position never moved again.
///
/// The wire is in-process: the replacement peer's production `PartitionBackfill` — production-shaped: the HRW
/// owner known, self's watermark read from the replica's own ring, its acks delivered to the owner's replication
/// manager as the wire would (never a hand-seated owner row: `backfillFromOwner` promotes at the page's own last
/// offset, the path a registry-sourced fixture cannot see — rev1417 F1/F2) — pulls through the production
/// `ForwardCatchupTransport` over a `StreamForwardClient` whose transport lands each `ReadForward` on the owner's
/// `DefaultStreamForwardHandler`, whose response lands back on the client. The owner's tier is the real sealer
/// over an in-memory storage tier and a `SegmentIndex`, the same fixture as `TieredReadVisibleBoundTest`.
/// Scenario (the s1352 P3 probe): owner ring capacity 2, min-sync 2, one peer that never acknowledges, three
/// publishes → ring `[1, 2]`, offset 0 sealed, visible −1. The replacement peer has its own sealer and tier, as
/// every node does, so "holds a copy" is checked as ring-or-tier on both sides.
class ReplicaCatchupTierFallbackTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId OWNER = NodeId.randomNodeId();
    private static final NodeId PEER = NodeId.randomNodeId();
    private static final NodeId NEW_PEER = NodeId.randomNodeId();
    private static final long RING_CAPACITY = 2;
    private static final long ONE_GB = 1024 * 1024 * 1024L;
    private static final int PER_EVENT_HEADER = Long.BYTES + Long.BYTES + Integer.BYTES;

    private StorageInstance storage;
    private SegmentIndex index;
    private SegmentSink realSink;
    private GatedSink sink;
    private StorageInstance replicaStorage;
    private SegmentIndex replicaIndex;
    private ReplicaRegistry registry;
    private ReplicationManager replication;
    private StreamPartitionManager owner;
    private StreamPartitionManager replica;
    private StreamForwardHandler handler;
    private StreamForwardClient client;
    private final List<ReplicationMessage.ReplicateAck> acksToOwner = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        storage = StorageInstance.storageInstance("test", List.of(MemoryTier.memoryTier(ONE_GB)));
        index = new SegmentIndex();
        realSink = storageSegmentSink(storage, index);
        sink = new GatedSink(realSink);
        replicaStorage = StorageInstance.storageInstance("replica", List.of(MemoryTier.memoryTier(ONE_GB)));
        replicaIndex = new SegmentIndex();
        registry = replicaRegistry();
        registry.registerReplica(STREAM, PARTITION, OWNER);
        registry.registerReplica(STREAM, PARTITION, PEER);
        registry.registerReplica(STREAM, PARTITION, NEW_PEER);
        replication = replicationManager(OWNER, registry);
        owner = streamPartitionManager(Long.MAX_VALUE, segmentSealer(sink), replication);
        replica = streamPartitionManager(Long.MAX_VALUE, segmentSealer(storageSegmentSink(replicaStorage, replicaIndex)));
        owner.createStream(config()).onFailure(cause -> fail(cause.message()));
        replica.createStream(config()).onFailure(cause -> fail(cause.message()));
        client = clientAgainst(streamForwardHandler(OWNER,
                                                    owner,
                                                    this::deliverToPeer,
                                                    StreamForwardHandler.DEFAULT_MAX_READ_RESPONSE_BYTES,
                                                    StreamReadForwardMetrics.NOOP,
                                                    Option.none(),
                                                    Option.some(tieredStreamReader(index, storage))));
    }

    @AfterEach
    void tearDown() {
        owner.close();
        replica.close();
        storage.shutdown();
        replicaStorage.shutdown();
    }

    /// The ticket's scenario end to end. Today the backfill dies at `CursorExpired(0, 1)`; with the fix it applies
    /// `[0, 2]`, promotes at the owner's head and acks it over the wire — one ack now satisfies `min-sync`, so the
    /// owner's visible position, stuck at −1, moves to 2; the next live batch lands contiguously and its ack moves
    /// it to 3.
    @Test
    void replacementReplica_catchesUpPastTheEvictedPrefix_fromTheOwnersTier() {
        publish(3);
        awaitSealedThrough(0);
        assertThat(ownerRing().tailOffset()).as("offset 0 left the owner's ring").isEqualTo(1L);
        assertThat(ownerRing().visibleOffset()).as("nothing acknowledged yet").isEqualTo(-1L);

        var backfill = productionShapedBackfill();

        var applied = backfill.backfill(STREAM, PARTITION)
                              .await()
                              .onFailure(cause -> fail("backfill failed: " + cause.message()))
                              .or(-1L);

        assertThat(applied).isEqualTo(3L);
        assertThat(replica.nextExpectedOffset(STREAM, PARTITION)).isEqualTo(3L);
        assertThat(replicaRingOffsets(1)).as("the replica's ring holds the owner's ring").containsExactly(1L, 2L);
        awaitCondition(() -> replicaIndex.lastSealedOffset(STREAM, PARTITION) >= 0);
        assertThat(replicaIndex.lastSealedOffset(STREAM, PARTITION)).as("the replica's own tier holds the evicted prefix").isEqualTo(0L);
        assertThat(acksToOwner).as("the backfill acked the owner over the wire").extracting(ReplicationMessage.ReplicateAck::confirmedOffset).containsExactly(2L);
        assertThat(row(NEW_PEER).state()).isEqualTo(CAUGHT_UP);
        assertThat(row(NEW_PEER).confirmedOffset()).as("newPeerConfirmed").isEqualTo(2L);
        assertThat(replication.replicatedThrough(STREAM, PARTITION, 1)).as("replicatedThrough(minAcks = 1)").isEqualTo(2L);
        assertThat(ownerRing().visibleOffset()).as("the partition's visible position advances on the backfill ack").isEqualTo(2L);

        var acks = new CopyOnWriteArrayList<ReplicationMessage>();
        var gaps = new CopyOnWriteArrayList<String>();
        var receiver = replicationReceiveHandler(NEW_PEER,
                                                 replica::appendRecovered,
                                                 replica::nextExpectedOffset,
                                                 (_, message) -> acks.add(message),
                                                 (stream, partition) -> gaps.add(stream + "/" + partition));

        publish(1);
        receiver.onReplicateEvents(replicateEvents(OWNER, STREAM, PARTITION, 3, List.of("e".getBytes(UTF_8)), List.of(1L), Epoch.ZERO));

        assertThat(gaps).as("the live batch from 3 is contiguous on the caught-up replica").isEmpty();
        assertThat(acks).hasSize(1);
        acks.stream()
            .map(ReplicationMessage.ReplicateAck.class::cast)
            .forEach(replication::handleAck);
        assertThat(replication.replicatedThrough(STREAM, PARTITION, 1)).isEqualTo(3L);
        assertThat(ownerRing().visibleOffset()).as("and again on the live ack").isEqualTo(3L);
    }

    /// The bound: a catch-up read never returns past the owner's APPENDED head, whatever the tier holds. The tier is
    /// handed a segment `[1, 5]` directly — contiguous with the sealed `[0, 0]` — while the ring's head is 2. This
    /// state is constructed: no production path was traced that leaves the tier ahead of the ring head. The pin
    /// holds the contract of the replication-read class (#1235): a replica must never come to hold, and ack, an
    /// offset the owner has not appended, or the owner's next live batch at that offset lands on a replica that
    /// already holds something else there.
    @Test
    void catchupRead_neverReturnsPastTheAppendedHead() {
        publish(3);
        awaitSealedThrough(0);
        realSink.seal(segment(1, 5)).await().onFailure(cause -> fail(cause.message()));
        assertThat(index.contiguousSealedEnd(STREAM, PARTITION, 0).or(-1L)).as("the tier is contiguous past the ring head").isEqualTo(5L);
        assertThat(ownerRing().headOffset()).isEqualTo(2L);

        var served = catchupRead(0, 10);

        assertThat(served).containsExactly(0L, 1L, 2L);
    }

    /// An evicted offset whose seal has not landed is answered transient — `SealInFlight`, never `CursorExpired`,
    /// which names an offset nobody holds (#1407 will seat a replica past such an offset; an in-flight one must
    /// not read that way). Once the seal lands the same read is served.
    @Test
    void catchupRead_ofAnOffsetWhoseSealIsInFlight_failsTransient_thenServes() {
        sink.hold();
        publish(3);
        awaitCondition(() -> sink.held() == 1);
        assertThat(ownerRing().tailOffset()).isEqualTo(1L);

        var refused = client.readRemoteCatchup(OWNER, STREAM, PARTITION, 0, 10).await();

        assertThat(failureMessage(refused)).contains("Offset 0 of orders/0 is being sealed to storage; retry the read");

        sink.release();
        awaitSealedThrough(0);

        assertThat(catchupRead(0, 10)).containsExactly(0L, 1L, 2L);
    }

    /// A page that succeeds is at the appended head: here offset 1's seal is still in flight while 0 is sealed, so the
    /// tier serves `[0]` and the ring refuses 1 — the page FAILS (as `SealInFlight` for 1), never `[0]` alone. Once
    /// the seal lands the same read is served whole.
    @Test
    void catchupRead_failsThePage_whenTheRingRefusesTheRest() {
        publish(3);
        awaitSealedThrough(0);
        sink.hold();
        publish(1);
        awaitCondition(() -> sink.held() == 1);
        assertThat(ownerRing().tailOffset()).isEqualTo(2L);
        assertThat(ownerRing().headOffset()).isEqualTo(3L);

        var refused = client.readRemoteCatchup(OWNER, STREAM, PARTITION, 0, 10).await();

        assertThat(failureMessage(refused)).contains("Offset 1 of orders/0 is being sealed to storage; retry the read");

        sink.release();
        awaitSealedThrough(1);

        assertThat(catchupRead(0, 10)).containsExactly(0L, 1L, 2L, 3L);
    }

    /// Why the page must fail rather than shorten (rev1417 F1): on the production path the backfill promotes at
    /// the page's own last offset (`backfillFromOwner` → `applyOwnerResponse` passes no source watermark), so a
    /// prefix-alone page `[0]` would promote the replica CAUGHT_UP at 0 under an owner head of 3 and ack 0 — a
    /// false-ready row, repaired only by a later live-batch gap or the reverify interval. With the refusal the
    /// backfill fails, the row stays SYNCING at −1, nothing is acked, and the redrive after the seal lands catches
    /// up whole.
    @Test
    void backfill_staysSyncing_whileTheRingRefusesTheRest_thenCatchesUpWhole() {
        publish(3);
        awaitSealedThrough(0);
        sink.hold();
        publish(1);
        awaitCondition(() -> sink.held() == 1);
        var backfill = productionShapedBackfill();

        var refused = backfill.backfill(STREAM, PARTITION).await();

        assertThat(failureMessage(refused)).contains("Offset 1 of orders/0 is being sealed to storage; retry the read");
        assertThat(row(NEW_PEER).state()).as("no false-ready CAUGHT_UP below the head").isEqualTo(SYNCING);
        assertThat(row(NEW_PEER).confirmedOffset()).isEqualTo(-1L);
        assertThat(acksToOwner).isEmpty();
        assertThat(replica.nextExpectedOffset(STREAM, PARTITION)).as("nothing applied from a failed page").isEqualTo(0L);

        sink.release();
        awaitSealedThrough(1);

        var applied = backfill.backfill(STREAM, PARTITION)
                              .await()
                              .onFailure(cause -> fail("redrive failed: " + cause.message()))
                              .or(-1L);

        assertThat(applied).isEqualTo(4L);
        assertThat(row(NEW_PEER).state()).isEqualTo(CAUGHT_UP);
        assertThat(row(NEW_PEER).confirmedOffset()).isEqualTo(3L);
        assertThat(acksToOwner).extracting(ReplicationMessage.ReplicateAck::confirmedOffset).containsExactly(3L);
        assertThat(replication.replicatedThrough(STREAM, PARTITION, 1)).isEqualTo(3L);
    }

    /// A prefix this owner's tier does not hold (a sink that lost it) is still `CursorExpired`: an empty success
    /// would let the backfill take its no-source path off a partition that has history.
    @Test
    void catchupRead_ofAPrefixTheTierDoesNotHold_isStillCursorExpired() {
        owner.close();
        owner = streamPartitionManager(Long.MAX_VALUE, segmentSealer(SegmentSink.DISCARD), replication);
        owner.createStream(config()).onFailure(cause -> fail(cause.message()));
        client = clientAgainst(streamForwardHandler(OWNER,
                                                    owner,
                                                    this::deliverToPeer,
                                                    StreamForwardHandler.DEFAULT_MAX_READ_RESPONSE_BYTES,
                                                    StreamReadForwardMetrics.NOOP,
                                                    Option.none(),
                                                    Option.some(tieredStreamReader(index, storage))));
        publish(3);
        awaitCondition(() -> !owner.sealInFlight(STREAM, PARTITION, 0));

        var refused = client.readRemoteCatchup(OWNER, STREAM, PARTITION, 0, 10).await();

        assertThat(failureMessage(refused)).contains("Cursor at offset 0 has expired, oldest available is 1");
    }

    /// Without a tier wired (base handler) the read stays ring-only and the ring's refusal stands, as before.
    @Test
    void catchupRead_withoutATier_isCursorExpired() {
        client = clientAgainst(streamForwardHandler(OWNER, owner, this::deliverToPeer));
        publish(3);
        awaitSealedThrough(0);

        var refused = client.readRemoteCatchup(OWNER, STREAM, PARTITION, 0, 10).await();

        assertThat(failureMessage(refused)).contains("Cursor at offset 0 has expired, oldest available is 1");
    }

    /// Production shape (`AetherNode.assembleNode`): the HRW owner is known (members = `[OWNER]`), the probe answers
    /// the owner's real head, self's watermark is the replica's own ring head, and every ack the backfill sends to
    /// the owner reaches the owner's replication manager — so the owner-side assertions come from the ack path.
    private PartitionBackfill productionShapedBackfill() {
        return partitionBackfill(registry,
                                 replica::appendRecovered,
                                 forwardCatchupTransport(client, 100),
                                 this::deliverAckToOwner,
                                 (_, _, _) -> Promise.success(ownerRing().headOffset()),
                                 (stream, partition) -> replica.nextExpectedOffset(stream, partition) - 1,
                                 NEW_PEER,
                                 TimeSpan.timeSpan(0).millis(),
                                 () -> List.of(OWNER),
                                 CommittedStreamOwnerSource.none());
    }

    private void deliverAckToOwner(NodeId target, ReplicationMessage message) {
        var ack = (ReplicationMessage.ReplicateAck) message;

        acksToOwner.add(ack);
        replication.handleAck(ack);
    }

    private ReplicaDescriptor row(NodeId node) {
        return registry.replicasFor(STREAM, PARTITION)
                       .stream()
                       .filter(descriptor -> descriptor.nodeId().equals(node))
                       .findFirst()
                       .orElseThrow();
    }

    private StreamForwardClient clientAgainst(StreamForwardHandler ownerHandler) {
        handler = ownerHandler;

        return streamForwardClient(NEW_PEER, (_, message) -> handler.onReadForward((ReadForward) message));
    }

    private void deliverToPeer(NodeId target, StreamForwardMessage message) {
        client.onReadForwardResponse((ReadForwardResponse) message);
    }

    private List<Long> catchupRead(long fromOffset, int maxEvents) {
        return client.readRemoteCatchup(OWNER, STREAM, PARTITION, fromOffset, maxEvents)
                     .await()
                     .onFailure(cause -> fail("catch-up read from " + fromOffset + " failed: " + cause.message()))
                     .map(result -> result.events().stream().map(RawEventDto::offset).toList())
                     .or(List.of());
    }

    private static String failureMessage(Result<?> result) {
        return result.fold(Cause::message, value -> "succeeded with " + value);
    }

    private OffHeapRingBuffer ownerRing() {
        return owner.partitionBuffer(STREAM, PARTITION).or(() -> fail("owner ring"));
    }

    private List<Long> replicaRingOffsets(long fromOffset) {
        return replica.readAppended(STREAM, PARTITION, fromOffset, 10)
                      .onFailure(cause -> fail("replica read failed: " + cause.message()))
                      .or(List.of())
                      .stream()
                      .map(OffHeapRingBuffer.RawEvent::offset)
                      .toList();
    }

    private void publish(int count) {
        for (var i = 0; i < count; i++) {
            owner.publishLocal(STREAM, PARTITION, "e".getBytes(UTF_8), 1L).onFailure(cause -> fail("publish failed: " + cause.message()));
        }
    }

    /// Sealing runs off the appending thread (#1234); the tier holds the offset only once its seal is indexed.
    private void awaitSealedThrough(long offset) {
        awaitCondition(() -> index.lastSealedOffset(STREAM, PARTITION) >= offset);
        assertThat(index.lastSealedOffset(STREAM, PARTITION)).as("sealed through %d", offset).isGreaterThanOrEqualTo(offset);
    }

    private static void awaitCondition(BooleanSupplier condition) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(condition.getAsBoolean()).as("condition within 5s").isTrue();
    }

    /// A segment in the sealer's own wire format (offset, timestamp, length, data per event), so the tier can be
    /// handed a range the ring never evicted.
    private static SealedSegment segment(long startOffset, long endOffset) {
        var data = "e".getBytes(UTF_8);
        var count = (int) (endOffset - startOffset + 1);
        var buffer = ByteBuffer.allocate(count * (PER_EVENT_HEADER + data.length));

        for (var offset = startOffset; offset <= endOffset; offset++) {
            buffer.putLong(offset).putLong(1L).putInt(data.length).put(data);
        }

        return SealedSegment.sealedSegment(STREAM, PARTITION, startOffset, endOffset, count, 1L, 1L, buffer.array());
    }

    private static StreamConfig config() {
        return StreamConfig.streamConfig(STREAM,
                                         1,
                                         RetentionPolicy.retentionPolicy(RING_CAPACITY, 1_048_576L, 60_000L),
                                         "earliest",
                                         1_048_576L,
                                         ConsistencyMode.EVENTUAL,
                                         2,
                                         2,
                                         StreamCompression.NONE,
                                         Option.none());
    }

    /// The real sink, with a gate: while holding, seals wait until `release()` — an in-flight seal the test controls.
    private static final class GatedSink implements SegmentSink {
        private final SegmentSink delegate;
        private final List<SealedSegment> heldSegments = new CopyOnWriteArrayList<>();
        private final List<Promise<Unit>> heldOutcomes = new CopyOnWriteArrayList<>();
        private volatile boolean holding;

        GatedSink(SegmentSink delegate) {
            this.delegate = delegate;
        }

        @Override
        public Promise<Unit> seal(SealedSegment segment) {
            if (!holding) {
                return delegate.seal(segment);
            }

            var outcome = Promise.<Unit> promise();

            heldSegments.add(segment);
            heldOutcomes.add(outcome);

            return outcome;
        }

        void hold() {
            holding = true;
        }

        int held() {
            return heldSegments.size();
        }

        void release() {
            holding = false;

            for (var call = 0; call < heldSegments.size(); call++) {
                var outcome = heldOutcomes.get(call);

                delegate.seal(heldSegments.get(call)).onResult(outcome::resolve);
            }
        }
    }
}
