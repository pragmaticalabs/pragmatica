// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.stream.provenance.LogProvenance;
import org.pragmatica.aether.stream.provenance.PartitionFlags;
import org.pragmatica.aether.stream.provenance.PartitionFlags.PartitionFlag;
import org.pragmatica.aether.stream.provenance.ProvenanceComparison;
import org.pragmatica.aether.stream.provenance.ProvenanceEntry;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #1596, spec #1569 §7.5.1: where a partition log's owner-epoch history is recorded. Owner publish and live replica
/// receive attribute each record to the epoch it was first appended under; a catch-up apply attributes nothing
/// itself and installs the source's slice instead, after the N13 check. Each test names the mutation that reddens it.
class PartitionProvenanceRecordingTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final Epoch E1 = Epoch.epoch(0,1, 0);
    private static final Epoch E2 = Epoch.epoch(0,2, 0);
    private static final Epoch E3 = Epoch.epoch(0,3, 0);
    private static final byte[] PAYLOAD = "e".getBytes(UTF_8);

    @TempDir
    Path walDir;

    private StreamPartitionManager manager;

    @BeforeEach
    void setUp() {
        manager = open();
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    @Nested
    class OwnerPublish {
        /// Red under "owner publish writes unattributed frames". The history survives a restart: it is on the volume.
        @Test
        void publish_recordsEachEpochOnce_atItsFirstOffset_durably() {
            publish(E1, 3);
            publish(E2, 2);

            assertThat(history()).containsExactly(at(E1, 0), at(E2, 3));

            manager.close();
            manager = open();

            assertThat(history()).as("the history is read back from the volume").containsExactly(at(E1, 0), at(E2, 3));
        }

        /// The batch publish attributes its run too (to the manager's owner-epoch source, the unfenced floor here).
        /// Red under "batch publish writes unattributed frames".
        @Test
        void batchPublish_attributesTheRun() {
            manager.publishLocalBatchAtFloor(STREAM, PARTITION, List.of(PAYLOAD, PAYLOAD), 1L, 0)
                   .onFailure(cause -> fail(cause.message()));
            publish(E1, 1);

            assertThat(history()).containsExactly(at(Epoch.ZERO, 0), at(E1, 2));
        }
    }

    @Nested
    class ReplicaReceive {
        /// The live receive records the batch epoch; the catch-up append (no epoch of its own) records nothing.
        /// Red under "live receive unattributed" and under "catch-up append attributed to its fence epoch".
        @Test
        void liveReceive_recordsTheBatchEpoch_catchUpAppendRecordsNothing() {
            live(0, E1);
            live(1, E1);
            caughtUp(2);
            live(3, E2);

            assertThat(history()).containsExactly(at(E1, 0), at(E2, 3));
        }

        /// Empty-history rule: a log holding records but no history (written before provenance, here by unattributed
        /// catch-up appends) records nothing when a live epoch arrives, so it reads as HISTORY_MISSING, never as a
        /// history starting mid-log. Red under "drop the empty-history rule".
        @Test
        void logWithRecordsButNoHistory_recordsNothing() {
            caughtUp(0);
            caughtUp(1);
            live(2, E1);

            assertThat(history()).isEmpty();
        }

        /// A batch of an epoch older than one the log already records is refused BEFORE the ring appends it, so ring
        /// and WAL stay in step. Red under "drop the pre-section provenance check" (the in-section refusal comes after
        /// the ring assigned the offset).
        @Test
        void olderEpochThanTheLogRecords_isRefusedBeforeTheRingAppends() {
            live(0, E2);
            live(1, E2);

            var refused = manager.appendRecovered(STREAM, PARTITION, 2, PAYLOAD, 1L, E1);

            refused.onSuccess(_ -> fail("an older epoch must be refused"))
                   .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.ProvenanceRegression.class));
            assertThat(manager.nextExpectedOffset(STREAM, PARTITION)).as("nothing entered the ring").isEqualTo(2L);
            assertThat(history()).containsExactly(at(E2, 0));
        }
    }

    @Nested
    class CatchUpInstall {
        /// #1638 B1 (v1638 probe3): re-installing entries this log already holds -- not only its last one -- is a no-op,
        /// not a refusal. Red under "no skip of held entries".
        @Test
        void reinstallingHeldEntries_isIdempotent() {
            live(0, E1);
            live(1, E2);
            live(2, E3);

            install(1, 2, at(E1, 0), at(E2, 1), at(E3, 2)).onFailure(cause -> fail("identical histories: " + cause.message()));

            assertThat(history()).containsExactly(at(E1, 0), at(E2, 1), at(E3, 2));
        }

        /// #1638 B1 (v1638 probe6): a page whose apply failed after its install left entries above the head; trimming
        /// drops them, and the retry of the same page installs and applies cleanly.
        @Test
        void failedApply_trimmed_thenTheRetryInstallsCleanly() {
            live(0, E1);
            live(1, E1);
            live(2, E1);
            var slice = new ProvenanceEntry[]{at(E1, 0), at(E2, 4), at(E3, 6)};

            manager.trimProvenance(installed(3, 9, slice)).onFailure(cause -> fail(cause.message()));
            assertThat(history()).as("nothing above the head survives the failed apply").containsExactly(at(E1, 0));

            install(3, 9, slice).onFailure(cause -> fail("the retry: " + cause.message()));
            for (var offset = 3; offset <= 9; offset++) {
                caughtUp(offset);
            }

            assertThat(history()).containsExactly(slice);
        }

        /// #1638 S3: N13 compares over every offset this copy HOLDS up to the page's end, not only below `from` -- a
        /// re-verifying pull starts below the head. Here the copy holds e1 records 0..4, the pull starts at 3, and the
        /// source says e2 began at 4: refused at 4. Red under "N13 narrowed to the spec's `[0, from - 1]`" (which would
        /// record e2@4 over e1 records).
        @Test
        void mismatchInTheWidenedOverlap_isRefused() {
            for (var offset = 0; offset < 5; offset++) {
                live(offset, E1);
            }

            install(3, 8, at(E1, 0), at(E2, 4)).onSuccess(_ -> fail("a divergence at a held offset above `from` must refuse"))
                                               .onFailure(cause -> assertThat(cause).isEqualTo(new StreamError.ProvenanceMismatch(STREAM,
                                                                                                                                  PARTITION,
                                                                                                                                  4)));
            assertThat(history()).containsExactly(at(E1, 0));
        }

        /// #1638 S1 (v1638 probe1/probe2): an entry above the head -- a crash between its durable record and its first
        /// frame, or an install whose apply never happened -- is dropped when the partition is reopened, so the copy is
        /// never ranked by an epoch it holds no record of. Red under "no trim at open".
        @Test
        void ghostEntryAboveTheHead_isTrimmedAtOpen() {
            live(0, E1);
            live(1, E1);
            live(2, E1);
            install(3, 9, at(E1, 0), at(E3, 6)).onFailure(cause -> fail(cause.message()));
            assertThat(history()).as("the ghost is on the volume").containsExactly(at(E1, 0), at(E3, 6));

            manager.close();
            manager = open();

            var local = manager.localProvenance(STREAM, PARTITION).unwrap().unwrap();

            assertThat(local.head()).isEqualTo(2L);
            assertThat(local.history()).as("trimmed at open").containsExactly(at(E1, 0));
        }

        /// #1638 F3 (v1638 probe r3): the crash-shaped ghost -- an entry starting EXACTLY at head + 1, recorded durably
        /// before the first frame of its epoch that the crash took -- is dropped at open. Red under the design's own bound
        /// `truncateEpochsAbove(head + 1)`, which keeps it.
        @Test
        void ghostAtHeadPlusOne_isTrimmedAtOpen() {
            live(0, E1);
            live(1, E1);
            live(2, E1);
            install(3, 9, at(E1, 0), at(E3, 3)).onFailure(cause -> fail(cause.message()));
            assertThat(history()).as("the ghost at head + 1 is on the volume").containsExactly(at(E1, 0), at(E3, 3));

            manager.close();
            manager = open();

            assertThat(history()).containsExactly(at(E1, 0));
        }

        /// #1638 F2 (v1638 probe r1) end to end: an EMPTY replica installs a slice and dies before its apply. The trim at
        /// open cannot tell an empty log from one whose data lives in sealed blocks, so it keeps the entries; the copy
        /// must still rank by nothing, below a copy holding records, and never diverge. Red under "rank every entry".
        @Test
        void emptyCopysGhosts_surviveTheReopen_butNeverRank() {
            install(0, 5, at(E1, 0), at(E3, 2)).onFailure(cause -> fail(cause.message()));

            manager.close();
            manager = open();

            var ghost = manager.localProvenance(STREAM, PARTITION).unwrap().unwrap();
            var holder = LogProvenance.logProvenance(0, 0, 5, List.of(at(E1, 0), at(E2, 3)));

            assertThat(ghost.head()).isEqualTo(-1L);
            assertThat(ghost.history()).as("kept by the trim at open").containsExactly(at(E1, 0), at(E3, 2));
            assertThat(ProvenanceComparison.diverge(ghost, holder)).as("an empty copy never diverges").isFalse();
            assertThat(LogProvenance.SOURCE_ORDER.compare(ghost, holder)).as("the empty copy ranks BELOW the holder of 0..5")
                                                                          .isNegative();
        }

        /// #1638 F1: the failed-apply trim of an EMPTY replica drops what its install recorded -- its head (-1) is
        /// known, unlike at open. Red under "the failed-apply trim keeps the `head < 0` guard".
        @Test
        void failedApplyOnAnEmptyReplica_isTrimmed() {
            manager.trimProvenance(installed(0, 5, at(E1, 0), at(E3, 2))).onFailure(cause -> fail(cause.message()));

            assertThat(history()).isEmpty();
        }

        /// N13 holds (the replica's own prefix matches the slice): the slice's entries past the replica's head are
        /// recorded. Red under "skip the install".
        @Test
        void matchingSlice_isRecorded() {
            live(0, E1);
            live(1, E1);
            live(2, E1);

            install(3, 6, at(E1, 0), at(E2, 5)).onFailure(cause -> fail(cause.message()));

            assertThat(history()).containsExactly(at(E1, 0), at(E2, 5));
        }

        /// N13 fails: the source says e2 began at 3, this replica holds e1 records at 3 and 4. Nothing is recorded,
        /// the partition is quarantined at 3, the first offset whose provenance differs. Red under "drop N13".
        @Test
        void mismatchingSlice_isRefused_andQuarantinesAtTheFirstDifferingOffset() {
            for (var offset = 0; offset < 5; offset++) {
                live(offset, E1);
            }

            install(5, 8, at(E1, 0), at(E2, 3)).onSuccess(_ -> fail("a mismatching slice must be refused"))
                                               .onFailure(cause -> assertThat(cause).isEqualTo(new StreamError.ProvenanceMismatch(STREAM,
                                                                                                                                  PARTITION,
                                                                                                                                  3)));
            assertThat(history()).containsExactly(at(E1, 0));
            assertThat(manager.quarantinedAt(STREAM, PARTITION)).isEqualTo(Option.some(3L));
        }

        /// A replica holding nothing installs the whole slice, the base included.
        @Test
        void emptyReplica_installsTheWholeSlice() {
            install(0, 9, at(E1, 0), at(E2, 4)).onFailure(cause -> fail(cause.message()));

            assertThat(history()).containsExactly(at(E1, 0), at(E2, 4));
        }

        /// A partition that keeps no log records no provenance: the install is a no-op, never a refusal.
        @Test
        void withoutALog_installIsANoOp() {
            var walless = streamPartitionManager(Long.MAX_VALUE);

            walless.createStream(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));
            walless.installProvenance(STREAM, PARTITION, 0, 9, List.of(at(E1, 0))).onFailure(cause -> fail(cause.message()));

            assertThat(walless.epochHistory(STREAM, PARTITION).unwrap()).isEmpty();
            walless.close();
        }
    }

    /// #1638 F1: two catch-ups of one partition in flight at once. A failed apply trims only what ITS install recorded,
    /// and never an entry another pending install relies on, so the other catch-up's records keep the epochs its
    /// source attributed them to. Driven at the seam in the exact interleaving (v1638 probe r2); the backfill's
    /// single-flight keeps the real pulls from overlapping (`PartitionBackfillTest$SingleFlight`).
    @Nested
    class ConcurrentCatchUps {
        /// v1638 probe r2: A installs e2@4 and has not applied; B's install of the same slice records nothing (e2@4 is
        /// held) and B's apply fails. B's trim must not drop A's e2@4 -- else A's records 4..9 read as e1, and the next
        /// re-verifying pull of the same, correct source is refused by N13 (a spurious quarantine and MARKED_DIVERGED).
        /// Red under "the trim drops every entry above the head".
        @Test
        void failedApplysTrim_keepsAnotherInstallsEntries() {
            for (var offset = 0; offset < 3; offset++) {
                live(offset, E1);
            }

            var first = installed(3, 9, at(E1, 0), at(E2, 4));
            var second = installed(3, 9, at(E1, 0), at(E2, 4));

            manager.trimProvenance(second).onFailure(cause -> fail(cause.message()));
            for (var offset = 3; offset <= 9; offset++) {
                caughtUp(offset);
            }
            manager.releaseProvenance(first);

            assertThat(history()).as("records 4..9 read as e2").containsExactly(at(E1, 0), at(E2, 4));
            install(3, 9, at(E1, 0), at(E2, 4)).onFailure(cause -> fail("the re-verifying pull: " + cause.message()));
            assertThat(manager.quarantinedAt(STREAM, PARTITION)).isEqualTo(Option.none());
        }

        /// The mirror: B records e2@4 first, then A's install finds it held and skips it. B's apply fails. e2@4 is B's
        /// own entry, but A relies on it, so B's trim keeps it while A is pending. Red under "trim everything this
        /// install recorded" (no shield for another pending install's claims).
        @Test
        void failedApplysTrim_keepsItsOwnEntryWhileAnotherPendingInstallReliesOnIt() {
            for (var offset = 0; offset < 3; offset++) {
                live(offset, E1);
            }

            var failing = installed(3, 9, at(E1, 0), at(E2, 4));
            var applying = installed(3, 9, at(E1, 0), at(E2, 4));

            assertThat(failing.recorded()).as("the first install recorded e2@4").hasSize(1);
            assertThat(applying.recorded()).as("the second found it held").isEmpty();

            manager.trimProvenance(failing).onFailure(cause -> fail(cause.message()));
            for (var offset = 3; offset <= 9; offset++) {
                caughtUp(offset);
            }
            manager.releaseProvenance(applying);

            assertThat(history()).as("records 4..9 read as e2").containsExactly(at(E1, 0), at(E2, 4));
        }

        /// Through the seam: a page whose apply fails is trimmed, one whose apply succeeds keeps its entries. Red under
        /// "the seam does not settle a failed apply".
        @Test
        void applyAttributed_trimsAFailedApply_andKeepsASuccessfulOne() {
            for (var offset = 0; offset < 3; offset++) {
                live(offset, E1);
            }

            var failed = manager.applyAttributed(STREAM,
                                                 PARTITION,
                                                 3,
                                                 9,
                                                 List.of(at(E1, 0), at(E2, 4)),
                                                 () -> Causes.cause("injected apply failure").result());

            assertThat(failed.isFailure()).isTrue();
            assertThat(history()).as("the failed page is trimmed").containsExactly(at(E1, 0));

            manager.applyAttributed(STREAM, PARTITION, 3, 9, List.of(at(E1, 0), at(E2, 4)), this::applyThreeToNine)
                   .onFailure(cause -> fail(cause.message()));

            assertThat(history()).containsExactly(at(E1, 0), at(E2, 4));
        }

        private Result<Long> applyThreeToNine() {
            for (var offset = 3; offset <= 9; offset++) {
                caughtUp(offset);
            }

            return Result.success(7L);
        }
    }

    /// The durable half of the local fences: an N13 mismatch raises `MARKED_DIVERGED`, a log with records and no
    /// history raises `HISTORY_MISSING` -- each once per process, not once per occurrence. Red under "quarantine only".
    @Nested
    class DurableFlag {
        private final List<AetherValue.PartitionRecoveryReason> raised = new CopyOnWriteArrayList<>();

        @BeforeEach
        void wireFlags() {
            manager.partitionFlags(recordingFlags(raised));
        }

        /// #1730 phase 2: the mismatch quarantines at once but raises nothing, because the backfill may repair it (an ordinary
        /// failover): the durable flag is raised only when the repair is REFUSED, once per process. The old premise (flag at
        /// detection) blocked the partition cluster-wide for a divergence the replica then healed by itself.
        @Test
        void n13Mismatch_raisesNothingAtDetection_andMarkedDivergedOnceWhenTheRepairIsRefused() {
            for (var offset = 0; offset < 5; offset++) {
                live(offset, E1);
            }

            install(5, 8, at(E1, 0), at(E2, 3));
            install(5, 8, at(E1, 0), at(E2, 3));

            assertThat(raised).as("detection alone flags nothing").isEmpty();
            assertThat(manager.quarantinedAt(STREAM, PARTITION).isPresent()).as("the local fence is up").isTrue();

            manager.quarantineView().flagUnrepaired(STREAM, PARTITION);
            manager.quarantineView().flagUnrepaired(STREAM, PARTITION);

            assertThat(raised).extracting(AetherValue.PartitionRecoveryReason::kind)
                              .containsExactly(AetherValue.PartitionRecoveryReasonKind.MARKED_DIVERGED);
            assertThat(raised.getFirst().evidence()).contains("offset 3");
        }

        @Test
        void recordsWithoutHistory_raiseHistoryMissing_once() {
            caughtUp(0);
            caughtUp(1);
            live(2, E1);
            live(3, E1);

            assertThat(raised).extracting(AetherValue.PartitionRecoveryReason::kind)
                              .containsExactly(AetherValue.PartitionRecoveryReasonKind.HISTORY_MISSING);
        }

        /// #1638 F6: a ghost above the head that the trim at open cannot drop (the history's temp file is blocked)
        /// keeps the partition closed on this node -- fail closed -- and raises `LOCAL_MISMATCH` for this copy on the
        /// durable flag. Red under "a failed trim at open is not flagged".
        @Test
        void failedTrimAtOpen_failsClosed_andRaisesLocalMismatch() throws IOException {
            live(0, E1);
            install(3, 9, at(E1, 0), at(E3, 6)).onFailure(cause -> fail(cause.message()));
            manager.close();
            blockHistoryWrites();

            var reopened = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));

            reopened.partitionFlags(recordingFlags(raised));
            assertThat(reopened.ensureStreamMaterialized(StreamConfig.streamConfig(STREAM)).isFailure()).as("fail closed")
                                                                                                         .isTrue();
            manager = reopened;

            assertThat(raised).extracting(AetherValue.PartitionRecoveryReason::kind)
                              .containsExactly(AetherValue.PartitionRecoveryReasonKind.LOCAL_MISMATCH);
            assertThat(raised.getFirst().evidence()).contains("above head 0");
        }

        /// A page from a source that VOUCHED for an empty history (it keeps no log: non-durable, Forge) is applied, as before the
        /// #1730 phase 2 vouched-flag redesign: the receiver cannot tell it from any other empty slice here, and refusing it
        /// would keep a WAL replica of a log-less owner from ever catching up. Related to #1938 (such a copy is later quarantined
        /// at its next re-verify, a separate defect). Red under "refuse every empty slice" (the 51e78b7e0 guard).
        @Test
        void emptySliceFromASourceThatKeepsNoLog_isApplied() {
            var applied = manager.applyAttributed(STREAM, PARTITION, 0, 2, List.of(), () -> {
                for (var offset = 0; offset <= 2; offset++) {
                    caughtUp(offset);
                }
                return Result.success(3L);
            });

            assertThat(applied.isSuccess()).isTrue();
            assertThat(manager.nextExpectedOffset(STREAM, PARTITION)).isEqualTo(3L);
        }

        @Test
        void attributedLog_raisesNothing() {
            publish(E1, 3);
            live(3, E2);

            assertThat(raised).isEmpty();
        }
    }

    /// rc4 ruling: provenance applies only to WAL-backed partitions. A log-less partition records nothing, is excluded
    /// from comparison (`localProvenance` is none), and is never flagged -- not for records without history, not for a
    /// mismatching catch-up slice, not for a slice-less replay. Red under "drop the WAL gate" on any of those sites.
    @Nested
    class WalLessPartition {
        private final List<AetherValue.PartitionRecoveryReason> raised = new CopyOnWriteArrayList<>();
        private StreamPartitionManager walless;

        @BeforeEach
        void openWalLess() {
            walless = streamPartitionManager(Long.MAX_VALUE);
            walless.createStream(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));
            walless.partitionFlags(recordingFlags(raised));
        }

        @AfterEach
        void closeWalLess() {
            walless.close();
        }

        @Test
        void walLessPartition_isNeverFlagged_andIsExcludedFromComparison() {
            walless.appendRecovered(STREAM, PARTITION, 0, PAYLOAD, 1L).onFailure(cause -> fail(cause.message()));
            walless.appendRecovered(STREAM, PARTITION, 1, PAYLOAD, 1L, E1).onFailure(cause -> fail(cause.message()));
            walless.publishLocal(STREAM, PARTITION, PAYLOAD, 1L, E2).onFailure(cause -> fail(cause.message()));
            walless.installProvenance(STREAM, PARTITION, 3, 9, List.of(at(E2, 0))).onFailure(cause -> fail(cause.message()));
            walless.installUnattributed(STREAM, PARTITION, 20).onFailure(cause -> fail(cause.message()));

            assertThat(raised).as("no HISTORY_MISSING, MARKED_DIVERGED or HISTORY_INCOMPLETE for a log-less partition").isEmpty();
            assertThat(walless.epochHistory(STREAM, PARTITION).unwrap()).isEmpty();
            assertThat(walless.localProvenance(STREAM, PARTITION).unwrap()).isEqualTo(Option.none());
            assertThat(walless.quarantinedAt(STREAM, PARTITION)).isEqualTo(Option.none());
        }
    }

    private static PartitionFlags recordingFlags(List<AetherValue.PartitionRecoveryReason> raised) {
        return new PartitionFlags() {
            @Override
            public Promise<PartitionFlag> raise(String stream, int partition, AetherValue.PartitionRecoveryReason reason) {
                raised.add(reason);

                return Promise.success(new PartitionFlag(AetherValue.StreamPartitionRecoveryValue.raised(Option.none(), reason), "digest"));
            }

            @Override
            public Option<PartitionFlag> status(String stream, int partition) {
                return Option.none();
            }

            @Override
            public AetherValue.PartitionRecoveryReason local(AetherValue.PartitionRecoveryReasonKind kind, String evidence) {
                return AetherValue.PartitionRecoveryReason.partitionRecoveryReason(kind, Option.some("self"), evidence);
            }
        };
    }

    @Test
    void localProvenance_reportsTheHeadAndTheHistory() {
        publish(E1, 4);

        var local = manager.localProvenance(STREAM, PARTITION).unwrap().unwrap();

        assertThat(local.head()).isEqualTo(3L);
        assertThat(local.base()).isEqualTo(0L);
        assertThat(local.history()).containsExactly(at(E1, 0));
    }

    private StreamPartitionManager open() {
        var opened = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));

        opened.ensureStreamMaterialized(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));

        return opened;
    }

    private void publish(Epoch epoch, int count) {
        for (var i = 0; i < count; i++) {
            manager.publishLocal(STREAM, PARTITION, PAYLOAD, 1L, epoch).onFailure(cause -> fail(cause.message()));
        }
    }

    private void live(long offset, Epoch epoch) {
        manager.appendRecovered(STREAM, PARTITION, offset, PAYLOAD, 1L, epoch).onFailure(cause -> fail(cause.message()));
    }

    private void caughtUp(long offset) {
        manager.appendRecovered(STREAM, PARTITION, offset, PAYLOAD, 1L).onFailure(cause -> fail(cause.message()));
    }

    private StreamPartitionManager.InstalledSlice installed(long from, long to, ProvenanceEntry... slice) {
        return manager.installProvenance(STREAM, PARTITION, from, to, List.of(slice)).unwrap();
    }

    /// A directory where the history writes its temp file: every history write then fails.
    private void blockHistoryWrites() throws IOException {
        try (var files = Files.walk(walDir)) {
            var sidecar = files.filter(path -> path.getFileName().toString().endsWith(".epochs")).findFirst().orElseThrow();
            var temp = sidecar.resolveSibling(sidecar.getFileName() + ".tmp");

            Files.createDirectories(temp.resolve("blocked"));
        }
    }

    private Result<?> install(long from, long to, ProvenanceEntry... slice) {
        return manager.installProvenance(STREAM, PARTITION, from, to, List.of(slice));
    }

    private List<ProvenanceEntry> history() {
        return manager.epochHistory(STREAM, PARTITION).unwrap();
    }

    private static ProvenanceEntry at(Epoch epoch, long start) {
        return ProvenanceEntry.provenanceEntry(epoch, Option.none(), start);
    }
}
