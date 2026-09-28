// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.LongStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.PartitionRecoveryReason;
import org.pragmatica.aether.slice.kvstore.AetherValue.PartitionRecoveryReasonKind;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionRecoveryValue;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.provenance.LogProvenance;
import org.pragmatica.aether.stream.provenance.PartitionFlags;
import org.pragmatica.aether.stream.provenance.ProvenanceComparison;
import org.pragmatica.aether.stream.provenance.ProvenanceEntry;
import org.pragmatica.aether.stream.segment.SealedSegment;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.aether.stream.segment.SegmentReader;
import org.pragmatica.aether.stream.segment.StorageSegmentSink;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.storage.AppendLog;
import org.pragmatica.storage.LocalDiskTier;
import org.pragmatica.storage.StorageInstance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.replication.GovernorFailoverHandler.governorFailoverHandler;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.WatermarkTracker.watermarkTracker;
import static org.pragmatica.aether.stream.segment.SegmentReader.segmentReader;
import static org.pragmatica.aether.stream.segment.StorageSegmentSink.storageSegmentSink;

/// #1596 (D): a sealed segment carries its sealing log's owner-epoch slice, and segment replay installs it -- N13
/// first -- before appending records past this copy's head; a segment without one falls back to `UNKNOWN(d)`.
///
/// The fixture: node N holds e1 records 0..4 and e2 records 5..9 (history e1@0, e2@5). The e3 owner, whose log
/// history is e1@0, e2@5, e3@10, sealed 10..14 into the tier. Q is the deposed e2 owner, whose unacked tail 10..16
/// was written at e2. N replays the tier's 10..14. Each test names the mutation that reddens it.
class SegmentReplayProvenanceTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final long ONE_GB = 1024 * 1024 * 1024L;
    private static final Epoch E1 = Epoch.epoch(1, 0);
    private static final Epoch E2 = Epoch.epoch(2, 0);
    private static final Epoch E3 = Epoch.epoch(3, 0);
    private static final List<ProvenanceEntry> OWNER_HISTORY = List.of(at(E1, 0), at(E2, 5), at(E3, 10));
    private static final LogProvenance OWNER = LogProvenance.logProvenance(0, 0, 14, OWNER_HISTORY);
    private static final LogProvenance DEPOSED_Q = LogProvenance.logProvenance(0, 0, 16, List.of(at(E1, 0), at(E2, 5)));

    @TempDir
    Path replicaWal;

    @TempDir
    Path ownerWal;

    @TempDir
    Path tierDir;

    private StorageInstance storage;
    private SegmentIndex index;
    private StorageSegmentSink sink;
    private SegmentReader reader;
    private StreamPartitionManager node;
    private final List<PartitionRecoveryReason> raised = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        storage = StorageInstance.storageInstance("tier", List.of(LocalDiskTier.localDiskTier(tierDir, ONE_GB).unwrap()));
        index = new SegmentIndex();
        sink = storageSegmentSink(storage, index);
        reader = segmentReader(storage, index);
        node = streamPartitionManager(Long.MAX_VALUE, Option.some(replicaWal));
        node.createStream(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));
        node.partitionFlags(recordingFlags());
    }

    @AfterEach
    void tearDown() {
        node.close();
        storage.shutdown();
    }

    /// The F1 shape through the tier: with the slice, N's replayed range is e3's, so N and the deposed Q are two
    /// histories. Without it (the base, or a replay that ignores the slice) N would read those records as e2 and
    /// compare CONSISTENT with Q -- the false CONSISTENT this closes. Red under "replay ignores the slice" and
    /// under "the sealer omits the slice".
    @Test
    void replayWithTheSlice_divergesFromTheDeposedOwnersTail_andAgreesWithTheSealer() {
        holdE1ThenE2();
        sealByTheE3Owner();

        replay();

        assertThat(node.nextExpectedOffset(STREAM, PARTITION)).isEqualTo(15L);
        var copy = node.localProvenance(STREAM, PARTITION).unwrap().unwrap();

        assertThat(copy.history()).containsExactlyElementsOf(OWNER_HISTORY);
        assertThat(ProvenanceComparison.firstDivergence(copy, DEPOSED_Q, 0, 14)).isEqualTo(Option.some(10L));
        assertThat(ProvenanceComparison.diverge(copy, OWNER)).isFalse();
        assertThat(raised).as("a normal lagging replay with a slice raises nothing").isEmpty();
    }

    /// A segment sealed without a slice (before this change, or without a log): what lands past the head is
    /// `UNKNOWN(d)`, which diverges from every other copy, and `HISTORY_INCOMPLETE` is raised for this copy.
    @Test
    void replayWithoutASlice_recordsUnknown_andFlags() {
        holdE1ThenE2();
        sealWithoutASlice();

        replay();

        var copy = node.localProvenance(STREAM, PARTITION).unwrap().unwrap();

        assertThat(ProvenanceComparison.firstDivergence(copy, OWNER, 0, 14)).isEqualTo(Option.some(10L));
        assertThat(ProvenanceComparison.firstDivergence(copy, DEPOSED_Q, 0, 14)).isEqualTo(Option.some(10L));
        assertThat(raised).extracting(PartitionRecoveryReason::kind)
                          .containsExactly(PartitionRecoveryReasonKind.HISTORY_INCOMPLETE);
    }

    /// N13 through replay: N holds e1 records where the sealer's history says e2 began at 5. Nothing is appended,
    /// and `MARKED_DIVERGED` is raised.
    @Test
    void replayOverAMismatchingPrefix_appendsNothing_andFlags() {
        LongStream.range(0, 10).forEach(offset -> live(offset, E1));
        sealByTheE3Owner();

        replay();

        assertThat(node.nextExpectedOffset(STREAM, PARTITION)).isEqualTo(10L);
        assertThat(raised).extracting(PartitionRecoveryReason::kind)
                          .containsExactly(PartitionRecoveryReasonKind.MARKED_DIVERGED);
    }

    private void holdE1ThenE2() {
        LongStream.range(0, 5).forEach(offset -> live(offset, E1));
        LongStream.range(5, 10).forEach(offset -> live(offset, E2));
    }

    private void live(long offset, Epoch epoch) {
        node.appendRecovered(STREAM, PARTITION, offset, ("r" + offset).getBytes(), 1L, epoch)
            .onFailure(cause -> fail(cause.message()));
    }

    private void sealByTheE3Owner() {
        var log = AppendLog.open(ownerWal.resolve("owner.wal")).unwrap();

        OWNER_HISTORY.forEach(entry -> log.recordEpochStart(entry.key().unwrap(), entry.startOffset(), ProvenanceEntry.ORDER)
                                          .onFailure(cause -> fail(cause.message())));
        sink.seal(segment(), Option.some(log)).await().onFailure(cause -> fail(cause.message()));
        log.close();
    }

    private void sealWithoutASlice() {
        sink.seal(segment(), Option.none()).await().onFailure(cause -> fail(cause.message()));
    }

    private void replay() {
        governorFailoverHandler(replicaRegistry(), node.alignedRecovery(), node::syncReplicated)
            .handleFailover(STREAM, PARTITION, watermarkTracker(), index, reader)
            .await();
    }

    private static SealedSegment segment() {
        var buffer = ByteBuffer.allocate(5 * (Long.BYTES + Long.BYTES + Integer.BYTES + 3)).order(ByteOrder.BIG_ENDIAN);

        for (var offset = 10L; offset <= 14; offset++) {
            buffer.putLong(offset).putLong(1L).putInt(3).put(("s" + offset).getBytes());
        }

        return SealedSegment.sealedSegment(STREAM, PARTITION, 10, 14, 5, 1L, 1L, buffer.array());
    }

    private static ProvenanceEntry at(Epoch epoch, long start) {
        return ProvenanceEntry.provenanceEntry(epoch, start);
    }

    private PartitionFlags recordingFlags() {
        return new PartitionFlags() {
            @Override
            public Promise<PartitionFlag> raise(String stream, int partition, PartitionRecoveryReason reason) {
                raised.add(reason);

                return Promise.success(new PartitionFlag(StreamPartitionRecoveryValue.raised(Option.none(), reason), "d"));
            }

            @Override
            public Option<PartitionFlag> status(String stream, int partition) {
                return Option.none();
            }

            @Override
            public PartitionRecoveryReason local(PartitionRecoveryReasonKind kind, String evidence) {
                return PartitionRecoveryReason.partitionRecoveryReason(kind, Option.some("N"), evidence);
            }
        };
    }
}
