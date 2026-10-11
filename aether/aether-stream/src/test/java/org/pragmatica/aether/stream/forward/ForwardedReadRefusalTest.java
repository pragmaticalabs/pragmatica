// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.forward;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.ReadPreference;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.fence.OwnershipDomain;
import org.pragmatica.aether.slice.fence.OwnershipEpochHighWater;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.EpochStart;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.CommittedStreamOwnerSource;
import org.pragmatica.aether.stream.CommittedStreamOwnerSource.CommittedOwner;
import org.pragmatica.aether.stream.EvictionListener;
import org.pragmatica.aether.stream.LinearizableBarrier;
import org.pragmatica.aether.stream.LinearizableOwnerServe;
import org.pragmatica.aether.stream.OffHeapRingBuffer;
import org.pragmatica.aether.stream.OwnerPeerReads;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.StreamReadRouter;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForward;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicaSetController.Role;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.aether.stream.replication.ReplicationState;
import org.pragmatica.aether.stream.segment.SealedSegment;
import org.pragmatica.aether.stream.segment.SegmentError;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.aether.stream.segment.StorageSegmentSink;
import org.pragmatica.aether.stream.segment.TieredStreamReader;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.http.HttpError;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;
import org.pragmatica.serialization.SliceCodec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.forward.StreamForwardClient.streamForwardClient;
import static org.pragmatica.aether.stream.forward.StreamForwardHandler.streamForwardHandler;


/// #1967: a forwarded stream read is refused with the SAME cause a local read of the same partition gives, so the route that maps
/// a local refusal to a status maps the forwarded one identically. Two real nodes in one JVM: the owner holds the data and
/// answers through the real `StreamForwardHandler`; the caller holds the stream's config but no ring (a metadata-only node) and
/// reads through the real `StreamReadRouter` and `StreamForwardClient`. Every message crosses the production codec in both
/// directions, so a slot the codec drops or reorders fails here and not on a cluster.
///
/// One test per refusal a read can raise on the serving node. The set is the one `ReadRefusal` carries: the owner role gate, the
/// ring read, the verified-copy gate, the epoch validation, the linearizable pipeline, the held-partition naming and the sealed
/// tier. A refusal outside it travels as text and is covered by `outsideTheCarriedSet_*`. Where a refusal is also reachable by a
/// routed read the test asserts it at the router; the replication-class ones only exist as catch-up reads and are asserted at the
/// client.
class ForwardedReadRefusalTest {
    private static final SliceCodec CODEC = SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), ForwardCodecsStream.CODECS);
    private static final NodeId OWNER_ID = new NodeId("owner-node");
    private static final NodeId CALLER_ID = new NodeId("caller-node");
    private static final NodeId OTHER_ID = new NodeId("other-node");
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final Epoch E1 = Epoch.epoch(1L, 1L, 1L);
    private static final Epoch E2 = Epoch.epoch(1L, 1L, 2L);

    private final List<ReadForwardResponse> answers = new CopyOnWriteArrayList<>();
    private StreamPartitionManager owner;
    private StreamPartitionManager caller;
    private StreamForwardHandler handler;
    private StreamForwardClient client;
    private StreamReadRouter callerRouter;

    @BeforeEach
    void startTwoNodes() {
        owner = streamPartitionManager(Long.MAX_VALUE);
        caller = streamPartitionManager(Long.MAX_VALUE);
        caller.placementRoleSupplier((_, _) -> Role.NONE);
        client = streamForwardClient(CALLER_ID,
                                     (_, message) -> handler.onReadForward((ReadForward) overTheWire(message)),
                                     TimeSpan.timeSpan(5).seconds());
    }

    @AfterEach
    void stopTwoNodes() {
        owner.close();
        caller.close();
    }

    /// Wires the owner's handler and the caller's router once the test has set the owner up.
    private void connect(Option<LinearizableOwnerServe<OffHeapRingBuffer.RawEvent>> ownerServe,
                         Option<CommittedStreamOwnerSource> committedOwner) {
        handler = streamForwardHandler(OWNER_ID,
                                       owner,
                                       (_, message) -> deliverToCaller(overTheWire(message)),
                                       Long.MAX_VALUE,
                                       StreamReadForwardMetrics.NOOP,
                                       ownerServe);
        callerRouter = StreamReadRouter.streamReadRouter(caller,
                                                         Option.none(),
                                                         Option.some(client),
                                                         CALLER_ID,
                                                         (_, _) -> Option.some(OWNER_ID),
                                                         StreamReadForwardMetrics.NOOP,
                                                         committedOwner,
                                                         Option.none(),
                                                         Option.none());
    }

    private void connect() {
        connect(Option.none(), Option.none());
    }

    private void deliverToCaller(Object message) {
        var response = (ReadForwardResponse) message;

        answers.add(response);
        client.onReadForwardResponse(response);
    }

    @SuppressWarnings("unchecked")
    private static <T> T overTheWire(T message) {
        var buffer = Unpooled.buffer();

        CODEC.write(buffer, message);

        return CODEC.read(buffer);
    }

    // ---- the ring read ------------------------------------------------------------------------------------------------

    /// The #1921 case: retention has rolled the tail past 0, and a default read starting at 0 must be told by the typed
    /// `CursorExpired` naming the earliest retained offset, not by a `ReadForwardFailed` the route cannot map.
    @Test
    void cursorExpired_onARetentionRolledStream_arrivesAsTheOwnersCursorExpired() {
        rolledStreamOnOwner();
        callerHoldsOnlyTheConfig(config(STREAM, 1));
        connect();
        var local = failureOf(owner.readServing(STREAM, PARTITION, 0, 10));

        assertThat(local).isInstanceOfSatisfying(StreamError.CursorExpired.class, expired -> assertThat(expired.tailOffset()).isGreaterThan(0));
        assertThat(remoteRead(0)).as("at the client").isEqualTo(local);
        assertThat(routedRead(0)).as("through the router a route calls").isEqualTo(local);
    }

    @Test
    void streamNotFound_whenTheOwnerHasNoSuchStream_arrivesAsStreamNotFound() {
        callerHoldsOnlyTheConfig(config(STREAM, 1));
        connect();

        assertThat(remoteRead(0)).as("at the client").isEqualTo(new StreamError.StreamNotFound(STREAM));
        assertThat(routedRead(0)).as("through the router").isEqualTo(new StreamError.StreamNotFound(STREAM));
    }

    @Test
    void partitionOutOfRange_whenTheOwnerDeclaresFewerPartitions_arrivesAsPartitionOutOfRange() {
        owner.createStream(config(STREAM, 2)).onFailure(cause -> fail(cause.message()));
        callerHoldsOnlyTheConfig(config(STREAM, 4));
        connect();
        var expected = new StreamError.PartitionOutOfRange(STREAM, 3, 2);

        assertThat(failureOf(owner.readServing(STREAM, 3, 0, 10))).as("what a local read of the owner gives").isEqualTo(expected);
        assertThat(remoteReadOf(3, 0)).as("at the client").isEqualTo(expected);
        assertThat(routedReadOf(3, 0)).as("through the router").isEqualTo(expected);
    }

    @Test
    void partitionNotLocal_whenTheOwnerHoldsNoRingEither_arrivesAsTheSingletonTheRoutersForwardOn() {
        owner.placementRoleSupplier((_, _) -> Role.NONE);
        owner.onStreamConfigPut(configPut(config(STREAM, 1)));
        callerHoldsOnlyTheConfig(config(STREAM, 1));
        connect();

        assertThat(failureOf(owner.readServing(STREAM, PARTITION, 0, 10))).isSameAs(StreamError.General.PARTITION_NOT_LOCAL);
        assertThat(remoteRead(0)).as("the very constant, not an equal copy: callers compare by identity").isSameAs(StreamError.General.PARTITION_NOT_LOCAL);
        assertThat(routedRead(0)).as("a forward that lands on a non-owner is the same retryable refusal a local miss is")
                                 .isSameAs(StreamError.General.PARTITION_NOT_LOCAL);
    }

    @Test
    void bufferClosed_whenTheOwnersRingIsClosedUnderTheRead_arrivesAsBufferClosed() {
        owner.createStream(config(STREAM, 1)).onFailure(cause -> fail(cause.message()));
        owner.partitionBuffer(STREAM, PARTITION).onPresent(OffHeapRingBuffer::close);
        callerHoldsOnlyTheConfig(config(STREAM, 1));
        connect();

        assertThat(failureOf(owner.readServing(STREAM, PARTITION, 0, 10))).isSameAs(StreamError.General.BUFFER_CLOSED);
        assertThat(remoteRead(0)).as("at the client").isSameAs(StreamError.General.BUFFER_CLOSED);
        assertThat(routedRead(0)).as("through the router").isSameAs(StreamError.General.BUFFER_CLOSED);
    }

    // ---- the owner role gate and the verified-copy gate ---------------------------------------------------------------

    @Test
    void ownerNotActivated_whenTheOwnerHasNotCompletedPromotion_arrivesAsOwnerNotActivated() {
        owner.createStream(config(STREAM, 1)).onFailure(cause -> fail(cause.message()));
        owner.placementRoleSupplier((_, _) -> Role.OWNER);
        owner.ownerServeGate((stream, partition) -> new StreamError.OwnerNotActivated(stream, partition).<Unit> result());
        callerHoldsOnlyTheConfig(config(STREAM, 1));
        connect();
        var expected = new StreamError.OwnerNotActivated(STREAM, PARTITION);

        assertThat(failureOf(owner.readServing(STREAM, PARTITION, 0, 10))).isEqualTo(expected);
        assertThat(remoteRead(0)).as("at the client").isEqualTo(expected);
        assertThat(routedRead(0)).as("through the router").isEqualTo(expected);
    }

    /// A demoted owner still holding its old tail: it serves nothing from the new epoch's start until it has been compared with
    /// that epoch's owner.
    @Test
    void replicaNotVerified_whenTheCopyHasNotBeenComparedWithTheEpochsOwner_arrivesAsReplicaNotVerified() {
        owner.ownerEpochSource((_, _) -> E2);
        owner.createStream(config(STREAM, 1)).onFailure(cause -> fail(cause.message()));
        for (var i = 0; i < 10; i++) {
            owner.appendRecovered(STREAM, PARTITION, i, ("old-" + i).getBytes(UTF_8), 1000L + i, E1).unwrap();
        }
        owner.syncReplicated(STREAM, PARTITION).await();
        owner.placementRoleSupplier((_, _) -> Role.REPLICA);
        owner.epochStarts((_, _, epoch) -> epoch.equals(E2)
                                           ? Option.some(5L)
                                           : Option.none());
        callerHoldsOnlyTheConfig(config(STREAM, 1));
        connect();
        var expected = new StreamError.ReplicaNotVerified(STREAM, PARTITION, 5L);

        assertThat(failureOf(owner.readServing(STREAM, PARTITION, 5L, 10))).isEqualTo(expected);
        assertThat(remoteRead(5L)).as("at the client").isEqualTo(expected);
        assertThat(routedRead(5L)).as("through the router").isEqualTo(expected);
    }

    // ---- the epoch-validated consumer read ----------------------------------------------------------------------------

    /// A consumer that claims an epoch newer than the owner's committed one reached a stale owner.
    @Test
    void staleEpochRead_whenTheConsumersEpochIsAheadOfTheOwners_arrivesAsStaleEpochRead() {
        ownerCommittedAtEpochTwo();
        callerHoldsOnlyTheConfig(config(STREAM, 1));
        connect();
        var e3 = Epoch.epoch(1L, 1L, 3L);
        var expected = new StreamError.StaleEpochRead(STREAM, PARTITION, e3, E2);

        assertThat(failureOf(owner.readServing(STREAM, PARTITION, 2L, 10, e3))).isEqualTo(expected);
        assertThat(causeOf(client.readRemoteValidated(OWNER_ID, STREAM, PARTITION, 2L, 10, e3).await())).as("at the client").isEqualTo(expected);
        assertThat(causeOf(callerRouter.readValidated(STREAM, PARTITION, 2L, 10, e3).await())).as("through the router").isEqualTo(expected);
    }

    @Test
    void epochDiverged_stillArrivesInItsOwnSlots_notAsARefusal() {
        ownerCommittedAtEpochTwo();
        callerHoldsOnlyTheConfig(config(STREAM, 1));
        connect();
        var diverged = causeOf(client.readRemoteValidated(OWNER_ID, STREAM, PARTITION, 5L, 10, E1).await());

        assertThat(diverged).isInstanceOfSatisfying(StreamError.EpochDiverged.class,
                                                    cause -> assertThat(cause.resumeAt()).isEqualTo(3L));
        assertThat(answers.getLast().refusal()).as("the divergence has its own slots; the refusal slot stays empty").isEqualTo(Option.none());
    }

    // ---- the linearizable pipeline ------------------------------------------------------------------------------------

    @Test
    void notCurrentOwner_whenTheForwardLandsOnANodeThatIsNotTheCommittedOwner_arrivesAsNotCurrentOwner() {
        publishedOnOwner(1);
        callerHoldsOnlyTheConfig(config(STREAM, 1));
        connect(linearizable(committedAt(OTHER_ID, E1), Option.none(), Option.none(), Option.none()), committedAt(OWNER_ID, E1));
        var expected = new StreamError.NotCurrentOwner(STREAM, PARTITION, OTHER_ID, OWNER_ID);

        assertThat(remoteLinearizableRead()).as("at the client").isEqualTo(expected);
        assertThat(routedLinearizableRead()).as("through the router").isEqualTo(expected);
    }

    @Test
    void staleEpochRead_whenTheCommittedOwnerIsBehindTheHighWater_arrivesAsStaleEpochReadFromTheLinearizablePipeline() {
        publishedOnOwner(1);
        callerHoldsOnlyTheConfig(config(STREAM, 1));
        var highWater = OwnershipEpochHighWater.ownershipEpochHighWater(emptyStore());

        highWater.advance(OwnershipDomain.streamPartition(STREAM, PARTITION), E2);
        connect(linearizable(committedAt(OWNER_ID, E1), Option.some(highWater), Option.none(), Option.none()), committedAt(OWNER_ID, E1));
        var expected = new StreamError.StaleEpochRead(STREAM, PARTITION, E1, E2);

        assertThat(remoteLinearizableRead()).as("at the client").isEqualTo(expected);
        assertThat(routedLinearizableRead()).as("through the router").isEqualTo(expected);
    }

    @Test
    void ownerCatchupPending_whenTheCommittedOwnerHasNotCaughtUp_arrivesAsOwnerCatchupPending() {
        publishedOnOwner(1);
        callerHoldsOnlyTheConfig(config(STREAM, 1));
        var registry = ReplicaRegistry.replicaRegistry();

        registry.registerReplica(STREAM, PARTITION, OWNER_ID);
        registry.updateWatermark(STREAM, PARTITION, OWNER_ID, 0L, ReplicationState.SYNCING);
        connect(linearizable(committedAt(OWNER_ID, E1), Option.none(), Option.some(registry), Option.none()), committedAt(OWNER_ID, E1));
        var expected = new StreamError.OwnerCatchupPending(STREAM, PARTITION);

        assertThat(remoteLinearizableRead()).as("at the client").isEqualTo(expected);
        assertThat(routedLinearizableRead()).as("through the router").isEqualTo(expected);
    }

    @Test
    void linearizableRoundTimeout_whenTheNoOpRoundDoesNotApply_arrivesAsLinearizableRoundTimeout() {
        publishedOnOwner(1);
        callerHoldsOnlyTheConfig(config(STREAM, 1));
        LinearizableBarrier timingOut = (stream, partition) -> new StreamError.LinearizableRoundTimeout(stream, partition).promise();

        connect(linearizable(committedAt(OWNER_ID, E1), Option.none(), Option.none(), Option.some(timingOut)), committedAt(OWNER_ID, E1));
        var expected = new StreamError.LinearizableRoundTimeout(STREAM, PARTITION);

        assertThat(remoteLinearizableRead()).as("at the client").isEqualTo(expected);
        assertThat(routedLinearizableRead()).as("through the router").isEqualTo(expected);
    }

    // ---- the replication-class read -----------------------------------------------------------------------------------

    /// A holder that has not materialized the partition (paced, or out of off-heap budget) names its durable watermark instead of
    /// answering as a non-holder, and the prober reads that as an answer. It was recovered from the text; it is carried now.
    @Test
    void partitionHeldNotMaterialized_whenTheOwnerHoldsButHasNotMaterialized_arrivesWithItsWatermarkAndBudgetFlag() {
        var held = new java.util.concurrent.ConcurrentHashMap<String, Role>();

        owner.placementRoleSupplier((stream, _) -> held.getOrDefault(stream, Role.NONE));
        owner.onStreamConfigPut(configPut(config("busy", 2)));
        owner.onStreamConfigPut(configPut(config("fresh", 1)));
        held.put("busy", Role.REPLICA);
        held.put("fresh", Role.REPLICA);
        owner.materializePartition("busy", 0).onFailure(cause -> fail("first slot should materialize: " + cause.message()));
        owner.materializePartition("busy", 1).onFailure(cause -> fail("second slot should materialize: " + cause.message()));
        owner.materializePartition("fresh", 0).onSuccess(_ -> fail("fresh[0] must be paced"));
        connect();

        var refused = causeOf(client.readRemoteCatchup(OWNER_ID, "fresh", 0, 0L, 10).await());

        assertThat(refused).isInstanceOfSatisfying(StreamError.PartitionHeldNotMaterialized.class,
                                                   refusal -> {
                                                       assertThat(refusal.streamName()).isEqualTo("fresh");
                                                       assertThat(refusal.watermark()).isEqualTo(-1L);
                                                       assertThat(refusal.budgetExhausted()).isFalse();
                                                   });
        assertThat(StreamError.PartitionHeldNotMaterialized.watermarkOf(refused)).as("the prober's reading of it is unchanged").isEqualTo(Option.some(-1L));
    }

    /// The owner's tier holds `[0, 1]` and `[5, 5]` with a hole between, and its ring has rolled past it: a catch-up read inside the
    /// hole is a terminal `SealedRangeMissing`, not a `CursorExpired` and not a text.
    @Test
    void sealedRangeMissing_whenTheOwnersTierHasAHoleAtTheCursor_arrivesAsSealedRangeMissing() {
        var registry = ReplicaRegistry.replicaRegistry();

        registry.registerReplica(STREAM, PARTITION, CALLER_ID);
        owner.close();
        owner = streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, ReplicationManager.replicationManager(OWNER_ID, registry));
        owner.createStream(config(STREAM, 1, 2)).onFailure(cause -> fail(cause.message()));
        for (var i = 0; i < 8; i++) {
            owner.publishLocal(STREAM, PARTITION, "e".getBytes(UTF_8), 1L).onFailure(cause -> fail(cause.message()));
        }
        var storage = StorageInstance.storageInstance("owner-tier", List.of(MemoryTier.memoryTier(1024 * 1024 * 1024L)));
        var index = new SegmentIndex();
        var sink = StorageSegmentSink.storageSegmentSink(storage, index);

        try {
            sink.seal(segment(0, 1)).await().onFailure(cause -> fail(cause.message()));
            sink.seal(segment(5, 5)).await().onFailure(cause -> fail(cause.message()));
            callerHoldsOnlyTheConfig(config(STREAM, 1, 2));
            handler = streamForwardHandler(OWNER_ID,
                                           owner,
                                           (_, message) -> deliverToCaller(overTheWire(message)),
                                           Long.MAX_VALUE,
                                           StreamReadForwardMetrics.NOOP,
                                           Option.none(),
                                           Option.some(TieredStreamReader.tieredStreamReader(index, storage)));

            assertThat(causeOf(client.readRemoteCatchup(OWNER_ID, STREAM, PARTITION, 3L, 10).await())).isEqualTo(new SegmentError.SealedRangeMissing(STREAM, PARTITION, 3L, 5L));
        } finally {
            storage.shutdown();
        }
    }

    private static SealedSegment segment(long startOffset, long endOffset) {
        var data = "e".getBytes(UTF_8);
        var count = (int) (endOffset - startOffset + 1);
        var perEvent = Long.BYTES + Long.BYTES + Integer.BYTES + data.length;
        var buffer = ByteBuffer.allocate(count * perEvent);

        for (var offset = startOffset; offset <= endOffset; offset++) {
            buffer.putLong(offset).putLong(1L).putInt(data.length).put(data);
        }

        return SealedSegment.sealedSegment(STREAM, PARTITION, startOffset, endOffset, count, 1L, 1L, buffer.array());
    }

    // ---- outside the carried set --------------------------------------------------------------------------------------

    /// A cause no read is known to raise keeps today's shape: the owner's text in a `ReadForwardFailed`, no refusal on the wire.
    @Test
    void outsideTheCarriedSet_aFailureTravelsAsTextAndIsRebuiltAsReadForwardFailed() {
        owner.createStream(config(STREAM, 1)).onFailure(cause -> fail(cause.message()));
        owner.placementRoleSupplier((_, _) -> Role.OWNER);
        owner.ownerServeGate((_, _) -> StreamError.General.STREAM_CONFIG_COMMIT_FAILED.<Unit> result());
        callerHoldsOnlyTheConfig(config(STREAM, 1));
        connect();

        assertThat(remoteRead(0)).isInstanceOfSatisfying(StreamForwardError.ReadForwardFailed.class,
                                                         cause -> assertThat(cause.message()).contains(StreamError.General.STREAM_CONFIG_COMMIT_FAILED.message()));
        assertThat(answers.getLast().refusal()).isEqualTo(Option.none());
        assertThat(routedRead(0)).as("a failure the owner reported only as text still degrades to the local read, as before")
                                 .isSameAs(StreamError.General.PARTITION_NOT_LOCAL);
    }

    // ---- the owner promotion gate's probe (#1108) ---------------------------------------------------------------------

    /// What the "Forwarded read failed ... Stream partition is not owned by this node" bursts of #1108 are: the owner promotion
    /// gate probing every live peer at offset 0 over the catch-up class, a peer that holds no ring answering
    /// `PARTITION_NOT_LOCAL`. The gate reads that as "holds nothing" (`-1`) and the activation goes on; the backfill's probe, which
    /// has no committed replica set to say the peer should hold it, still reads it as no information. Both readings must survive the
    /// typed refusal: they were taken from the text, and the cause is rebuilt as the enum constant now.
    @Test
    void notOwnedProbe_isMinusOneForTheGate_andNoInformationForTheBackfillProbe() {
        owner.placementRoleSupplier((_, _) -> Role.NONE);
        owner.onStreamConfigPut(configPut(config(STREAM, 1)));
        connect();

        assertThat(OwnerPeerReads.appendedWatermark(client::readRemoteCatchup, OWNER_ID, STREAM, PARTITION, 100).await().unwrap()).as("the gate's probe").isEqualTo(-1L);
        assertThat(OwnerPeerReads.replicaWatermark(client::readRemoteCatchup, OWNER_ID, STREAM, PARTITION, 100).await().isFailure()).as("the backfill's probe").isTrue();
    }

    /// A peer whose ring has rolled past offset 0 answers `CursorExpired` naming its oldest offset; the probe resumes there and
    /// reports the head. The resume is parsed from the cause's text, which the rebuilt cause renders as the owner's did.
    @Test
    void expiredProbe_resumesAtTheOldestOffsetAndReportsTheHead() {
        rolledStreamOnOwner();
        connect();
        var head = owner.partitionBuffer(STREAM, PARTITION).or(() -> fail("owner ring")).headOffset();

        assertThat(OwnerPeerReads.appendedWatermark(client::readRemoteCatchup, OWNER_ID, STREAM, PARTITION, 100).await().unwrap()).isEqualTo(head);
    }

    // ---- what still degrades to the local read ------------------------------------------------------------------------

    /// A node that holds an (empty) ring and is not a caught-up replica forwards a `NEAREST` read to the owner when its own ring
    /// has nothing. An owner that does not hold the partition either is an ownership view that disagrees with this node's (#1108):
    /// the forward degrades to the local read, whose empty answer is the honest one, instead of failing the read.
    @Test
    void nearestRead_whenTheTargetOfTheForwardIsNotTheOwner_stillServesTheLocalRing() {
        owner.placementRoleSupplier((_, _) -> Role.NONE);
        owner.onStreamConfigPut(configPut(config(STREAM, 1)));
        caller.placementRoleSupplier((_, _) -> Role.REPLICA);
        callerHoldsOnlyTheConfig(config(STREAM, 1));
        connect();
        var nearest = routerWithRegistry(client);

        assertThat(nearest.read(STREAM, PARTITION, 0, 10, ReadPreference.NEAREST).await().isSuccess()).isTrue();
    }

    /// The same degradation when the owner cannot be asked at all: a forward that times out is not the owner's answer.
    @Test
    void nearestRead_whenTheOwnerDoesNotAnswer_stillServesTheLocalRing() {
        caller.placementRoleSupplier((_, _) -> Role.REPLICA);
        callerHoldsOnlyTheConfig(config(STREAM, 1));
        var silent = streamForwardClient(CALLER_ID, (_, _) -> {}, TimeSpan.timeSpan(100).millis());

        assertThat(routerWithRegistry(silent).read(STREAM, PARTITION, 0, 10, ReadPreference.NEAREST).await().isSuccess()).isTrue();
    }

    private StreamReadRouter routerWithRegistry(StreamForwardClient forwardClient) {
        return StreamReadRouter.streamReadRouter(caller,
                                                 Option.some(ReplicaRegistry.replicaRegistry()),
                                                 Option.some(forwardClient),
                                                 CALLER_ID,
                                                 (_, _) -> Option.some(OWNER_ID),
                                                 StreamReadForwardMetrics.NOOP);
    }

    // ---- the text on the wire (#2105) ---------------------------------------------------------------------------------

    /// A refusal travels as a typed code plus the client-safe rendering, never the cause chain: the owner logs the chain and the
    /// caller, who echoes the text into a client body, never receives it.
    @Test
    void theTextOnTheWire_isTheClientSafeRendering_neverTheCauseChain() {
        owner.createStream(config(STREAM, 1)).onFailure(cause -> fail(cause.message()));
        owner.placementRoleSupplier((_, _) -> Role.OWNER);
        owner.ownerServeGate((_, _) -> HttpError.httpError(HttpStatus.BAD_GATEWAY, new Chained("top-level failure", Causes.cause("deep internal detail")))
                                                .<Unit> result());
        callerHoldsOnlyTheConfig(config(STREAM, 1));
        connect();

        remoteRead(0);

        assertThat(answers.getLast().errorMessage()).contains("top-level failure").doesNotContain("deep internal detail");
    }

    private record Chained(String message, Cause deeper) implements Cause {
        @Override
        public Option<Cause> source() {
            return Option.some(deeper);
        }
    }

    // ---- fixtures -----------------------------------------------------------------------------------------------------

    private Cause remoteRead(long from) {
        return remoteReadOf(PARTITION, from);
    }

    private Cause remoteReadOf(int partition, long from) {
        return causeOf(client.readRemote(OWNER_ID, STREAM, partition, from, 10).await());
    }

    private Cause routedRead(long from) {
        return routedReadOf(PARTITION, from);
    }

    private Cause routedReadOf(int partition, long from) {
        return causeOf(callerRouter.read(STREAM, partition, from, 10, ReadPreference.GOVERNOR).await());
    }

    private Cause remoteLinearizableRead() {
        return causeOf(client.readRemote(OWNER_ID, STREAM, PARTITION, 0L, 10, ReadPreference.LINEARIZABLE).await());
    }

    private Cause routedLinearizableRead() {
        return causeOf(callerRouter.read(STREAM, PARTITION, 0L, 10, ReadPreference.LINEARIZABLE).await());
    }

    private static <T> Cause causeOf(Result<T> result) {
        return result.fold(cause -> cause,
                           value -> {
                               fail("expected a refusal, got " + value);

                               return null;
                           });
    }

    private static Cause failureOf(Result<?> result) {
        return causeOf(result);
    }

    private void rolledStreamOnOwner() {
        owner.createStream(config(STREAM, 1, 5)).onFailure(cause -> fail(cause.message()));
        for (var i = 0; i < 30; i++) {
            owner.publishLocal(STREAM, PARTITION, ("event-" + i).getBytes(UTF_8), 1000L + i).onFailure(cause -> fail(cause.message()));
        }
    }

    private void publishedOnOwner(int count) {
        owner.createStream(config(STREAM, 1)).onFailure(cause -> fail(cause.message()));
        for (var i = 0; i < count; i++) {
            owner.publishLocal(STREAM, PARTITION, ("event-" + i).getBytes(UTF_8), 1000L + i).onFailure(cause -> fail(cause.message()));
        }
    }

    /// The owner at epoch E2 whose epoch began at offset 3, over five records; a cursor of E1 past 3 is diverged.
    private void ownerCommittedAtEpochTwo() {
        publishedOnOwner(5);
        owner.ownershipRecords((_, _) -> Option.some(new StreamPartitionOwnershipValue(OWNER_ID,
                                                                                       E2,
                                                                                       2L,
                                                                                       HlcTimestamp.ZERO,
                                                                                       List.of(OWNER_ID),
                                                                                       3L,
                                                                                       false,
                                                                                       List.of(),
                                                                                       List.of(new EpochStart(E1, 0L), new EpochStart(E2, 3L)))));
    }

    private void callerHoldsOnlyTheConfig(StreamConfig config) {
        caller.onStreamConfigPut(configPut(config));
    }

    private Option<LinearizableOwnerServe<OffHeapRingBuffer.RawEvent>> linearizable(Option<CommittedStreamOwnerSource> committed,
                                                                                    Option<OwnershipEpochHighWater> highWater,
                                                                                    Option<ReplicaRegistry> registry,
                                                                                    Option<LinearizableBarrier> barrier) {
        return Option.some(LinearizableOwnerServe.linearizableOwnerServe(OWNER_ID,
                                                                         registry,
                                                                         committed,
                                                                         highWater,
                                                                         barrier,
                                                                         (stream, partition, from, max) -> owner.readServing(stream, partition, from, max)
                                                                                                                .async()));
    }

    private static Option<CommittedStreamOwnerSource> committedAt(NodeId node, Epoch epoch) {
        CommittedStreamOwnerSource source = (_, _) -> Option.some(new CommittedOwner(node, epoch));

        return Option.some(source);
    }

    private static StreamConfig config(String name, int partitions) {
        return config(name, partitions, 100);
    }

    private static StreamConfig config(String name, int partitions, long retainedEvents) {
        return StreamConfig.streamConfig(name,
                                         partitions,
                                         RetentionPolicy.retentionPolicy(retainedEvents, 64 * 1024L, 3_600_000L),
                                         "earliest",
                                         1_048_576L,
                                         ConsistencyMode.EVENTUAL,
                                         1,
                                         1,
                                         StreamCompression.NONE,
                                         Option.none());
    }

    private static ValuePut<StreamConfigKey, StreamConfigValue> configPut(StreamConfig config) {
        return new ValuePut<>(new KVCommand.Put<>(StreamConfigKey.streamConfigKey(config.name()), StreamConfigValue.streamConfigValue(config)),
                              Option.none());
    }

    private static KVStore<AetherKey, AetherValue> emptyStore() {
        return new KVStore<>(MessageRouter.mutable(), new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        }, new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        });
    }
}
