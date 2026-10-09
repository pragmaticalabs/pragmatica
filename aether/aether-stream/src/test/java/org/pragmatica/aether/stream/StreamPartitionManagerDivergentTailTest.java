// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.file.Path;
import java.util.List;

import org.pragmatica.aether.slice.ReplicationFactors;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #1730 phase 2: the replica-side repair of a divergent tail (`StreamPartitionManager#repairDivergence`). A replica
/// that was handed a different record at an offset it already holds is quarantined there; the repair cuts the WAL, the
/// epoch history and the ring back to the offset below it and lifts the quarantine, all inside the ring's ordered
/// section.
class StreamPartitionManagerDivergentTailTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;

    @TempDir
    Path walDir;

    private StreamPartitionManager manager;
    private final java.util.List<OperatorWarning> warnings = new java.util.concurrent.CopyOnWriteArrayList<>();
    /// The events of #2080 (a cut preserved its records in a recovery segment), kept apart from [#warnings]: those tests pin the
    /// DATA-LOSS warning, which the preserved event does not replace, so they must keep seeing exactly that one and nothing else.
    private final java.util.List<OperatorWarning> preserved = new java.util.concurrent.CopyOnWriteArrayList<>();

    private OperatorWarningSink lossWarningsOnly() {
        return OperatorWarningSink.handingOffTo(warning -> (warning.code() == OperatorWarningCode.STREAM_DIVERGENT_TAIL_PRESERVED
                                                            ? preserved
                                                            : warnings).add(warning));
    }

    @BeforeEach
    void setUp() {
        manager = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));
        manager.createStream(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));
        manager.operatorWarnings(lossWarningsOnly());
        for (var i = 0; i < 10; i++) {
            manager.appendRecovered(STREAM, PARTITION, i, ("replica-" + i).getBytes(UTF_8), 1000L + i, org.pragmatica.aether.slice.generation.Epoch.ZERO).unwrap();
        }
        manager.syncReplicated(STREAM, PARTITION).await();
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    @Test
    void repairDivergence_cutsTheWalAndTheRing_liftsTheQuarantine_andAcceptsTheOwnersRecords() {
        quarantineAt(5);

        var cut = manager.repairDivergence(STREAM, PARTITION, _ -> true).unwrap().unwrap();

        assertThat(cut.keptThrough()).isEqualTo(4L);
        assertThat(cut.removed()).isEqualTo(5L);
        assertThat(cut.firstRemoved()).isEqualTo(5L);
        assertThat(cut.lastRemoved()).isEqualTo(9L);
        assertThat(manager.quarantinedAt(STREAM, PARTITION).isEmpty()).isTrue();
        assertThat(texts()).containsExactly("replica-0", "replica-1", "replica-2", "replica-3", "replica-4");
        assertThat(manager.appendRecovered(STREAM, PARTITION, 5, "owner-5".getBytes(UTF_8), 1005L, org.pragmatica.aether.slice.generation.Epoch.ZERO).isSuccess()).isTrue();
    }

    /// The durability barrier remembers the last replicated write; one that survived the cut would, at the next sync,
    /// mark offsets the cut removed as durable and visible, so the owner's records appended there would be readable
    /// before they are durable on this node.
    @Test
    void repairDivergence_forgetsTheBarrierWriteAboveTheCut_soNothingBecomesVisibleBeforeItIsDurable() {
        quarantineAt(5);
        manager.repairDivergence(STREAM, PARTITION, _ -> true).unwrap();

        manager.syncReplicated(STREAM, PARTITION).await();
        manager.appendRecovered(STREAM, PARTITION, 5, "owner-5".getBytes(UTF_8), 1005L, org.pragmatica.aether.slice.generation.Epoch.ZERO).unwrap();

        assertThat(manager.readLocal(STREAM, PARTITION, 5, 10).unwrap())
            .as("offset 5 is appended but its WAL frame is not yet committed: not readable").isEmpty();

        manager.syncReplicated(STREAM, PARTITION).await();

        assertThat(manager.readLocal(STREAM, PARTITION, 5, 10).unwrap()).hasSize(1);
    }

    /// B2 (v1890): the authority is evaluated INSIDE the cut. When it no longer holds (the committed owner changed, or its
    /// epoch is not later than the records to be removed) nothing is removed, the quarantine stands, and the cause is typed.
    @Test
    void repairDivergence_whenTheAuthorityNoLongerHolds_removesNothing_andKeepsTheQuarantine() {
        quarantineAt(5);

        var refused = manager.repairDivergence(STREAM, PARTITION, _ -> false);

        assertThat(refused.isFailure()).isTrue();
        refused.onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.RepairNotAuthorized.class));
        assertThat(texts()).as("nothing removed").hasSize(10);
        assertThat(manager.quarantinedAt(STREAM, PARTITION).isPresent()).as("quarantine stands").isTrue();
        assertThat(manager.repairDivergence(STREAM, PARTITION, _ -> true).unwrap().isPresent()).as("control: with authority the same cut proceeds").isTrue();
    }

    /// B2 (v1890): never below the sealed floor. Offsets at or below the last sealed offset are durable in segments, so a
    /// divergence found there is refused: the copy keeps its records and the quarantine.
    @Test
    void repairDivergence_belowTheSealedFloor_isRefused() {
        var floor = new java.util.concurrent.atomic.AtomicLong(-1L);
        var sealed = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir.resolve("sealed")), (_, _) -> floor.get());

        sealed.createStream(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));
        for (var i = 0; i < 10; i++) {
            sealed.appendRecovered(STREAM, PARTITION, i, ("replica-" + i).getBytes(UTF_8), 1000L + i, org.pragmatica.aether.slice.generation.Epoch.ZERO).unwrap();
        }
        sealed.syncReplicated(STREAM, PARTITION).await();
        floor.set(7L);
        sealed.appendRecovered(STREAM, PARTITION, 5, "different".getBytes(UTF_8), 1005L, org.pragmatica.aether.slice.generation.Epoch.ZERO);

        var below = sealed.repairDivergence(STREAM, PARTITION, _ -> true);

        assertThat(below.isFailure()).as("divergence at 5 is below the sealed floor 7").isTrue();
        assertThat(sealed.readAppended(STREAM, PARTITION, 0, 20).unwrap()).hasSize(10);
        sealed.close();
    }

    @Test
    void repairDivergence_withNothingQuarantined_doesNothing() {
        assertThat(manager.repairDivergence(STREAM, PARTITION, _ -> true).unwrap().isEmpty()).isTrue();
        assertThat(texts()).hasSize(10);
    }

    /// confirmation_factor defaults to 2 here: the discarded records were never acknowledged, so the cut is routine and
    /// raises no operator warning.
    @Test
    void repairDivergence_atConfirmationFactor2_raisesNoOperatorWarning() {
        quarantineAt(5);
        manager.repairDivergence(STREAM, PARTITION, _ -> true).unwrap();
        manager.quarantineView().repairSettled(STREAM, PARTITION);
        quietPeriod();

        assertThat(warnings).isEmpty();
    }

    /// With confirmation_factor 1 the old owner's own fsync was the acknowledgement, so the discarded records may have
    /// been acknowledged: the operator is told exactly which offsets are lost.
    @Test
    void repairDivergence_atConfirmationFactor1_raisesOneWarning_naming_theDiscardedRange() {
        var one = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir.resolve("cf1")));

        one.createStream(StreamConfig.streamConfig("single").withReplication(ReplicationFactors.replicationFactors(1, 1).unwrap()))
           .onFailure(cause -> fail(cause.message()));
        one.operatorWarnings(lossWarningsOnly());
        for (var i = 0; i < 8; i++) {
            one.appendRecovered("single", PARTITION, i, ("r" + i).getBytes(UTF_8), 1000L + i, org.pragmatica.aether.slice.generation.Epoch.epoch(1L, 2L, 3L)).unwrap();
        }
        one.syncReplicated("single", PARTITION).await();
        one.appendRecovered("single", PARTITION, 3, "different".getBytes(UTF_8), 1003L, org.pragmatica.aether.slice.generation.Epoch.epoch(1L, 2L, 3L));

        one.repairDivergence("single", PARTITION, _ -> true).unwrap();
        quietPeriod();
        assertThat(warnings).as("nothing is reported before the repair settles").isEmpty();

        one.quarantineView().repairSettled("single", PARTITION);
        awaitWarnings(1);

        assertThat(warnings).singleElement().satisfies(warning -> {
            assertThat(warning.code()).isEqualTo(OperatorWarningCode.STREAM_DIVERGENT_TAIL_TRUNCATED);
            assertThat(warning.message()).contains("[3, 7]").contains("5 events").contains("epoch 1:2:3").contains("ackedAtOwner=true");
        });
        one.close();
    }

    /// B9 residual: a durable cut whose repair never settles inside a RUNNING process is reported within the bound, with the range
    /// known so far and repairSettled=false; if it later settles with a LARGER range it is reported once more (a distinct event),
    /// and with the same range it is not repeated.
    @Test
    void repairDivergence_neverSettling_isReportedWithinTheBound_andOnceMoreOnlyIfTheRangeGrew() {
        var one = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir.resolve("cf1-bound")));

        one.createStream(StreamConfig.streamConfig("single").withReplication(ReplicationFactors.replicationFactors(1, 1).unwrap()))
           .onFailure(cause -> fail(cause.message()));
        one.operatorWarnings(lossWarningsOnly());
        one.repairReportBound(org.pragmatica.lang.io.TimeSpan.timeSpan(300).millis());
        for (var i = 0; i < 12; i++) {
            one.appendRecovered("single", PARTITION, i, ("r" + i).getBytes(UTF_8), 1000L + i, org.pragmatica.aether.slice.generation.Epoch.epoch(1L, 2L, 3L)).unwrap();
        }
        one.syncReplicated("single", PARTITION).await();
        one.appendRecovered("single", PARTITION, 9, "different".getBytes(UTF_8), 1009L, org.pragmatica.aether.slice.generation.Epoch.epoch(1L, 2L, 3L));
        one.repairDivergence("single", PARTITION, _ -> true).unwrap();

        awaitWarnings(1);

        assertThat(warnings).as("reported inside the bound although nothing settled").singleElement()
                            .satisfies(warning -> assertThat(warning.message()).contains("[9, 11]").contains("repairSettled=false"));

        one.appendRecovered("single", PARTITION, 5, "different".getBytes(UTF_8), 1005L, org.pragmatica.aether.slice.generation.Epoch.epoch(1L, 2L, 3L));
        one.repairDivergence("single", PARTITION, _ -> true).unwrap();
        one.quarantineView().repairSettled("single", PARTITION);
        awaitWarnings(2);
        quietPeriod();

        assertThat(warnings).as("the larger range is reported once more, as a distinct settled event").hasSize(2);
        assertThat(warnings.get(1).message()).contains("[5, 11]").contains("repairSettled=true");
        one.close();
    }

    /// Control: the same range already reported while unsettled is not repeated on settle.
    @Test
    void repairDivergence_reportedUnsettled_thenSettledWithTheSameRange_isNotRepeated() {
        var one = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir.resolve("cf1-same")));

        one.createStream(StreamConfig.streamConfig("single").withReplication(ReplicationFactors.replicationFactors(1, 1).unwrap()))
           .onFailure(cause -> fail(cause.message()));
        one.operatorWarnings(lossWarningsOnly());
        one.repairReportBound(org.pragmatica.lang.io.TimeSpan.timeSpan(300).millis());
        for (var i = 0; i < 8; i++) {
            one.appendRecovered("single", PARTITION, i, ("r" + i).getBytes(UTF_8), 1000L + i, org.pragmatica.aether.slice.generation.Epoch.epoch(1L, 2L, 3L)).unwrap();
        }
        one.syncReplicated("single", PARTITION).await();
        one.appendRecovered("single", PARTITION, 3, "different".getBytes(UTF_8), 1003L, org.pragmatica.aether.slice.generation.Epoch.epoch(1L, 2L, 3L));
        one.repairDivergence("single", PARTITION, _ -> true).unwrap();
        awaitWarnings(1);
        one.quarantineView().repairSettled("single", PARTITION);
        quietPeriod();

        assertThat(warnings).as("settled with the range already reported: no second event").hasSize(1);
        one.close();
    }

    /// B9: a timer left from a repair that already settled must not report a NEWER repair earlier than its own bound. Repair 1 cuts
    /// and settles at once (its timer is still pending); repair 2 cuts ~200 ms later; repair 1's timer fires at 400 ms, when repair 2
    /// is only 200 ms old: nothing may be reported then, and repair 2 reports at its own bound.
    @Test
    void repairDivergence_aStaleTimerFromASettledRepair_doesNotReportANewerRepairEarly() throws InterruptedException {
        var one = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir.resolve("cf1-stale")));

        one.createStream(StreamConfig.streamConfig("single").withReplication(ReplicationFactors.replicationFactors(1, 1).unwrap()))
           .onFailure(cause -> fail(cause.message()));
        one.operatorWarnings(lossWarningsOnly());
        one.repairReportBound(org.pragmatica.lang.io.TimeSpan.timeSpan(400).millis());
        for (var i = 0; i < 12; i++) {
            one.appendRecovered("single", PARTITION, i, ("r" + i).getBytes(UTF_8), 1000L + i, org.pragmatica.aether.slice.generation.Epoch.epoch(1L, 2L, 3L)).unwrap();
        }
        one.syncReplicated("single", PARTITION).await();
        one.appendRecovered("single", PARTITION, 10, "different".getBytes(UTF_8), 1010L, org.pragmatica.aether.slice.generation.Epoch.epoch(1L, 2L, 3L));
        one.repairDivergence("single", PARTITION, _ -> true).unwrap();
        one.quarantineView().repairSettled("single", PARTITION);
        awaitWarnings(1);
        assertThat(warnings).as("repair 1 settled: reported once, settled").singleElement();
        warnings.clear();
        Thread.sleep(200);
        one.appendRecovered("single", PARTITION, 6, "different".getBytes(UTF_8), 1006L, org.pragmatica.aether.slice.generation.Epoch.epoch(1L, 2L, 3L));
        one.repairDivergence("single", PARTITION, _ -> true).unwrap();
        Thread.sleep(300);

        assertThat(warnings).as("repair 1's timer fired at 400 ms; repair 2 is 300 ms old, inside its own bound: no report").isEmpty();

        awaitWarnings(1);

        assertThat(warnings).as("repair 2 reports at its own bound").singleElement()
                            .satisfies(warning -> assertThat(warning.message()).contains("repairSettled=false"));
        one.close();
    }

    /// I (v1890): the witness of what a cut discards is written BEFORE the cut, and a cut whose witness cannot be made durable is
    /// REFUSED: the records stay, the copy stays quarantined, nothing is reported as lost.
    @Test
    void repairDivergence_whenTheWitnessCannotBeWritten_refusesTheCut_andLosesNothing() throws Exception {
        var one = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir.resolve("cf1-witness")));

        one.createStream(StreamConfig.streamConfig("single").withReplication(ReplicationFactors.replicationFactors(1, 1).unwrap()))
           .onFailure(cause -> fail(cause.message()));
        one.operatorWarnings(lossWarningsOnly());
        for (var i = 0; i < 8; i++) {
            one.appendRecovered("single", PARTITION, i, ("r" + i).getBytes(UTF_8), 1000L + i, org.pragmatica.aether.slice.generation.Epoch.epoch(1L, 2L, 3L)).unwrap();
        }
        one.syncReplicated("single", PARTITION).await();
        one.appendRecovered("single", PARTITION, 3, "different".getBytes(UTF_8), 1003L, org.pragmatica.aether.slice.generation.Epoch.epoch(1L, 2L, 3L));
        blockWitnessFiles(walDir.resolve("cf1-witness"));

        var refused = one.repairDivergence("single", PARTITION, _ -> true);

        assertThat(refused.isFailure()).as("the cut is refused when its witness cannot be written").isTrue();
        refused.onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.RepairWitnessFailed.class));
        assertThat(one.readAppended("single", PARTITION, 0, 20).unwrap()).as("nothing was cut").hasSize(8);
        assertThat(one.quarantinedAt("single", PARTITION).isPresent()).as("the copy stays quarantined").isTrue();
        quietPeriod();
        assertThat(warnings).as("no loss, no report").isEmpty();
        one.close();
    }

    /// I: a witness that exists but cannot be read (a torn or foreign file) is reported at reopen as an UNKNOWN range, never dropped.
    @Test
    void aTornWitness_isReportedAtReopenAsAnUnknownRange_andRemoved() throws Exception {
        var path = walDir.resolve("cf1-torn");
        var first = streamPartitionManager(Long.MAX_VALUE, Option.some(path));

        first.createStream(StreamConfig.streamConfig("single").withReplication(ReplicationFactors.replicationFactors(1, 1).unwrap()))
             .onFailure(cause -> fail(cause.message()));
        first.appendRecovered("single", PARTITION, 0, "r0".getBytes(UTF_8), 1000L, org.pragmatica.aether.slice.generation.Epoch.epoch(1L, 2L, 3L)).unwrap();
        first.syncReplicated("single", PARTITION).await();
        first.close();
        var wal = java.nio.file.Files.walk(path).filter(java.nio.file.Files::isRegularFile).filter(file -> file.getFileName().toString().endsWith(".wal")).findFirst().orElseThrow();

        java.nio.file.Files.writeString(wal.resolveSibling(wal.getFileName() + ".pending-cut"), "3 5 xx");
        var restarted = streamPartitionManager(Long.MAX_VALUE, Option.some(path));

        restarted.createStream(StreamConfig.streamConfig("single").withReplication(ReplicationFactors.replicationFactors(1, 1).unwrap()))
                 .onFailure(cause -> fail(cause.message()));
        restarted.operatorWarnings(lossWarningsOnly());
        awaitWarnings(1);

        assertThat(warnings).singleElement().satisfies(warning -> assertThat(warning.message()).contains("unknown range"));
        assertThat(java.nio.file.Files.exists(wal.resolveSibling(wal.getFileName() + ".pending-cut"))).as("the witness is removed once reported").isFalse();
        restarted.close();
    }

    /// Makes every witness write under `root` fail: a directory occupies each file's temporary witness name.
    private static void blockWitnessFiles(java.nio.file.Path root) throws java.io.IOException {
        try (var files = java.nio.file.Files.walk(root)) {
            for (var file : files.filter(java.nio.file.Files::isRegularFile).toList()) {
                java.nio.file.Files.createDirectories(file.resolveSibling(file.getFileName() + ".pending-cut.tmp"));
            }
        }
    }

    /// B9 (v1890): a divergence older than the compared window is cut back one window per run. The operator is told ONCE, when the
    /// repair settles, with the FINAL range (from the last cut's first offset up to the original local head), not once per step.
    @Test
    void repairDivergence_inSeveralSteps_atConfirmationFactor1_raisesOneWarning_withTheFinalRange() {
        var one = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir.resolve("cf1-steps")));

        one.createStream(StreamConfig.streamConfig("single").withReplication(ReplicationFactors.replicationFactors(1, 1).unwrap()))
           .onFailure(cause -> fail(cause.message()));
        one.operatorWarnings(lossWarningsOnly());
        for (var i = 0; i < 12; i++) {
            one.appendRecovered("single", PARTITION, i, ("r" + i).getBytes(UTF_8), 1000L + i, org.pragmatica.aether.slice.generation.Epoch.ZERO).unwrap();
        }
        one.syncReplicated("single", PARTITION).await();
        one.appendRecovered("single", PARTITION, 9, "different".getBytes(UTF_8), 1009L, org.pragmatica.aether.slice.generation.Epoch.ZERO);
        one.repairDivergence("single", PARTITION, _ -> true).unwrap();
        one.appendRecovered("single", PARTITION, 5, "different".getBytes(UTF_8), 1005L, org.pragmatica.aether.slice.generation.Epoch.ZERO);
        one.repairDivergence("single", PARTITION, _ -> true).unwrap();
        quietPeriod();

        assertThat(warnings).as("two steps, nothing reported yet").isEmpty();

        one.quarantineView().repairSettled("single", PARTITION);
        awaitWarnings(1);
        quietPeriod();

        assertThat(warnings).as("ONE event for the whole truncation").singleElement().satisfies(warning -> assertThat(warning.message()).contains("[5, 11]").contains("7 events"));
        one.close();
    }

    /// #2080: the cut writes exactly the records it removes -- offset, timestamp, payload and the owner epoch each was written
    /// under -- to a recovery segment beside the WAL, and names the stream, partition, range and file in an operator event.
    @Test
    void repairDivergence_preservesExactlyTheCutRecords_withEpochs_andRaisesTheEvent() throws Exception {
        var path = walDir.resolve("preserve");
        var one = streamPartitionManager(Long.MAX_VALUE, Option.some(path));
        var first = org.pragmatica.aether.slice.generation.Epoch.epoch(1L, 2L, 3L);
        var second = org.pragmatica.aether.slice.generation.Epoch.epoch(1L, 3L, 4L);

        one.createStream(StreamConfig.streamConfig("single")).onFailure(cause -> fail(cause.message()));
        one.operatorWarnings(lossWarningsOnly());
        for (var i = 0; i < 10; i++) {
            one.appendRecovered("single", PARTITION, i, ("r" + i).getBytes(UTF_8), 2000L + i, i < 7 ? first : second).unwrap();
        }
        one.syncReplicated("single", PARTITION).await();
        one.appendRecovered("single", PARTITION, 5, "different".getBytes(UTF_8), 2005L, second);
        assertThat(one.quarantinedAt("single", PARTITION).or(-1L)).isEqualTo(5L);

        var cut = one.repairDivergence("single", PARTITION, _ -> true).unwrap().unwrap();
        var segments = segmentsUnder(path);

        assertThat(segments).as("one segment for the one cut").hasSize(1);
        var contents = RecoverySegment.read(segments.getFirst()).unwrap();

        assertThat(contents.streamName()).isEqualTo("single");
        assertThat(contents.partition()).isEqualTo(PARTITION);
        assertThat(contents.first()).isEqualTo(cut.firstRemoved()).isEqualTo(5L);
        assertThat(contents.last()).isEqualTo(cut.lastRemoved()).isEqualTo(9L);
        assertThat(contents.entries()).extracting(RecoverySegment.Entry::offset).containsExactly(5L, 6L, 7L, 8L, 9L);
        assertThat(contents.entries()).extracting(entry -> new String(entry.payload(), UTF_8))
                                      .as("the records as the copy held them, not the owner's different one")
                                      .containsExactly("r5", "r6", "r7", "r8", "r9");
        assertThat(contents.entries()).extracting(RecoverySegment.Entry::timestampMillis).containsExactly(2005L, 2006L, 2007L, 2008L, 2009L);
        var epochs = contents.entries().stream().map(entry -> entry.epoch().or("none")).toList();

        assertThat(epochs.get(0)).as("5 and 6 were written under the first epoch").isEqualTo(epochs.get(1)).isNotEqualTo("none");
        assertThat(epochs.get(2)).as("7..9 under the second").isEqualTo(epochs.get(4)).isNotEqualTo(epochs.get(1)).isNotEqualTo("none");
        assertThat(one.readAppended("single", PARTITION, 0, 100).unwrap()).as("and the live stream lost them").hasSize(5);
        awaitPreserved(1);
        assertThat(preserved).singleElement().satisfies(event -> {
            assertThat(event.code()).isEqualTo(OperatorWarningCode.STREAM_DIVERGENT_TAIL_PRESERVED);
            assertThat(event.message()).contains("single[0]").contains("[5, 9]").contains("5 events").contains(segments.getFirst().getFileName().toString());
        });
        one.close();
    }

    /// #2080: a segment that cannot be made durable REFUSES the cut. Nothing is removed, the copy stays quarantined, no event claims a
    /// preserved loss, and no half-written segment or witness is left; with the obstacle gone the same cut then proceeds.
    @Test
    void repairDivergence_whenTheRecoverySegmentCannotBeWritten_refusesTheCut_andRemovesNothing() throws Exception {
        var path = walDir.resolve("preserve-refused");
        var one = streamPartitionManager(Long.MAX_VALUE, Option.some(path));

        one.createStream(StreamConfig.streamConfig("single")).onFailure(cause -> fail(cause.message()));
        one.operatorWarnings(lossWarningsOnly());
        for (var i = 0; i < 8; i++) {
            one.appendRecovered("single", PARTITION, i, ("r" + i).getBytes(UTF_8), 1000L + i, org.pragmatica.aether.slice.generation.Epoch.epoch(1L, 2L, 3L)).unwrap();
        }
        one.syncReplicated("single", PARTITION).await();
        one.appendRecovered("single", PARTITION, 3, "different".getBytes(UTF_8), 1003L, org.pragmatica.aether.slice.generation.Epoch.epoch(1L, 2L, 3L));
        var obstacles = blockRecoverySegments(path);

        assertThat(obstacles).as("the obstacle is in place").isNotEmpty();

        var refused = one.repairDivergence("single", PARTITION, _ -> true);

        assertThat(refused.isFailure()).as("the cut is refused when its records cannot be preserved").isTrue();
        refused.onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.RepairPreserveFailed.class));
        assertThat(one.readAppended("single", PARTITION, 0, 20).unwrap()).as("nothing was cut").hasSize(8);
        assertThat(one.quarantinedAt("single", PARTITION).isPresent()).as("the copy stays quarantined").isTrue();
        assertThat(segmentsUnder(path)).as("no segment under its final name").isEmpty();
        assertThat(filesUnder(path, ".pending-cut")).as("the witness of a cut that did not happen is restored away").isEmpty();
        quietPeriod();
        assertThat(preserved).as("no event claims a preserved loss").isEmpty();
        assertThat(warnings).isEmpty();

        for (var obstacle : obstacles) {
            java.nio.file.Files.delete(obstacle);
        }

        assertThat(one.repairDivergence("single", PARTITION, _ -> true).unwrap().isPresent()).as("control: with the obstacle gone the cut proceeds").isTrue();
        assertThat(segmentsUnder(path)).hasSize(1);
        one.close();
    }

    /// #2080: an ephemeral copy (no WAL, no volume) has nothing to retain; the cut proceeds as before and claims no segment.
    @Test
    void repairDivergence_withoutAWal_cutsAsBefore_andRaisesNoPreservedEvent() {
        var ephemeral = streamPartitionManager(Long.MAX_VALUE, Option.none());

        ephemeral.createStream(StreamConfig.streamConfig("single")).onFailure(cause -> fail(cause.message()));
        ephemeral.operatorWarnings(lossWarningsOnly());
        for (var i = 0; i < 8; i++) {
            ephemeral.appendRecovered("single", PARTITION, i, ("r" + i).getBytes(UTF_8), 1000L + i, org.pragmatica.aether.slice.generation.Epoch.ZERO).unwrap();
        }
        ephemeral.appendRecovered("single", PARTITION, 3, "different".getBytes(UTF_8), 1003L, org.pragmatica.aether.slice.generation.Epoch.ZERO);

        assertThat(ephemeral.repairDivergence("single", PARTITION, _ -> true).unwrap().isPresent()).isTrue();
        assertThat(ephemeral.readAppended("single", PARTITION, 0, 20).unwrap()).hasSize(3);
        quietPeriod();
        assertThat(preserved).isEmpty();
        ephemeral.close();
    }

    private static List<Path> segmentsUnder(Path root) throws java.io.IOException {
        return filesUnder(root, ".seg");
    }

    private static List<Path> filesUnder(Path root, String contains) throws java.io.IOException {
        try (var files = java.nio.file.Files.walk(root)) {
            return files.filter(java.nio.file.Files::isRegularFile)
                        .filter(file -> file.getFileName().toString().contains(contains))
                        .toList();
        }
    }

    /// Puts a directory where the segment's temporary file goes: a write there fails with the platform's own error, whoever runs
    /// the test.
    private static List<Path> blockRecoverySegments(Path root) throws java.io.IOException {
        var blocked = new java.util.ArrayList<Path>();

        try (var files = java.nio.file.Files.walk(root)) {
            for (var wal : files.filter(java.nio.file.Files::isRegularFile).filter(file -> file.getFileName().toString().endsWith(".wal")).toList()) {
                blocked.add(java.nio.file.Files.createDirectories(RecoverySegment.temporaryFor(wal)));
            }
        }

        return blocked;
    }

    private void awaitPreserved(int expected) {
        var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);

        while (preserved.size() < expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    /// The sink hands warnings to a virtual thread; wait for the expected number to arrive.
    private void awaitWarnings(int expected) {
        var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);

        while (warnings.size() < expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    /// A negative assertion on the asynchronous sink: its positive control is the confirmation-factor-1 test, which
    /// shows the same path delivers within milliseconds.
    private static void quietPeriod() {
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /// Offered a different record at offset 5, which the replica already holds, the ring records the conflict and the
    /// partition is quarantined at 5.
    private void quarantineAt(long offset) {
        manager.appendRecovered(STREAM, PARTITION, offset, "owner-different".getBytes(UTF_8), 1000L + offset, org.pragmatica.aether.slice.generation.Epoch.ZERO);

        assertThat(manager.quarantinedAt(STREAM, PARTITION).or(-1L)).isEqualTo(offset);
    }

    private List<String> texts() {
        return manager.readAppended(STREAM, PARTITION, 0, 100)
                      .unwrap()
                      .stream()
                      .map(event -> new String(event.data(), UTF_8))
                      .toList();
    }
}
