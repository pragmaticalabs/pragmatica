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

    @BeforeEach
    void setUp() {
        manager = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));
        manager.createStream(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));
        manager.operatorWarnings(OperatorWarningSink.handingOffTo(warnings::add));
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
        one.operatorWarnings(OperatorWarningSink.handingOffTo(warnings::add));
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
        one.operatorWarnings(OperatorWarningSink.handingOffTo(warnings::add));
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
        one.operatorWarnings(OperatorWarningSink.handingOffTo(warnings::add));
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

    /// B9 (v1890): a divergence older than the compared window is cut back one window per run. The operator is told ONCE, when the
    /// repair settles, with the FINAL range (from the last cut's first offset up to the original local head), not once per step.
    @Test
    void repairDivergence_inSeveralSteps_atConfirmationFactor1_raisesOneWarning_withTheFinalRange() {
        var one = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir.resolve("cf1-steps")));

        one.createStream(StreamConfig.streamConfig("single").withReplication(ReplicationFactors.replicationFactors(1, 1).unwrap()))
           .onFailure(cause -> fail(cause.message()));
        one.operatorWarnings(OperatorWarningSink.handingOffTo(warnings::add));
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
