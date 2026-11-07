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
import java.util.zip.CRC32;

import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.provenance.ProvenanceEntry;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicaSetController;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.aether.stream.segment.SealerThresholds;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.aether.stream.segment.SegmentSealer;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.AppendLog;
import org.pragmatica.storage.LocalDiskTier;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.MetadataStore;
import org.pragmatica.storage.StorageInstance;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
    private final AtomicReference<ReplicaSetController.Role> role = new AtomicReference<>(ReplicaSetController.Role.OWNER);

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

    /// NT-41 real: the marker written by the tick covers 0..4; the acks for 5..7 came after the last tick and the kill
    /// lost them, so those records wait for a re-ack (the bounded fallback), and 8..9 -- never acked -- stay invisible.
    @Test
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

    /// NT-111 (iii) real: while the owner gate refuses, the marker is not read; the first admission raises to it.
    @Test
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

    /// NT-111 (iv) real: an admitted owner read while the committed record names another node does not trust the marker;
    /// once the record names this node, the next admission does.
    @Test
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

    // ---- NT-111 (i), (ii) and the A-15 shape: never raised --------------------------------------------------------------

    /// NT-111 (i): while the role is NONE -- the role unresolved, with the transient owner rule -- an admitted owner check
    /// never reads the marker; the marker stays pending, and the first admission as OWNER restores it.
    @Test
    void noneRole_neverRaisesFromTheMarker_evenWhenTheGateAdmits() {
        restartWithMarkerAt4();
        role.set(ReplicaSetController.Role.NONE);
        assertThat(manager.mayServeAsOwner(STREAM, PARTITION)).as("control: the gate admits").isTrue();
        assertThat(manager.mayServeAsOwner(STREAM, PARTITION)).isTrue();
        assertThat(visible()).as("the NONE role never raises visible from the marker").isEqualTo(-1L);
        role.set(ReplicaSetController.Role.OWNER);
        servedRead();
        assertThat(visible()).as("positive control: pending, not consumed").isEqualTo(4L);
    }

    /// NT-111 (ii): a marker whose offset the epoch history does not attribute to the marker's epoch is never trusted, and
    /// it is consumed: no later admission reads it again.
    @Test
    void unattributedMarker_isNeverTrusted() throws IOException {
        restartWithMarkerAt4();
        manager.close();
        manager = null;
        Files.writeString(walFile(walDir).resolveSibling(PARTITION + ".acked"), validMarkerLine("999", 4L));
        restartOn(walDir);
        servedRead();
        servedRead();
        assertThat(visible()).as("an epoch the history does not attribute 4 to").isEqualTo(-1L);
    }

    /// The committed record must be present: with none (the cold-start window, HRW routing alone) the marker stays pending
    /// and is not read; once a record naming this node commits, the next admission restores it.
    @Test
    void noCommittedRecord_neverRaisesFromTheMarker_untilOneCommits() {
        restartWithMarkerAt4();

        var record = committed.getAndSet(null);

        servedRead();
        assertThat(visible()).as("no committed record: not trusted, not consumed").isEqualTo(-1L);
        committed.set(record);
        servedRead();
        assertThat(visible()).isEqualTo(4L);
    }

    /// [StreamPartitionManager#mayServeAsOwner] is an owner-admission site too.
    @Test
    void mayServeAsOwner_restoresTheMarker() {
        restartWithMarkerAt4();
        assertThat(manager.mayServeAsOwner(STREAM, PARTITION)).isTrue();
        assertThat(visible()).isEqualTo(4L);
    }

    /// NT-111 (ii), same rank: a key of the marker's own epoch rank but another incarnation passes the lineage check and
    /// fails attribution alone -- the history attributes 4 to the key it recorded, not to this one.
    @Test
    void markerOfTheSameEpochRankUnderAnotherKey_isNeverTrusted() throws IOException {
        restartWithMarkerAt4();
        manager.close();
        manager = null;

        var foreign = ProvenanceEntry.provenanceEntry(E1, Option.some("01JFOREIGNINCARNATION0000000"), 0L).key().unwrap().token();

        Files.writeString(walFile(walDir).resolveSibling(PARTITION + ".acked"), validMarkerLine(foreign, 4L));
        restartOn(walDir);
        servedRead();
        assertThat(visible()).as("the history attributes 4 to another key").isEqualTo(-1L);
    }

    /// A log with no marker (an older build's) is never given one by its own seed: the tick after the restart writes none
    /// until visible moves.
    @Test
    void restartOverALogWithNoMarker_tickWritesNoMarkerFromTheSeed() throws IOException {
        startOwner(walDir);
        publish(10);
        manager.close();
        manager = null;
        Files.delete(walFile(walDir).resolveSibling(PARTITION + ".acked"));
        restartOn(walDir);
        manager.repeatVisible();
        assertThat(Files.exists(walFile(walDir).resolveSibling(PARTITION + ".acked"))).as("the seed is not an ack").isFalse();
        replication.handleAck(replicateAck(PEER, STREAM, PARTITION, 3L));
        manager.repeatVisible();
        assertThat(markerOffset()).as("positive control: a real advance is written").isEqualTo(Option.some(3L));
    }

    /// The A-15 shape at the admission edge: the committed record's lineage has an epoch AFTER the marker's that began at or
    /// below the marker's offset (another owner wrote from offset 2 on), so the marker's prefix is not in the live lineage
    /// and is never exposed, even with the record naming this node and the gate admitting.
    @Test
    void markerOutsideTheLiveLineage_isNeverTrusted() {
        startOwner(walDir);
        publish(10);
        replication.handleAck(replicateAck(PEER, STREAM, PARTITION, 4L));
        manager.repeatVisible();
        manager.close();
        committed.set(committed.get().restarted(2L, HlcTimestamp.ZERO));
        startOwner(walDir);
        servedRead();
        assertThat(visible()).as("a later epoch began at 2, below the marker's 4").isEqualTo(-1L);
    }

    /// Owner writes are an admission site too: the first publish after the restart restores the marker.
    @Test
    void firstOwnerWrite_restoresTheMarker() {
        restartWithMarkerAt4();
        assertThat(visible()).isEqualTo(-1L);
        publish(1);
        assertThat(visible()).isEqualTo(4L);
    }

    // ---- the writer (tick + close) and the open-time clamp: wiring pins ----------------------------------------------------

    @Test
    void tick_writesTheOwnersVisiblePosition_asTheAckedMarker_attributedToItsEpoch() {
        startOwner(walDir);
        publish(10);
        assertThat(markerOffset()).as("control: born at -1").isEqualTo(Option.some(-1L));
        replication.handleAck(replicateAck(PEER, STREAM, PARTITION, 4L));
        assertThat(markerOffset()).as("an ack alone writes nothing").isEqualTo(Option.some(-1L));
        manager.repeatVisible();
        assertThat(AppendLog.readAckedMarker(walFile(walDir)).flatMap(AppendLog.AckedMark::epoch).isPresent())
                  .as("the epoch the history attributes 4 to").isTrue();
        assertThat(markerOffset()).isEqualTo(Option.some(4L));
    }

    @Test
    void close_flushesTheAckedMarker_withoutATick() {
        startOwner(walDir);
        publish(10);
        replication.handleAck(replicateAck(PEER, STREAM, PARTITION, 6L));
        manager.close();
        manager = null;
        assertThat(markerOffset()).isEqualTo(Option.some(6L));
    }

    @Test
    void tick_onANodeThatIsNotTheOwner_writesNoMarker() {
        startOwner(walDir);
        publish(10);
        replication.handleAck(replicateAck(PEER, STREAM, PARTITION, 4L));
        role.set(ReplicaSetController.Role.NONE);
        manager.repeatVisible();
        manager.close();
        manager = null;
        assertThat(markerOffset()).as("neither the tick nor the close of a non-owner writes it").isEqualTo(Option.some(-1L));
    }

    @Test
    void tick_whoseMarkerWriteFails_isIgnored_andTheNextTickWritesIt() throws IOException {
        startOwner(walDir);
        publish(10);
        replication.handleAck(replicateAck(PEER, STREAM, PARTITION, 4L));

        var blocker = walFile(walDir).resolveSibling(PARTITION + ".acked.tmp");

        Files.createDirectories(blocker.resolve("blocks-the-temp-file"));
        manager.repeatVisible();
        assertThat(markerOffset()).as("control: the write failed").isEqualTo(Option.some(-1L));
        publish(1);
        assertThat(visible()).as("the partition keeps serving").isEqualTo(4L);
        Files.delete(blocker.resolve("blocks-the-temp-file"));
        Files.delete(blocker);
        manager.repeatVisible();
        assertThat(markerOffset()).isEqualTo(Option.some(4L));
    }

    @Test
    void restart_clampsAMarkerAboveTheRecoveredHead_toTheHead() throws IOException {
        startOwner(walDir);
        publish(10);
        manager.close();
        manager = null;
        Files.writeString(walFile(walDir).resolveSibling(PARTITION + ".acked"), validMarkerLine(50L));
        assertThat(markerOffset()).as("control: a marker above everything the log holds").isEqualTo(Option.some(50L));
        restartOn(walDir);
        assertThat(markerOffset()).as("clamped to the recovered head (9) at open, durably").isEqualTo(Option.some(9L));
    }

    /// A marker that could not be clamped is never restored in this incarnation (`forgoAckedRestore`), even though it is
    /// otherwise trusted: valid CRC, the log's real epoch token, the committed record naming this node. Without the forgo
    /// the unclamped marker (50) would raise visible to `min(durable, 50)` = 9 at the first admission.
    @Test
    void markerWhoseClampFailed_isNeverTrustedToRaiseVisible() throws IOException {
        restartWithMarkerAt4();
        var token = AppendLog.readAckedMarker(walFile(walDir)).flatMap(AppendLog.AckedMark::epoch).map(AppendLog.EpochKey::token)
                             .or("missing-token");

        manager.close();
        manager = null;
        var sidecar = walFile(walDir).resolveSibling(PARTITION + ".acked");

        Files.writeString(sidecar, validMarkerLine(token, 50L));
        Files.createDirectories(sidecar.resolveSibling(PARTITION + ".acked.tmp").resolve("blocks-the-clamp-rewrite"));
        restartOn(walDir);
        servedRead();
        servedRead();
        assertThat(markerOffset()).as("control: the clamp rewrite failed, the marker is still above the head").isEqualTo(Option.some(50L));
        assertThat(visible()).as("a marker that could not be clamped never raises visible").isEqualTo(-1L);
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
        manager.ownerEpochSource((_, _) -> Option.option(committed.get()).map(StreamPartitionOwnershipValue::ownerEpoch).or(Epoch.ZERO));
        manager.ownershipRecords((_, _) -> Option.option(committed.get()));
        manager.ownerWriteAdmission((_, _) -> remoteOwner.get());
        manager.ownerServeGate((_, _) -> gate.get());
        manager.placementRoleSupplier((_, _) -> role.get());
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

    private Option<Long> markerOffset() {
        return AppendLog.readAckedMarker(walFile(walDir)).map(AppendLog.AckedMark::offset);
    }

    private static Path walFile(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            return paths.filter(path -> path.getFileName().toString().equals(PARTITION + ".wal")).findFirst().orElseThrow();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// The sidecar's one line, `aether-append-log-acked v1 - <offset> <crc>`, with a valid CRC (the storage module's encoder
    /// is package-private).
    private static String validMarkerLine(long offset) {
        return validMarkerLine("-", offset);
    }

    private static String validMarkerLine(String epochToken, long offset) {
        var body = "aether-append-log-acked v1 " + epochToken + " " + offset;
        var crc = new CRC32();

        crc.update(body.getBytes(UTF_8));

        return body + " " + Long.toHexString(crc.getValue()) + "\n";
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
