// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.aether.stream.replication.ReplicationMessage;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.storage.AppendLog;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.OffHeapRingBuffer.offHeapRingBuffer;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// Streams audit A-01 (#2201): the ordered append section has no abort. A refusal inside the section (a WAL that
/// refuses the frame: EpochRegression, a fail-stopped or closed WAL) leaves the ring's offset assigned and appended,
/// while no WAL frame exists for it. Replicas pull the appended (not visible) range, and the next accepted frame lands
/// past a hole.
///
/// Each nested group holds the TRIPWIRE (enabled, asserts today's behaviour, GREEN) and the REAL assertion beside it
/// (`@Disabled`, RED today). When the overhaul adds the abort, the tripwire fails: delete it and enable the real one.
class OrderedSectionAbortTripwireTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final Epoch E1 = Epoch.epoch(0, 1, 0);
    private static final Epoch E3 = Epoch.epoch(0, 3, 0);
    private static final String ENABLE = "A-01 tripwire: the ordered section now aborts a refused append; delete this tripwire and enable the real assertion beside it (#2201)";

    /// The ring contract itself: a failed `inOrder` must leave the head where it was (RING appendOrdered).
    ///
    /// This pair is ring-API specific: it flips ONLY for an abort inside `OffHeapRingBuffer.appendOrdered`. An abort placed in the manager
    /// (wrapping `inOrder` in `StreamPartitionManager`, which 1b-core's "no caller change" leaves open) leaves the ring contract as it is. The two
    /// manager-level groups below (`FailStoppedWal`, `EpochRace`) flip for either placement; read them as the acceptance criterion, and this pair as
    /// the pin for a ring-level fix only.
    @Nested
    class RingContract {
        @Test
        void appendOrdered_leavesTheOffsetAssigned_whenTheContinuationFails_tripwire() {
            try (var ring = offHeapRingBuffer(8, 4096)) {
                ring.append(bytes("a"), 1L).onFailure(cause -> fail(cause.message()));
                var before = ring.headOffset();

                var refused = ring.<Long> appendOrdered(bytes("orphan"), 2L, _ -> Causes.cause("wal refused").result());

                assertThat(refused.isFailure()).as("control: the continuation's failure is returned").isTrue();
                assertThat(ring.headOffset()).as(ENABLE).isEqualTo(before + 1);
                assertThat(texts(ring.readAppended(before + 1, 5).unwrap())).as(ENABLE).containsExactly("orphan");
            }
        }

        @Disabled("A-01 real assertion, RED today: a failed inOrder must leave the head unchanged (#2201)")
        @Test
        void appendOrdered_leavesTheHeadUnchanged_whenTheContinuationFails() {
            try (var ring = offHeapRingBuffer(8, 4096)) {
                ring.append(bytes("a"), 1L).onFailure(cause -> fail(cause.message()));
                var before = ring.headOffset();

                ring.<Long> appendOrdered(bytes("orphan"), 2L, _ -> Causes.cause("wal refused").result());

                assertThat(ring.headOffset()).isEqualTo(before);
                assertThat(texts(ring.readAppended(before + 1, 5).unwrap())).isEmpty();
            }
        }
    }

    /// 1b-core's fail-stop variant: a WAL that stopped (an I/O failure, here a closed channel standing in for ENOSPC)
    /// refuses every later frame inside the section, so each refused publish leaves one more orphan in the ring.
    @Nested
    class FailStoppedWal {
        @TempDir
        Path walDir;

        @Test
        void refusedPublishes_leaveOrphansInTheRing_tripwire() throws Exception {
            var manager = openWithOneAckedEvent(walDir);
            var ring = manager.partitionBuffer(STREAM, PARTITION).unwrap();
            var before = ring.headOffset();

            failStopTheWal(manager);
            var first = manager.publishLocal(STREAM, PARTITION, bytes("lost-1"), 2L);
            var second = manager.publishLocal(STREAM, PARTITION, bytes("lost-2"), 3L);

            assertThat(first.isFailure()).as("control: the publish that met the I/O failure is reported failed").isTrue();
            assertThat(second.isFailure()).as("control: a publish on a fail-stopped WAL is refused").isTrue();
            assertThat(ring.headOffset()).as(ENABLE).isEqualTo(before + 2);
            assertThat(texts(ring.readAppended(before + 1, 5).unwrap())).as(ENABLE).containsExactly("lost-1", "lost-2");
            manager.close();
        }

        @Disabled("A-01 real assertion, RED today: publishes refused by a stopped WAL must not enter the ring (#2201)")
        @Test
        void refusedPublishes_leaveNothingInTheRing() throws Exception {
            var manager = openWithOneAckedEvent(walDir);
            var ring = manager.partitionBuffer(STREAM, PARTITION).unwrap();
            var before = ring.headOffset();

            failStopTheWal(manager);
            manager.publishLocal(STREAM, PARTITION, bytes("lost-1"), 2L);
            manager.publishLocal(STREAM, PARTITION, bytes("lost-2"), 3L);

            assertThat(ring.headOffset()).isEqualTo(before);
            assertThat(texts(ring.readAppended(before + 1, 5).unwrap())).isEmpty();
            manager.close();
        }
    }

    /// 1b-core's deterministic epoch race on the real ring, real `AppendLog` and the real manager: a publish stalled
    /// between the fence and the section loses to an epoch bump, and the WAL refuses its older epoch inside the section.
    /// The seam is a replication stub that runs INSIDE the holder's section (Java monitors are reentrant).
    @Nested
    class EpochRace {
        @TempDir
        Path walDir;

        @Test
        void epochRegressionInsideTheSection_leavesAnOrphan_andAHoleInTheWal_tripwire() throws Exception {
            var race = new Race(walDir);

            race.run();

            assertThat(race.stalled.get().isFailure()).as("control: the stale-epoch publish was refused by the WAL").isTrue();
            assertThat(race.stalledOffsetVisibleToReaders()).as(ENABLE).isTrue();
            assertThat(race.walOffsets()).as("the WAL has a hole at the orphan's offset").containsExactly(0L, 1L, 3L);
            assertThat(race.restartRefused()).as(ENABLE).isTrue();
            race.shutDown();
        }

        @Disabled("A-01 real assertion, RED today: the refused stale-epoch publish must leave no offset and no WAL hole (#2201)")
        @Test
        void epochRegressionInsideTheSection_leavesNoOrphanAndNoHole() throws Exception {
            var race = new Race(walDir);

            race.run();

            assertThat(race.stalledOffsetVisibleToReaders()).isFalse();
            assertThat(race.restartRefused()).isFalse();
            race.shutDown();
        }
    }

    /// T0 publishes at E1 and, inside its own section, starts a stale-epoch publisher T2, bumps the epoch to E3 reentrantly,
    /// then releases. T2 cannot enter before T0 leaves (T0 holds the section monitor), so it enters with offset 2 and the WAL
    /// refuses E1 after E3. The order is forced by joins, not by timing: `run` joins T0, then T2, and only then publishes the
    /// E3 record, so no thread can take offset 2 ahead of T2 and no probe of T2's state is needed.
    private final class Race implements ReplicationManager {
        private final Path walDir;
        private final ReplicaRegistry registry = ReplicaRegistry.replicaRegistry();
        private final AtomicInteger sends = new AtomicInteger();
        private final AtomicReference<Result<Long>> stalled = new AtomicReference<>();
        private final AtomicReference<Thread> staleThread = new AtomicReference<>();
        private StreamPartitionManager manager;

        Race(Path walDir) {
            this.walDir = walDir;
            manager = open(this, walDir);
        }

        void run() throws Exception {
            var t0 = new Thread(() -> manager.publishLocal(STREAM, PARTITION, bytes("e1-first"), 1L, E1));

            t0.start();
            t0.join(TimeUnit.SECONDS.toMillis(30));
            assertThat(t0.isAlive()).as("T0 finished").isFalse();
            staleThread.get().join(TimeUnit.SECONDS.toMillis(30));
            assertThat(staleThread.get().isAlive()).as("T2 finished").isFalse();
            manager.publishLocal(STREAM, PARTITION, bytes("e3-after"), 5L, E3).onFailure(cause -> fail(cause.message()));
        }

        /// Runs inside T0's section, after its WAL frame (offset 0, E1) was written.
        @Contract
        @Override
        public void replicateEvent(String streamName, int partition, long offset, byte[] payload, long timestamp, Epoch ownerEpoch) {
            if (sends.getAndIncrement() == 0) {
                bumpEpochWhileStalePublisherWaits();
            }
        }

        @Contract
        private void bumpEpochWhileStalePublisherWaits() {
            var stale = new Thread(() -> stalled.set(manager.publishLocal(STREAM, PARTITION, bytes("e1-stale"), 2L, E1)));

            staleThread.set(stale);
            stale.start();
            manager.publishLocal(STREAM, PARTITION, bytes("e3-reentrant"), 3L, E3).onFailure(cause -> fail(cause.message()));
            sends.set(Integer.MIN_VALUE / 2);
        }

        boolean stalledOffsetVisibleToReaders() {
            var ring = manager.partitionBuffer(STREAM, PARTITION).unwrap();

            return texts(ring.read(0, 10).unwrap()).contains("e1-stale");
        }

        List<Long> walOffsets() throws Exception {
            var log = GatedWalFsync.walOf(manager, STREAM, PARTITION);

            var records = new ArrayList<AppendLog.WalRecord>();

            log.replay(-1L, records::add).onFailure(cause -> fail(cause.message()));
            return records.stream().map(AppendLog.WalRecord::offset).toList();
        }

        /// A fresh manager over the same WAL directory: whether it refuses to materialize the partition.
        boolean restartRefused() {
            manager.close();
            manager = streamPartitionManager(Long.MAX_VALUE,
                                             EvictionListener.NOOP,
                                             ReplicationManager.NONE,
                                             Option.some(walDir),
                                             LastSealedOffsetSource.none());

            return manager.ensureStreamMaterialized(StreamConfig.streamConfig(STREAM)).isFailure();
        }

        void shutDown() {
            manager.close();
        }

        @Override
        public void handleAck(ReplicationMessage.ReplicateAck ack) {}

        @Override
        public ReplicaRegistry registry() {
            return registry;
        }

        @Override
        public Promise<Unit> awaitReplication(String streamName, int partition, long offset, int minAcks) {
            return Promise.unitPromise();
        }

        @Override
        public long replicatedThrough(String streamName, int partition, int minAcks) {
            return Long.MAX_VALUE;
        }

        @Override
        public long replicatedThrough(ReplicationMessage.ReplicateAck pending, int minAcks) {
            return Long.MAX_VALUE;
        }

        @Contract
        @Override
        public void observeAcks(AckObserver observer) {}
    }

    private static StreamPartitionManager open(ReplicationManager replication, Path walDir) {
        var manager = streamPartitionManager(Long.MAX_VALUE,
                                             EvictionListener.NOOP,
                                             replication,
                                             Option.some(walDir),
                                             LastSealedOffsetSource.none());

        manager.ensureStreamMaterialized(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));
        return manager;
    }

    private static StreamPartitionManager openWithOneAckedEvent(Path walDir) {
        var manager = open(ReplicationManager.NONE, walDir);

        manager.publishLocal(STREAM, PARTITION, bytes("acked"), 1L).onFailure(cause -> fail(cause.message()));
        return manager;
    }

    /// An I/O failure on the WAL's channel: the next frame write fails and the log stops for good (`failStop`).
    private static void failStopTheWal(StreamPartitionManager manager) throws Exception {
        var field = AppendLog.class.getDeclaredField("channel");

        field.setAccessible(true);
        ((FileChannel) field.get(GatedWalFsync.walOf(manager, STREAM, PARTITION))).close();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(UTF_8);
    }

    private static List<String> texts(List<OffHeapRingBuffer.RawEvent> events) {
        return events.stream().map(event -> new String(event.data(), UTF_8)).toList();
    }
}
