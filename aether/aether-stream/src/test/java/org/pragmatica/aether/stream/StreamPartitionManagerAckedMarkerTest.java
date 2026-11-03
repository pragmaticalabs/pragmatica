// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.aether.stream.segment.SealerThresholds;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.aether.stream.segment.SegmentSealer;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.LocalDiskTier;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.MetadataStore;
import org.pragmatica.storage.StorageInstance;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationManager.replicationManager;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateAck.replicateAck;
import static org.pragmatica.aether.stream.segment.StorageSegmentSink.storageSegmentSink;

/// PR-2f (#2201 B-04/B-17, I-05): the owner's acked marker. Before a restart the owner's acked tail was visible; after it
/// the replayed tail is durable and NOT visible until a replica re-acks, because nothing about acks is persisted. The
/// marker is the owner's record of how far `visible` reached, written on the tick and at close, and trusted only at the
/// owner-admission edge (know `6665558916`, S1): after the owner gate admits, the committed record names this node, and
/// the log's own epoch history attributes the marker's offset to the marker's epoch.
///
/// Fixture: min-sync 2 with one peer, so `visible = min(durable, peer ack)`; a ring large enough that nothing is evicted,
/// so the sealed floor stays `-1` and only the marker can raise `visible` after the restart. The ownership record names
/// this node under `E1` before the restart and under the restarted epoch after it.
@Timeout(60)
class StreamPartitionManagerAckedMarkerTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId SELF = NodeId.randomNodeId();
    private static final NodeId PEER = NodeId.randomNodeId();
    private static final long ONE_GB = 1024 * 1024 * 1024L;
    private static final Epoch E1 = Epoch.epoch(1L, 1L, 1L);
    private static final Result<Unit> GATE_REFUSES = new StreamError.OwnerNotActivated(STREAM,
                                                                                                          PARTITION).result();

    @TempDir
    Path walDir;

    @TempDir
    Path killedWalDir;

    @TempDir
    Path storageDir;

    private MetadataStore metadataStore;
    private StorageInstance storage;
    private ReplicationManager replication;
    private StreamPartitionManager manager;
    private final AtomicReference<StreamPartitionOwnershipValue> committed = new AtomicReference<>();
    private final AtomicReference<Result<Unit>> gate = new AtomicReference<>(Result.unitResult());
    private final AtomicReference<Option<NodeId>> remoteOwner = new AtomicReference<>(Option.none());

    @BeforeEach
    void setUp() {
        metadataStore = MetadataStore.inMemoryMetadataStore("acked-marker");
        storage = StorageInstance.storageInstance("acked-marker",
                                                  List.of(MemoryTier.memoryTier(ONE_GB),
                                                          LocalDiskTier.localDiskTier(storageDir, ONE_GB).unwrap()),
                                                  metadataStore);
        committed.set(StreamPartitionOwnershipValue.streamPartitionOwnershipValue(SELF, E1, 1L, HlcTimestamp.ZERO)
                                                   .withEpochStart(0L));
    }

    @AfterEach
    void tearDown() {
        Option.option(manager).onPresent(StreamPartitionManager::close);
        Option.option(storage).onPresent(StorageInstance::shutdown);
    }

    // ---- NT-41: a kill within one tick of the last ack ----------------------------------------------------------------

    /// NT-41 tripwire (today): nothing about acks survives the restart, so after the first served read the owner's
    /// visible position is still `-1` -- the acked prefix 0..4 waits for a replica re-ack. Delete when PR-2f lands and
    /// enable [#killWithinOneTickOfTheLastAck_restoresTheTickedMarker_andNoUnackedRecord].
    @Test
    void killWithinOneTickOfTheLastAck_tripwire_holdsTheWholeTailInvisible() {
        killAfterTickedAckAt4_thenAckThrough7();
        assertThat(visible()).as("control: nothing raises visible at rebuild").isEqualTo(-1L);
        servedRead();
        assertThat(visible()).as("PR-2f landed? The ticked marker (4) is restored at the owner-admission edge. Delete this tripwire and "
                                 + "enable killWithinOneTickOfTheLastAck_restoresTheTickedMarker_andNoUnackedRecord")
                             .isEqualTo(-1L);
    }

    /// NT-41 real: the marker written by the tick covers 0..4; the acks for 5..7 came after the last tick and the kill
    /// lost them, so those records wait for a re-ack (the bounded fallback), and 8..9 -- never acked -- stay invisible.
    @Test
    @Disabled("NT-41 (PR-2f): enable when the owner's acked marker is restored at the owner-admission edge; RED today: visible stays -1")
    void killWithinOneTickOfTheLastAck_restoresTheTickedMarker_andNoUnackedRecord() {
        killAfterTickedAckAt4_thenAckThrough7();
        assertThat(visible()).as("never raised at ring rebuild, before any owner admission (S1)").isEqualTo(-1L);
        servedRead();
        assertThat(visible()).as("the ticked marker, not the acks the kill lost").isEqualTo(4L);
        assertThat(durable()).as("control: the whole tail is durable").isEqualTo(9L);
        replication.handleAck(replicateAck(PEER, STREAM, PARTITION, 7L));
        assertThat(visible()).as("the re-ack releases what the kill lost, and no further").isEqualTo(7L);
    }

    // ---- NT-111 (iii): the owner gate refuses ------------------------------------------------------------------------

    /// NT-111 (iii) tripwire (today): neither a refused nor an admitted owner read raises visible. Delete when PR-2f lands
    /// and enable [#ownerGateRefusing_neverRaisesFromTheMarker_untilItAdmits].
    @Test
    void ownerGateRefusing_tripwire_neverRaises_andNeitherDoesAnAdmission() {
        restartWithMarkerAt4();
        gate.set(GATE_REFUSES);
        refusedRead();
        assertThat(visible()).as("control: a refused gate raises nothing").isEqualTo(-1L);
        gate.set(Result.unitResult());
        servedRead();
        assertThat(visible()).as("PR-2f landed? An admitted owner read restores the marker. Delete this tripwire and enable "
                                 + "ownerGateRefusing_neverRaisesFromTheMarker_untilItAdmits")
                             .isEqualTo(-1L);
    }

    /// NT-111 (iii) real: while the owner gate refuses, the marker is not read; the first admission raises to it.
    @Test
    @Disabled("NT-111 iii (PR-2f): enable when the acked marker is restored at the owner-admission edge; RED today: visible stays -1 after the admission")
    void ownerGateRefusing_neverRaisesFromTheMarker_untilItAdmits() {
        restartWithMarkerAt4();
        gate.set(GATE_REFUSES);
        refusedRead();
        refusedRead();
        assertThat(visible()).as("a refused owner gate never reads the marker").isEqualTo(-1L);
        gate.set(Result.unitResult());
        servedRead();
        assertThat(visible()).as("the first admission restores the marker").isEqualTo(4L);
    }

    // ---- NT-111 (iv): the committed record names another node ---------------------------------------------------------

    /// NT-111 (iv) tripwire (today): an admitted read raises nothing, with or without a remote committed owner. Delete when
    /// PR-2f lands and enable [#remoteCommittedOwner_neverRaisesFromTheMarker_untilTheRecordNamesThisNode].
    @Test
    void remoteCommittedOwner_tripwire_neverRaises_andNeitherDoesSelf() {
        restartWithMarkerAt4();
        remoteOwner.set(Option.some(PEER));
        servedRead();
        assertThat(visible()).as("control: a remote committed owner raises nothing").isEqualTo(-1L);
        remoteOwner.set(Option.none());
        servedRead();
        assertThat(visible()).as("PR-2f landed? An admitted read with the record naming this node restores the marker. Delete this "
                                 + "tripwire and enable remoteCommittedOwner_neverRaisesFromTheMarker_untilTheRecordNamesThisNode")
                             .isEqualTo(-1L);
    }

    /// NT-111 (iv) real: an admitted owner read while the committed record names another node does not trust the marker;
    /// once the record names this node, the next admission does.
    @Test
    @Disabled("NT-111 iv (PR-2f): enable when the acked marker is restored at the owner-admission edge; RED today: visible stays -1")
    void remoteCommittedOwner_neverRaisesFromTheMarker_untilTheRecordNamesThisNode() {
        restartWithMarkerAt4();
        remoteOwner.set(Option.some(PEER));
        servedRead();
        servedRead();
        assertThat(visible()).as("a record naming another node never trusts the marker").isEqualTo(-1L);
        remoteOwner.set(Option.none());
        servedRead();
        assertThat(visible()).as("the record names this node: the marker is trusted").isEqualTo(4L);
    }

    // ---- scenarios ----------------------------------------------------------------------------------------------------

    /// Publish 0..9; the peer acks 4; one tick; the peer acks 7; then the node is KILLED: its files are copied as they
    /// are (no tick, no close) and a new owner starts over the copy.
    private void killAfterTickedAckAt4_thenAckThrough7() {
        startOwner(walDir);
        publish(10);
        replication.handleAck(replicateAck(PEER, STREAM, PARTITION, 4L));
        assertThat(visible()).as("control: min(durable 9, acked 4)").isEqualTo(4L);
        manager.repeatVisible();
        replication.handleAck(replicateAck(PEER, STREAM, PARTITION, 7L));
        assertThat(visible()).as("control: the later ack moved visible past the ticked value").isEqualTo(7L);
        copyTree(walDir, killedWalDir);
        manager.close();
        restartOn(killedWalDir);
    }

    /// Publish 0..9; the peer acks 4; one tick; a graceful restart.
    private void restartWithMarkerAt4() {
        startOwner(walDir);
        publish(10);
        replication.handleAck(replicateAck(PEER, STREAM, PARTITION, 4L));
        manager.repeatVisible();
        manager.close();
        restartOn(walDir);
    }

    // ---- fixture ------------------------------------------------------------------------------------------------------

    private void restartOn(Path dir) {
        committed.set(committed.get().restarted(10L, HlcTimestamp.ZERO));
        startOwner(dir);
    }

    private void startOwner(Path dir) {
        var index = new SegmentIndex();

        index.rebuildFromRefs(metadataStore);
        replication = replicationWithPeer();
        manager = streamPartitionManager(Long.MAX_VALUE,
                                         segmentSealer(index),
                                         replication,
                                         Option.some(dir),
                                         index::lastSealedOffset);
        manager.ownerEpochSource((_, _) -> committed.get().ownerEpoch());
        manager.ownershipRecords((_, _) -> Option.option(committed.get()));
        manager.ownerWriteAdmission((_, _) -> remoteOwner.get());
        manager.ownerServeGate((_, _) -> gate.get());
        manager.createStream(config()).onFailure(cause -> fail(cause.message()));
    }

    private SegmentSealer segmentSealer(SegmentIndex index) {
        return SegmentSealer.segmentSealer(storageSegmentSink(storage, index),
                                           SegmentSealer.DEFAULT_PENDING_CAP_BYTES,
                                           SealerThresholds.immediate());
    }

    private static ReplicationManager replicationWithPeer() {
        ReplicaRegistry registry = replicaRegistry();

        registry.registerReplica(STREAM, PARTITION, SELF);
        registry.registerReplica(STREAM, PARTITION, PEER);

        return replicationManager(SELF, registry);
    }

    private static StreamConfig config() {
        return StreamConfig.streamConfig(STREAM,
                                         1,
                                         RetentionPolicy.retentionPolicy(1_000L, 1_048_576L, 60_000L),
                                         "earliest",
                                         1_048_576L,
                                         ConsistencyMode.EVENTUAL,
                                         3,
                                         2,
                                         StreamCompression.NONE,
                                         Option.none());
    }

    private void servedRead() {
        manager.readServing(STREAM, PARTITION, 0L, 10).onFailure(cause -> fail("owner read refused: " + cause.message()));
    }

    private void refusedRead() {
        manager.readServing(STREAM, PARTITION, 0L, 10).onSuccess(_ -> fail("control: the owner gate refuses this read"));
    }

    private long visible() {
        return manager.partitionBuffer(STREAM, PARTITION).map(OffHeapRingBuffer::visibleOffset).or(Long.MIN_VALUE);
    }

    private long durable() {
        return manager.partitionBuffer(STREAM, PARTITION).map(OffHeapRingBuffer::durableOffset).or(Long.MIN_VALUE);
    }

    private void publish(int count) {
        for (var i = 0; i < count; i++) {
            manager.publishLocal(STREAM, PARTITION, ("e-" + i).getBytes(UTF_8), 1L)
                   .onFailure(cause -> fail("publish failed: " + cause.message()));
        }
    }

    private static void copyTree(Path from, Path to) {
        try (Stream<Path> paths = Files.walk(from)) {
            paths.forEach(path -> copyOne(from, to, path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void copyOne(Path from, Path to, Path path) {
        var target = to.resolve(from.relativize(path).toString());

        try {
            if (Files.isDirectory(path)) {
                Files.createDirectories(target);
            } else {
                Files.copy(path, target);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
