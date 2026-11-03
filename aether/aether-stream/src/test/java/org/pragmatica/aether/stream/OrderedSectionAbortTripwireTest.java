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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
/// PR-2c flipped every group: the tripwires are deleted and the real assertions are enabled, each in the commit that caused the flip
/// (the in-section guard flipped `EpochRace`; the rollback flipped `RingContract` and `FailStoppedWal`).
@Timeout(120)
class OrderedSectionAbortTripwireTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final Epoch E1 = Epoch.epoch(0, 1, 0);
    private static final Epoch E3 = Epoch.epoch(0, 3, 0);

    /// The ring contract itself: a failed `inOrder` must leave the head where it was (RING appendOrdered).
    ///
    /// This pair is ring-API specific: it flips ONLY for an abort inside `OffHeapRingBuffer.appendOrdered`. An abort placed in the manager
    /// (wrapping `inOrder` in `StreamPartitionManager`, which 1b-core's "no caller change" leaves open) leaves the ring contract as it is. The two
    /// manager-level groups below (`FailStoppedWal`, `EpochRace`) flip for either placement; read them as the acceptance criterion, and this pair as
    /// the pin for a ring-level fix only.
    @Nested
    class RingContract {
        /// Flipped by PR-2c's rollback (`OffHeapRingBuffer#continuedOrWithdrawn`).
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

        /// Flipped by PR-2c: the publish that meets the I/O failure is withdrawn by the rollback, the next is refused by the guard.
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

        /// Flipped by PR-2c's in-section guard (`PartitionSection`): the stale publish is refused before the ring assigns it an offset.
        @Test
        void epochRegressionInsideTheSection_leavesNoOrphanAndNoHole() throws Exception {
            var race = new Race(walDir);

            race.run();

            assertThat(race.stalled.get().isFailure()).as("control: the stale-epoch publish was refused").isTrue();
            race.stalled.get().onFailure(cause -> assertThat(cause).as("refused by the in-section provenance check, not by the WAL after an offset")
                                                                   .isInstanceOf(StreamError.ProvenanceRegression.class));
            assertThat(race.stalledOffsetVisibleToReaders()).isFalse();
            assertThat(race.walOffsets()).as("no hole: the next publish took the offset the refused one never had").containsExactly(0L, 1L, 2L);
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
            awaitBlockedOnTheSection(stale);
            manager.publishLocal(STREAM, PARTITION, bytes("e3-reentrant"), 3L, E3).onFailure(cause -> fail(cause.message()));
            sends.set(Integer.MIN_VALUE / 2);
        }

        /// The stale publisher has passed every pre-section check (at E1, the last recorded epoch) and waits for the section T0 holds,
        /// so its refusal can only come from inside the section.
        private void awaitBlockedOnTheSection(Thread stale) {
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

            while (stale.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(stale.getState()).as("T2 waits for the section").isEqualTo(Thread.State.BLOCKED);
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
