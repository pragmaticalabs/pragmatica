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
import org.pragmatica.aether.stream.OwnerActivation;
import org.pragmatica.aether.stream.OwnerPeerReads;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.hlc.HlcTimestamp;
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
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.LongStream;

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
import org.pragmatica.storage.AppendLog;

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
class GateSealedPeerRelaxationTest {
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

    /// PROBE F (v1890, attacks round 3's B6 "by construction" argument). The gate's overlap read goes ring THEN TIER on both
    /// sides (OwnerPeerReads.ownerRange -> CatchupRead.readAppended), so a divergence the PEER holds only in sealed segments is
    /// DETECTED. Here the peer (this test's `owner` field: ring capacity 2, real sealer and tier) holds common 0..10 plus its
    /// own 11..15, sealed through >= 13 (its ring holds 14..15). The ISR-elected candidate holds common 0..10 plus the acked
    /// 11'..12' and has sealed nothing (floor -1). The first difference, 11, is ABOVE the candidate's floor, so B6 relaxes and
    /// the candidate activates; but 11 is AT OR BELOW the PEER's sealed floor, where no truncation of the peer can reach
    /// (ring tail 14, seals immutable). Per the Q6 ruling (relax only for a divergence the peer can cut) activation must
    /// refuse. Expected RED while B6 bounds by the candidate's floor only.
    @Test
    void peerDivergentOnlyInItsSealedTier_isNotARelaxationCase() {
        var candidateNode = NodeId.randomNodeId();
        var candidate = streamPartitionManager(Long.MAX_VALUE);

        try {
            candidate.createStream(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));
            publishTagged(owner, "common", 11);
            publishTagged(owner, "divergent", 5);
            publishTagged(candidate, "common", 11);
            publishTagged(candidate, "acked", 2);
            awaitSealedThrough(13);

            var gate = gateFor(candidateNode, candidate);

            assertThat(ownerRing().tailOffset()).as("arming: the peer's ring no longer holds the first divergent offset 11").isGreaterThan(11L);
            assertThat(index.lastSealedOffset(STREAM, PARTITION)).as("arming: the peer has sealed offset 11").isGreaterThanOrEqualTo(11L);
            assertThat(gate.activate(STREAM, PARTITION).await().isSuccess())
                .as("activated past a peer whose divergence (11) lies in its sealed tier, where its repair cannot cut").isFalse();
        } finally {
            candidate.close();
        }
    }

    /// Control: the same shape with the peer's divergence still in its RING (nothing sealed, ring big enough):
    /// the relaxation applies and the candidate activates. So probe F's verdict turns on where the peer holds 11.
    @Test
    void control_peerDivergentInItsRing_isRelaxed() {
        var candidateNode = NodeId.randomNodeId();
        var candidate = streamPartitionManager(Long.MAX_VALUE);
        var peer = streamPartitionManager(Long.MAX_VALUE);

        try {
            candidate.createStream(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));
            peer.createStream(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));
            publishTagged(peer, "common", 11);
            publishTagged(peer, "divergent", 5);
            publishTagged(candidate, "common", 11);
            publishTagged(candidate, "acked", 2);

            var gate = OwnerActivation.ownerActivation(candidateNode,
                                                       (_, _) -> Option.some(isrElected(candidateNode)),
                                                       (_, _) -> true,
                                                       Option.none(),
                                                       () -> List.of(candidateNode, PEER),
                                                       (_, _, _) -> Promise.success(15L),
                                                       (_, _) -> 12L,
                                                       (_, _, _, _) -> Promise.success(-1L),
                                                       () -> true,
                                                       OwnerPeerReads.ownerRange(candidateNode, candidate, tieredStreamReader(new SegmentIndex(), storage), OwnerPeerReads.localPages(peer, Option.none()), 100),
                                                       _ -> Unit.unit(),
                                                       TimeSpan.timeSpan(1).hours());

            gate.peerRingTail((_, _, _) -> Promise.success(Option.some(peer.visibleBounds(STREAM, PARTITION).map(bounds -> bounds.earliestRetained()).or(-1L))));

            assertThat(gate.activate(STREAM, PARTITION).await().isSuccess()).isTrue();
        } finally {
            candidate.close();
            peer.close();
        }
    }

    private OwnerActivation gateFor(NodeId candidateNode, StreamPartitionManager candidate) {
        var gate = OwnerActivation.ownerActivation(candidateNode,
                                               (_, _) -> Option.some(isrElected(candidateNode)),
                                               (_, _) -> true,
                                               Option.none(),
                                               () -> List.of(candidateNode, PEER),
                                               (_, _, _) -> Promise.success(15L),
                                               (_, _) -> 12L,
                                               (_, _, _, _) -> Promise.success(-1L),
                                               () -> true,
                                               OwnerPeerReads.ownerRange(candidateNode,
                                                                         candidate,
                                                                         tieredStreamReader(new SegmentIndex(), storage),
                                                                         OwnerPeerReads.localPages(owner, Option.some(tieredStreamReader(index, storage))),
                                                                         100),
                                               _ -> Unit.unit(),
                                               TimeSpan.timeSpan(1).hours());

        gate.peerRingTail((_, _, _) -> Promise.success(Option.some(ownerRing().tailOffset())));

        return gate;
    }

    private static StreamPartitionOwnershipValue isrElected(NodeId candidate) {
        return StreamPartitionOwnershipValue.streamPartitionOwnershipValue(candidate,
                                                                           Epoch.epoch(0L, 3L, 0),
                                                                           3L,
                                                                           HlcTimestamp.ZERO,
                                                                           List.of(candidate, PEER),
                                                                           5L);
    }

    /// The gate's peer read where the test reads only the candidate's own window: reaching it is a dispatch defect.
    private static Promise<StreamForwardClient.ReadForwardResult> noPeerPages(NodeId target,
                                                                             String streamName,
                                                                             int partition,
                                                                             long fromOffset,
                                                                             int maxEvents) {
        return Causes.cause("the candidate's own window was read from a peer").promise();
    }

    private static void publishTagged(StreamPartitionManager manager, String tag, int count) {
        for (var i = 0; i < count; i++) {
            manager.publishLocal(STREAM, PARTITION, (tag + "-" + i).getBytes(UTF_8), 1L).onFailure(cause -> fail("publish failed: " + cause.message()));
        }
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
                                 replica.alignedRecovery(),
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
    /// The sink indexes a segment BEFORE the sealer releases it, so for a moment an offset is both indexed and
    /// still in flight, and a catch-up read in that moment is answered `SealInFlight` (#1682). Wait for the
    /// release too — the same predicate the read path asks first.
    private void awaitSealedThrough(long offset) {
        awaitCondition(() -> index.lastSealedOffset(STREAM, PARTITION) >= offset
                             && LongStream.rangeClosed(0, offset).noneMatch(held -> owner.sealInFlight(STREAM, PARTITION, held)));
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
                                         3,
                                         2,
                                         StreamCompression.NONE,
                                         Option.none());
    }

    /// The real sink, with a gate: while holding, seals wait until `release()` — an in-flight seal the test controls.
    private static final class GatedSink implements SegmentSink {
        private final SegmentSink delegate;
        private final List<SealedSegment> heldSegments = new CopyOnWriteArrayList<>();
        private final List<Option<AppendLog>> heldLogs = new CopyOnWriteArrayList<>();
        private final List<Promise<Unit>> heldOutcomes = new CopyOnWriteArrayList<>();
        private volatile boolean holding;

        GatedSink(SegmentSink delegate) {
            this.delegate = delegate;
        }

        @Override
        public Promise<Unit> seal(SealedSegment segment, Option<AppendLog> log) {
            if (!holding) {
                return delegate.seal(segment, log);
            }

            var outcome = Promise.<Unit> promise();

            // heldSegments LAST: held() and release() size off it, so a reader that sees entry N finds logs/outcomes N.
            heldLogs.add(log);
            heldOutcomes.add(outcome);
            heldSegments.add(segment);

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

                delegate.seal(heldSegments.get(call), heldLogs.get(call)).onResult(outcome::resolve);
            }
        }
    }
}
