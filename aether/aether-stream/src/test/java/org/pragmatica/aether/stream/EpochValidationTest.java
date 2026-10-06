// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.EpochStart;
import org.pragmatica.lang.Result;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1730 phase 2 / #1873 (KIP-320): a consumer that read under epoch `Ec` may keep reading from cursor `c` only while no
/// epoch that followed `Ec` began below `c`; otherwise it gets a typed divergence naming the offset the new lineage
/// started at, and re-reads from there. One pure function decides, from the committed epoch and its recorded starts.
class EpochValidationTest {
    private static final String STREAM = "orders";
    private static final Epoch E1 = Epoch.epoch(1L, 1L, 1L);
    private static final Epoch E2 = Epoch.epoch(1L, 1L, 2L);
    private static final Epoch E3 = Epoch.epoch(1L, 1L, 3L);
    private static final Epoch E4 = Epoch.epoch(1L, 1L, 4L);

    /// The consumer's own epoch is the owner's: nothing re-assigned offsets since, so any cursor is valid.
    @Test
    void sameEpoch_isAdmitted_whateverTheCursor() {
        assertThat(check(E2, List.of(start(E1, 0), start(E2, 5)), E2, 500L)).isEqualTo(E2);
    }

    @Test
    void noClaim_isAdmitted_andAdoptsTheOwnersEpoch() {
        assertThat(check(E2, List.of(start(E1, 0), start(E2, 5)), Epoch.ZERO, 500L)).isEqualTo(E2);
    }

    /// The #1873 case: the consumer read 3 and 4 under E1 (cursor 5), the owner restarted without a WAL and began E2 at 3.
    @Test
    void olderEpoch_cursorPastTheNextEpochsStart_divergesToThatStart() {
        var diverged = divergence(E2, List.of(start(E1, 0), start(E2, 3)), E1, 5L);

        assertThat(diverged.ownerEpoch()).isEqualTo(E2);
        assertThat(diverged.resumeAt()).as("re-read from where the new lineage began").isEqualTo(3L);
    }

    /// The cursor sits exactly at the new epoch's start: the consumer read nothing the new lineage re-assigned.
    @Test
    void olderEpoch_cursorAtTheNextEpochsStart_isAdmitted() {
        assertThat(check(E2, List.of(start(E1, 0), start(E2, 3)), E1, 3L)).isEqualTo(E2);
        assertThat(check(E2, List.of(start(E1, 0), start(E2, 3)), E1, 2L)).isEqualTo(E2);
    }

    /// A consumer asleep across two bumps: against the LATEST start alone (E3 at 20) cursor 10 passes, but E2 began at 8
    /// and re-assigned offsets 8 and 9 that this consumer already read under E1.
    @Test
    void consumerAsleepAcrossTwoBumps_isCheckedAgainstTheFirstEpochThatFollowedItsOwn() {
        var starts = List.of(start(E1, 0), start(E2, 8), start(E3, 20));

        assertThat(divergence(E3, starts, E1, 10L).resumeAt()).isEqualTo(8L);
        assertThat(check(E3, starts, E2, 10L)).as("under E2 the cursor is below E3's start").isEqualTo(E3);
    }

    /// A consumer from a lineage NEWER than the owner serves: the read reached a stale owner and must be re-routed.
    @Test
    void newerEpochThanTheOwners_isAStaleReader() {
        var result = EpochValidation.admit(STREAM, 0, E1, List.of(start(E1, 0)), E2, 5L);

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.StaleEpochRead.class));
    }

    /// The owner has not committed the start of its current epoch yet: it is not activated for it, and serves nothing.
    @Test
    void ownerWhoseCurrentEpochHasNoCommittedStart_isNotActivated() {
        var result = EpochValidation.admit(STREAM, 0, E2, List.of(start(E1, 0)), E1, 0L);

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.OwnerNotActivated.class));
    }

    /// #1873, re-create: destroy removes only the stream's config, so the ownership record outlives the stream and a second life
    /// continues its epochs. The new life's first start is offset 0 and supersedes the earlier lives' starts, so a consumer of an
    /// EARLIER life is admitted only at cursor 0 (it read nothing); above it, the record holds an exact start of a later epoch at
    /// 0, so the loss of [0, cursor) is PROVEN and the consumer resumes at 0.
    @Test
    void consumerOfAnEarlierLife_isAdmittedAtZero_andAboveItResumesAtZeroWithAProvenLoss() {
        var fresh = List.of(start(E3, 0L));

        assertThat(EpochValidation.admit(STREAM, 0, E3, fresh, E1, 0L).isSuccess()).as("cursor 0 read nothing").isTrue();

        for (var cursor : new long[]{2L, 500L}) {
            var diverged = divergence(E3, fresh, E1, cursor);

            assertThat(diverged.resumeAt()).as("cursor " + cursor).isZero();
            assertThat(diverged.provenLossFrom()).as("an unfolded start of a later epoch at 0 proves the loss").isZero();
        }
    }

    /// The exact boundary stays exact: a consumer whose own epoch is kept is judged against the start that followed it.
    @Test
    void consumerWithinTheKeptHistory_getsAnExactBoundary() {
        var diverged = divergence(E2, List.of(start(E1, 0), start(E2, 3)), E1, 5L);

        assertThat(diverged.resumeAt()).isEqualTo(3L);
        assertThat(diverged.provenLossFrom()).isEqualTo(3L);
    }

    /// The core #1873 case: a no-WAL owner restarted with NOTHING sealed, so its new epoch begins at 0 and supersedes the
    /// consumer's own start. The records the group processed in [0, cursor) are proven gone.
    @Test
    void nothingSealedRestart_provesTheLossFromZero() {
        var diverged = divergence(E2, List.of(start(E2, 0L)), E1, 5L);

        assertThat(diverged.resumeAt()).isZero();
        assertThat(diverged.provenLossFrom()).isZero();
    }

    /// A restart below the consumer's own epoch start (it began at 10, the restart resumed at 5) superseded it: proven.
    @Test
    void restartBelowTheConsumersOwnStart_provesTheLossFromTheRestart() {
        var diverged = divergence(E2, List.of(start(E2, 5L)), E1, 15L);

        assertThat(diverged.resumeAt()).isEqualTo(5L);
        assertThat(diverged.provenLossFrom()).isEqualTo(5L);
    }

    /// A FOLDED oldest start (the cap dropped E1 and E2, the oldest dropped being E1 at 0) proves nothing for a consumer that
    /// began after E1, while a LATER exact start below its cursor still proves the loss from there; the resume stays the
    /// conservative lower bound.
    @Test
    void foldedOldest_isNotExactForAConsumerNewerThanItsFoldedFrom_butALaterExactStartStillProves() {
        var history = List.of(folded(E3, 0L, E1), start(E4, 105L));

        var proven = divergence(E4, history, E2, 110L);

        assertThat(proven.resumeAt()).as("the conservative lower bound").isZero();
        assertThat(proven.provenLossFrom()).as("E4 is an exact later start below the cursor").isEqualTo(105L);

        var unproven = divergence(E4, history, E2, 50L);

        assertThat(unproven.resumeAt()).isZero();
        assertThat(unproven.provenLossFrom()).as("only the folded entry lies below 50, and it proves nothing for E2").isEqualTo(StreamError.EpochDiverged.NO_PROVEN_LOSS);
    }

    @Test
    void purelyFolded_consumerNewerThanItsFoldedFrom_isInexact() {
        var diverged = divergence(E3, List.of(folded(E3, 0L, E1)), E2, 50L);

        assertThat(diverged.resumeAt()).isZero();
        assertThat(diverged.lossProven()).isFalse();
    }

    /// A folded entry IS exact for a consumer older than the epoch it folded from: that epoch began at its offset.
    @Test
    void foldedOldest_isExactForAConsumerOlderThanItsFoldedFrom() {
        var diverged = divergence(E4, List.of(folded(E4, 0L, E3)), E1, 50L);

        assertThat(diverged.provenLossFrom()).isZero();
    }

    @Test
    void foldedOldest_cursorAtItsOffset_isAdmitted() {
        assertThat(check(E3, List.of(folded(E3, 0L, E1)), E2, 0L)).isEqualTo(E3);
    }

    /// The record's own cap, end to end: 17 raw starts fold to 16, the oldest kept being (e11 at 100) folded from e10. A consumer
    /// at e10 with a cursor below the next exact start (e12 at 120) is inexact; above it the loss is proven from 120.
    @Test
    void aCappedRecord_isJudgedOnTheStartThatSupersedesTheConsumer() {
        var raw = java.util.stream.IntStream.range(0, 17).mapToObj(i -> start(Epoch.epoch(1L, 1L, 10L + i), 100L + i * 10L)).toList();
        var record = new org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue(new org.pragmatica.consensus.NodeId("n"),
                                                                                                         raw.getLast().epoch(),
                                                                                                         26L,
                                                                                                         org.pragmatica.hlc.HlcTimestamp.ZERO,
                                                                                                         null,
                                                                                                         1L,
                                                                                                         false,
                                                                                                         null,
                                                                                                         raw);
        var consumer = Epoch.epoch(1L, 1L, 10L);

        var below = divergence(record.ownerEpoch(), record.epochStarts(), consumer, 115L);

        assertThat(below.resumeAt()).isEqualTo(100L);
        assertThat(below.lossProven()).as("only the folded entry is below 115").isFalse();

        var above = divergence(record.ownerEpoch(), record.epochStarts(), consumer, 250L);

        assertThat(above.resumeAt()).isEqualTo(100L);
        assertThat(above.provenLossFrom()).as("e12 began at 120, after e10").isEqualTo(120L);
    }

    /// C2 (v1873 round 2): a consumer older than the oldest kept start must resume at or below the EARLIEST offset that could
    /// have been re-assigned since its epoch. The kept history begins at 100, so the oldest kept start is the lowest offset the
    /// later epochs can have re-assigned: cursor 250 resumes AT 100, never at 250 (which would skip [100, 250)).
    @Test
    void trimmedHistory_resumesAtTheOldestKeptStart_neverAtTheCursor() {
        var kept = keptHistory();
        var diverged = divergence(kept.getLast().epoch(), kept, E1, 250L);

        assertThat(diverged.resumeAt()).as("the lowest offset any kept epoch may have re-assigned").isEqualTo(100L);
        assertThat(diverged.provenLossFrom()).as("these starts are unfolded: the first is exact").isEqualTo(100L);
    }

    /// A consumer older than the history whose cursor is at or below the oldest kept start holds nothing any later epoch can
    /// have re-assigned: admitted, nothing to re-read.
    @Test
    void consumerOlderThanTheKeptHistory_atOrBelowTheOldestKeptStart_isAdmitted() {
        var kept = keptHistory();

        assertThat(check(kept.getLast().epoch(), kept, E1, 100L)).isEqualTo(kept.getLast().epoch());
        assertThat(check(kept.getLast().epoch(), kept, E1, 90L)).isEqualTo(kept.getLast().epoch());
        assertThat(divergence(kept.getLast().epoch(), kept, E1, 101L).resumeAt()).isEqualTo(100L);
    }

    private static EpochStart folded(Epoch epoch, long startOffset, Epoch from) {
        return new EpochStart(epoch, startOffset, from);
    }

    private static List<EpochStart> keptHistory() {
        return java.util.stream.IntStream.range(0, 16)
                                         .mapToObj(i -> start(Epoch.epoch(1L, 1L, 10L + i), 100L + i * 10L))
                                         .toList();
    }

    private static EpochStart start(Epoch epoch, long startOffset) {
        return new EpochStart(epoch, startOffset);
    }

    private static Epoch check(Epoch owner, List<EpochStart> starts, Epoch consumer, long cursor) {
        return EpochValidation.admit(STREAM, 0, owner, starts, consumer, cursor).unwrap();
    }

    private static StreamError.EpochDiverged divergence(Epoch owner, List<EpochStart> starts, Epoch consumer, long cursor) {
        var result = EpochValidation.admit(STREAM, 0, owner, starts, consumer, cursor);
        var holder = new StreamError.EpochDiverged[1];

        result.onFailure(cause -> holder[0] = (StreamError.EpochDiverged) cause);

        assertThat(holder[0]).as("expected a divergence, got %s", result).isNotNull();

        return holder[0];
    }
}
