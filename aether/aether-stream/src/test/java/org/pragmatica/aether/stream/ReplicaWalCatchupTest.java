// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.aether.stream.forward.AcceptingStreamForwardTransport;
import org.pragmatica.aether.stream.forward.RawEventDto;
import org.pragmatica.aether.stream.forward.StreamForwardError;
import org.pragmatica.aether.stream.forward.StreamForwardHandler;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.aether.stream.provenance.ProvenanceEntry;
import org.pragmatica.aether.stream.replication.PartitionBackfill;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicaSetController.Role;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.aether.stream.replication.ReplicationMessage.CatchupResponse;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.storage.AppendLog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForward.readForward;

/// #1759 (I-27): a replica that HOLDS a partition without a ring (paced, or deferred for off-heap budget) serves a registered
/// replica's catch-up read from its WAL, READ-ONLY: no ring is created and no budget is taken, the page is shipped with the
/// WAL's own owner-epoch history, and a damaged WAL answers unavailable (the held partition's watermark), never a partial
/// range. `held` has five events (payload `event-i` at offset i) in its WAL from a previous run and the epoch start
/// `E1 @ 0` in its history.
class ReplicaWalCatchupTest {
    private static final NodeId SELF = NodeId.randomNodeId();
    private static final NodeId PEER = NodeId.randomNodeId();
    private static final Epoch E1 = Epoch.epoch(0, 1, 0);
    private static final int PAGE = 16;
    private static final int EVENTS = 5;
    private static final int FRAME_HEADER_BYTES = 4 + 8 + 8 + 4;

    @TempDir
    Path walDir;

    private final ConcurrentHashMap<String, Role> roles = new ConcurrentHashMap<>();
    private final List<ReadForwardResponse> answers = new CopyOnWriteArrayList<>();

    @BeforeEach
    void writeFiveEventsInAPreviousRun() {
        var previous = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir), LastSealedOffsetSource.none());

        previous.createStream(config("held", 1)).onFailure(_ -> fail("previous run should create"));
        for (var i = 0; i < EVENTS; i++) {
            previous.publishLocal("held", 0, payload(i), 1000L + i).onFailure(cause -> fail(cause.message()));
        }
        previous.close();

        var wal = AppendLog.open(walFile()).unwrap();

        wal.recordEpochStart(ProvenanceEntry.provenanceEntry(E1, 0).key().unwrap(), 0, ProvenanceEntry.ORDER)
           .onFailure(cause -> fail(cause.message()));
        wal.close();
    }

    /// The forward handler of a node whose `reshuffle_concurrency` slots (2) are held by `busy[0]` and `busy[1]`, so `held[0]`
    /// is held but paced, and which knows PEER as a registered replica of `held[0]`.
    @Nested
    class ServedToARegisteredReplica {
        private StreamPartitionManager manager;
        private StreamForwardHandler handler;

        @BeforeEach
        void startWithBothSlotsBusy() {
            var registry = ReplicaRegistry.replicaRegistry();

            registry.registerReplica("held", 0, PEER);
            manager = streamPartitionManager(Long.MAX_VALUE,
                                             EvictionListener.NOOP,
                                             ReplicationManager.replicationManager(SELF, registry),
                                             Option.some(walDir),
                                             LastSealedOffsetSource.none());
            manager.placementRoleSupplier((stream, _) -> roles.getOrDefault(stream, Role.NONE));
            List.of(config("busy", 2), config("held", 1)).forEach(c -> manager.onStreamConfigPut(configPut(c)));
            roles.put("busy", Role.REPLICA);
            roles.put("held", Role.REPLICA);
            manager.materializePartition("busy", 0).onFailure(_ -> fail("first slot should materialize"));
            manager.materializePartition("busy", 1).onFailure(_ -> fail("second slot should materialize"));
            manager.materializePartition("held", 0).onSuccess(_ -> fail("held[0] must be paced"));
            handler = StreamForwardHandler.streamForwardHandler(SELF,
                                                                manager,
                                                                AcceptingStreamForwardTransport.accepting((_, message) -> record(message)));
        }

        @AfterEach
        void stop() {
            manager.close();
        }

        /// NT-149 (node level) and NT-151: the records come from the WAL with its own history, and the partition stays
        /// unmaterialized with the pool untouched.
        @Test
        void catchupRead_ofAHeldUnmaterializedPartition_isServedFromItsWal_withItsOwnHistory_andTakesNoRingOrBudget() {
            var allocated = manager.totalAllocatedBytes();

            var answer = catchupRead(0L, PAGE);

            assertThat(answer.success()).as("answer %s", answer.errorMessage()).isTrue();
            assertThat(answer.events()).extracting(RawEventDto::offset).containsExactly(0L, 1L, 2L, 3L, 4L);
            assertThat(answer.events().get(3).data()).isEqualTo(payload(3));
            assertThat(answer.events().get(3).timestamp()).isEqualTo(1003L);
            assertThat(answer.history()).isEqualTo(sidecarHistory());
            assertThat(answer.history()).extracting(ProvenanceEntry::rank).contains(E1);
            assertThat(manager.partitionBuffer("held", 0).isEmpty()).as("no ring is created").isTrue();
            assertThat(manager.totalAllocatedBytes()).as("no off-heap budget is taken").isEqualTo(allocated);
            assertThat(manager.heldNotMaterializedWatermark("held", 0)).isEqualTo(Option.some(4L));
        }

        /// The requested page size bounds the WAL scan: a page of two is two records, not the whole log.
        @Test
        void catchupRead_withAPageOfTwo_servesTwoRecordsOfTheWal() {
            var answer = catchupRead(0L, 2);

            assertThat(answer.success()).as("answer %s", answer.errorMessage()).isTrue();
            assertThat(answer.events()).extracting(RawEventDto::offset).containsExactly(0L, 1L);
        }

        @Test
        void catchupRead_fromAnOffset_servesTheRecordsFromThere() {
            var answer = catchupRead(3L, PAGE);

            assertThat(answer.success()).as("answer %s", answer.errorMessage()).isTrue();
            assertThat(answer.events()).extracting(RawEventDto::offset).containsExactly(3L, 4L);
        }

        /// NT-150: a damaged record inside the range answers the held partition's watermark (unavailable), never the valid
        /// prefix as if it were the log's end.
        @Test
        void catchupRead_ofAWalWithADamagedRecord_answersUnavailable_neverAPartialRange() throws Exception {
            var bytes = Files.readAllBytes(walFile());

            bytes[bytes.length - 2] ^= 0x5A;
            Files.write(walFile(), bytes);

            var answer = catchupRead(0L, PAGE);

            assertThat(answer.success()).isFalse();
            assertThat(answer.events()).isEmpty();
            assertThat(StreamError.PartitionHeldNotMaterialized.watermarkOf(new StreamForwardError.ReadForwardFailed(answer.errorMessage()))
                                  .isPresent())
                .as("named as the held partition, %s", answer.errorMessage())
                .isTrue();
        }

        /// A2 (CTO): a torn FINAL frame ends the log, as the held watermark counts it: the intact prefix is served.
        @Test
        void catchupRead_ofAWalWithATornTail_servesTheIntactPrefix() throws Exception {
            Files.write(walFile(), new byte[]{0, 0, 0, 40, 1, 2}, StandardOpenOption.APPEND);

            var answer = catchupRead(0L, PAGE);

            assertThat(answer.success()).as("answer %s", answer.errorMessage()).isTrue();
            assertThat(answer.events()).extracting(RawEventDto::offset).containsExactly(0L, 1L, 2L, 3L, 4L);
        }

        @Test
        void catchupRead_ofAWalWhoseLastPayloadIsCutShort_servesThePrefixUpToTheHeldWatermark() throws Exception {
            cutLastFrameShort();

            var answer = catchupRead(0L, PAGE);

            assertThat(answer.success()).as("answer %s", answer.errorMessage()).isTrue();
            assertThat(answer.events()).extracting(RawEventDto::offset).containsExactly(0L, 1L, 2L, 3L);
            assertThat(manager.heldNotMaterializedWatermark("held", 0)).as("the page ends at the watermark").isEqualTo(Option.some(3L));
        }

        /// A2 (CTO), the crash case: a replica whose WAL was torn by a crash and which restarted budget-deferred. The owner
        /// behind it targets the replica's valid head and activates there.
        @Test
        void ownerBehindAReplicaWithATornFinalFrame_catchesUpToTheValidHead() throws Exception {
            cutLastFrameShort();

            var owner = streamPartitionManager();
            var registry = ReplicaRegistry.replicaRegistry();

            owner.createStream(config("held", 1)).onFailure(cause -> fail(cause.message()));
            registry.registerReplica("held", 0, PEER);
            registry.registerReplica("held", 0, SELF);
            var validHead = manager.heldNotMaterializedWatermark("held", 0).or(-1L);
            var backfill = PartitionBackfill.partitionBackfill(registry,
                                                               owner.alignedRecovery(),
                                                               (_, request) -> Promise.success(servedByTheReplica(request.fromOffset())),
                                                               PEER);

            backfill.catchUpOwnerFrom("held", 0, SELF, validHead).await().onFailure(cause -> fail(cause.message()));

            assertThat(validHead).isEqualTo(EVENTS - 2L);
            assertThat(owner.partitionBuffer("held", 0).map(OffHeapRingBuffer::headOffset)).isEqualTo(Option.some(validHead));
            owner.close();
        }

        private void cutLastFrameShort() throws Exception {
            var bytes = Files.readAllBytes(walFile());

            Files.write(walFile(), Arrays.copyOf(bytes, bytes.length - 2));
        }

        /// D1: the page ships the WAL's own history or fails; an unreadable history is never answered as an empty one.
        @Test
        void catchupRead_whoseWalHistoryIsUnreadable_failsTheRead_neverShipsAnEmptyHistory() throws Exception {
            Files.write(sidecar(), new byte[]{1, 2, 3, 4, 5, 6, 7});

            var answer = catchupRead(0L, PAGE);

            assertThat(answer.success()).isFalse();
            assertThat(answer.events()).isEmpty();
        }

        /// NT-149, node-level deterministic equivalent (the Ember form needs a per-node off-heap budget the harness does not
        /// have): an owner behind a budget-deferred replica pulls through the replica's real forward handler, installs the
        /// WAL-served records and holds the replica's tail, so its activation catch-up is complete.
        @Test
        void ownerBehindABudgetDeferredReplica_catchesUpFromItsWal_andReachesItsTail() {
            var owner = streamPartitionManager();
            var registry = ReplicaRegistry.replicaRegistry();

            owner.createStream(config("held", 1)).onFailure(cause -> fail(cause.message()));
            registry.registerReplica("held", 0, PEER);
            registry.registerReplica("held", 0, SELF);
            var backfill = PartitionBackfill.partitionBackfill(registry,
                                                               owner.alignedRecovery(),
                                                               (_, request) -> Promise.success(servedByTheReplica(request.fromOffset())),
                                                               PEER);

            var outcome = backfill.catchUpOwnerFrom("held", 0, SELF, EVENTS - 1L).await();

            outcome.onFailure(cause -> fail(cause.message()));
            assertThat(owner.partitionBuffer("held", 0).map(OffHeapRingBuffer::headOffset)).isEqualTo(Option.some(EVENTS - 1L));
            assertThat(manager.partitionBuffer("held", 0).isEmpty()).as("the replica stayed unmaterialized").isTrue();
            owner.close();
        }

        private CatchupResponse servedByTheReplica(long from) {
            var answer = catchupRead(from, PAGE);

            return CatchupResponse.catchupResponse(SELF,
                                                   "held",
                                                   0,
                                                   from,
                                                   answer.events().getLast().offset(),
                                                   answer.events().stream().map(RawEventDto::data).toList(),
                                                   answer.events().stream().map(RawEventDto::timestamp).toList(),
                                                   answer.history());
        }

        private ReadForwardResponse catchupRead(long from, int max) {
            handler.onReadForward(readForward(PEER, "corr", "held", 0, from, max, false, true));

            return answers.getLast();
        }
    }

    /// [StreamPartitionManager#readHeldDurable] over an opener that records where each page scan starts.
    @Nested
    class ReadHeldDurable {
        private final List<Long> scanStarts = new CopyOnWriteArrayList<>();
        private long sealedFloor = -1L;
        private StreamPartitionManager manager;

        @BeforeEach
        void startOverARecordingOpener() {
            manager = StreamPartitionManager.streamPartitionManagerOverLogs(Long.MAX_VALUE,
                                                                            Option.some(recording(AppendLog.Opener.directory(walDir))),
                                                                            (_, _) -> sealedFloor);
            manager.placementRoleSupplier((stream, _) -> roles.getOrDefault(stream, Role.NONE));
            List.of(config("held", 1), config("fresh", 1), config("elsewhere", 1))
                .forEach(c -> manager.onStreamConfigPut(configPut(c)));
            roles.put("held", Role.REPLICA);
            roles.put("fresh", Role.REPLICA);
        }

        @AfterEach
        void stop() {
            manager.close();
        }

        /// D3: a reader paging sequentially continues each scan where the last page ended, instead of rescanning from 0.
        @Test
        void readHeldDurable_pagedSequentially_continuesEachScanFromTheLastPagesEnd() {
            assertThat(offsets(read("held", 0, 2))).containsExactly(0L, 1L);
            assertThat(offsets(read("held", 2, 2))).containsExactly(2L, 3L);
            assertThat(offsets(read("held", 4, 2))).containsExactly(4L);
            assertThat(scanStarts.getFirst()).isZero();
            assertThat(scanStarts.subList(1, 3)).as("continued from the hint, scans %s", scanStarts).allMatch(start -> start > 0);
        }

        /// A read that is not the continuation of the last page has no hint to use: it scans from the start of the file.
        @Test
        void readHeldDurable_aReadNotContinuingTheLastPage_scansFromTheStartOfTheFile() {
            assertThat(offsets(read("held", 0, 2))).containsExactly(0L, 1L);
            assertThat(offsets(read("held", 3, 2))).containsExactly(3L, 4L);

            assertThat(scanStarts).containsExactly(0L, 0L);
        }

        /// D3: the remembered position now lands on a frame boundary, but the record there is not the one asked for (the
        /// asked-for record lies before it): the page is read again from the start, never taken from the wrong place.
        /// The first WAL has 48-byte frames, so a page of three ends at 144; the rewritten one has 25-byte frames for offsets 0-3
        /// and a 44-byte frame for offset 4, so 144 is the start of offset 5's frame.
        @Test
        void readHeldDurable_aStaleHintLandingOnAnotherRecord_fallsBackToAFullScan() throws Exception {
            rewriteWal(0, 1, 2, 3, 4, 5, 6, 7);
            assertThat(offsets(read("held", 0, 3))).containsExactly(0L, 1L, 2L);

            rewriteFrames(new long[]{0, 1, 2, 3, 4, 5, 6, 7}, new int[]{1, 1, 1, 1, 20, 7, 7, 7});

            assertThat(offsets(read("held", 3, 2))).containsExactly(3L, 4L);
        }

        /// The floor itself is sealed: a read AT it is expired past it; the first offset above it is merely not written yet.
        @Test
        void readHeldDurable_anEmptyWalReadAtTheSealedFloor_isCursorExpiredPastTheFloor() {
            sealedFloor = 4L;

            assertThat(failure(read2("fresh", 4, PAGE))).isEqualTo(new StreamError.CursorExpired(4, 5));
            assertThat(read("fresh", 5, PAGE)).isEmpty();
        }

        /// D3: a hint whose page does not begin at the expected offset (the log changed under it) is never trusted: the page
        /// is read again from the start of the file.
        @Test
        void readHeldDurable_aStaleHint_fallsBackToAFullScan() throws Exception {
            assertThat(offsets(read("held", 0, 2))).containsExactly(0L, 1L);
            rewriteWal(0, 1, 2, 3, 4, 5, 6, 7);
            scanStarts.clear();

            var page = read("held", 2, 2);

            assertThat(offsets(page)).containsExactly(2L, 3L);
            assertThat(page.get(0).data()).isEqualTo(longPayload(2));
            assertThat(scanStarts).as("hint first, then the full scan").hasSize(2).endsWith(0L);
            assertThat(scanStarts.getFirst()).isPositive();
        }

        /// A WAL whose prefix was sealed away: the read below its lowest record names that record, so the caller reads the
        /// prefix from the tier.
        @Test
        void readHeldDurable_belowTheWalsLowestRecord_isCursorExpiredAtThatRecord() throws Exception {
            rewriteWal(3, 4);

            assertThat(failure(read2("held", 0, PAGE))).isEqualTo(new StreamError.CursorExpired(0, 3));
        }

        @Test
        void readHeldDurable_anEmptyWalBelowTheSealedFloor_isCursorExpiredPastTheFloor() {
            sealedFloor = 4L;

            assertThat(failure(read2("fresh", 0, PAGE))).isEqualTo(new StreamError.CursorExpired(0, 5));
        }

        @Test
        void readHeldDurable_anEmptyWalAboveTheSealedFloor_isAnEmptyPage() {
            assertThat(read("fresh", 0, PAGE)).isEmpty();
        }

        @Test
        void readHeldDurable_aHoleInsideThePage_failsTheRead_neverSkipsIt() throws Exception {
            rewriteWal(0, 1, 2, 3);
            cutFrame(2);

            assertThat(failure(read2("held", 0, PAGE))).isInstanceOf(StreamError.WalReplayMismatch.class);
        }

        @Test
        void readHeldDurable_ofAMaterializedOrUnheldPartition_isNone() {
            manager.materializePartition("held", 0).onFailure(cause -> fail(cause.message()));

            assertThat(manager.readHeldDurable("held", 0, 0, PAGE).isEmpty()).as("materialized").isTrue();
            assertThat(manager.readHeldDurable("elsewhere", 0, 0, PAGE).isEmpty()).as("not held here").isTrue();
            assertThat(manager.heldEpochHistory("held", 0).isEmpty()).isTrue();
        }

        @Test
        void heldEpochHistory_isTheSidecarsHistory_andAnUnreadableOneIsAFailure() throws Exception {
            assertThat(manager.heldEpochHistory("held", 0).map(Result::unwrap)).isEqualTo(Option.some(sidecarHistory()));
            assertThat(sidecarHistory()).extracting(ProvenanceEntry::rank).contains(E1);

            Files.write(sidecar(), new byte[]{1, 2, 3, 4, 5, 6, 7});

            assertThat(manager.heldEpochHistory("held", 0).map(Result::isFailure)).isEqualTo(Option.some(true));
        }

        private List<OffHeapRingBuffer.RawEvent> read(String stream, long from, int max) {
            return read2(stream, from, max).unwrap();
        }

        private Result<List<OffHeapRingBuffer.RawEvent>> read2(String stream, long from, int max) {
            return manager.readHeldDurable(stream, 0, from, max).or(() -> fail("held without a ring"));
        }

        private AppendLog.Opener recording(AppendLog.Opener delegate) {
            return new AppendLog.Opener() {
                @Override
                public Result<AppendLog> open(String name) {
                    return delegate.open(name);
                }

                @Override
                public Result<AppendLog.LogExtent> inspect(String name) {
                    return delegate.inspect(name);
                }

                @Override
                public Result<List<String>> listLogs() {
                    return delegate.listLogs();
                }

                @Override
                public Result<AppendLog.RangePage> readRange(String name, long fromOffset, int maxRecords, long startPosition) {
                    scanStarts.add(startPosition);

                    return delegate.readRange(name, fromOffset, maxRecords, startPosition);
                }

                @Override
                public Result<List<AppendLog.EpochStart>> readEpochHistory(String name) {
                    return delegate.readEpochHistory(name);
                }
            };
        }
    }

    private void record(Object message) {
        if (message instanceof ReadForwardResponse response) {
            answers.add(response);
        }
    }

    /// Replace `held`'s WAL with records at `offsets`, each carrying a longer payload than the original's, so a position
    /// remembered in the original lands elsewhere.
    private void rewriteWal(long... offsets) throws Exception {
        var file = walFile();

        Files.delete(file);
        var wal = AppendLog.open(file).unwrap();

        for (var offset : offsets) {
            wal.append(offset, longPayload(offset), 2000L + offset).await().onFailure(cause -> fail(cause.message()));
        }
        wal.close();
    }

    /// Replace `held`'s WAL with frames at `offsets`, the i-th carrying `lengths[i]` payload bytes.
    private void rewriteFrames(long[] offsets, int[] lengths) throws Exception {
        var file = walFile();

        Files.delete(file);
        var wal = AppendLog.open(file).unwrap();

        for (var i = 0; i < offsets.length; i++) {
            wal.append(offsets[i], new byte[lengths[i]], 2000L + offsets[i]).await().onFailure(cause -> fail(cause.message()));
        }
        wal.close();
    }

    /// Splice the frame at `index` out of `held`'s WAL, leaving every other frame byte-identical with a valid CRC: a hole
    /// the append path itself refuses to write. A frame is `[payloadLen:4][offset:8][timestamp:8][crc:4]` + payload.
    private void cutFrame(int index) throws Exception {
        var file = walFile();
        var bytes = Files.readAllBytes(file);
        var start = frameStart(bytes, index);
        var end = frameStart(bytes, index + 1);
        var cut = new byte[bytes.length - (end - start)];

        System.arraycopy(bytes, 0, cut, 0, start);
        System.arraycopy(bytes, end, cut, start, bytes.length - end);
        Files.write(file, cut);
    }

    private static int frameStart(byte[] bytes, int index) {
        var position = 0;

        for (var i = 0; i < index; i++) {
            position += FRAME_HEADER_BYTES + ByteBuffer.wrap(bytes, position, 4).getInt();
        }
        return position;
    }

    private Path walFile() {
        try (Stream<Path> files = Files.walk(walDir)) {
            var wals = files.filter(file -> file.getFileName().toString().endsWith(".wal"))
                            .filter(file -> file.toString().contains("held"))
                            .toList();

            assertThat(wals).as("one WAL of held").hasSize(1);

            return wals.getFirst();
        } catch (Exception e) {
            return fail(e);
        }
    }

    private List<ProvenanceEntry> sidecarHistory() {
        return AppendLog.readEpochHistory(walFile())
                        .flatMap(history -> Result.allOf(history.stream().map(ProvenanceEntry::provenanceEntry).toList()))
                        .unwrap();
    }

    private Path sidecar() {
        var wal = walFile().getFileName().toString();

        return walFile().resolveSibling(wal.substring(0, wal.length() - ".wal".length()) + ".epochs");
    }

    private static <T> Object failure(Result<T> result) {
        var causes = new ArrayList<Object>();

        result.onSuccess(value -> fail("expected a failure, got " + value)).onFailure(causes::add);

        return causes.getFirst();
    }

    private static List<Long> offsets(List<OffHeapRingBuffer.RawEvent> events) {
        return events.stream().map(OffHeapRingBuffer.RawEvent::offset).toList();
    }

    private static byte[] payload(int i) {
        return ("event-" + i).getBytes();
    }

    private static byte[] longPayload(long i) {
        return ("rewritten-event-number-" + i).getBytes();
    }

    private static StreamConfig config(String name, int partitions) {
        return StreamConfig.streamConfig(name,
                                         partitions,
                                         RetentionPolicy.retentionPolicy(100, 64 * 1024L, 3_600_000L),
                                         "latest",
                                         1_048_576L,
                                         ConsistencyMode.EVENTUAL,
                                         1,
                                         1,
                                         StreamCompression.NONE,
                                         Option.none());
    }

    private static ValuePut<StreamConfigKey, StreamConfigValue> configPut(StreamConfig config) {
        return new ValuePut<>(new KVCommand.Put<>(StreamConfigKey.streamConfigKey(config.name()),
                                                  StreamConfigValue.streamConfigValue(config)),
                              Option.none());
    }
}
