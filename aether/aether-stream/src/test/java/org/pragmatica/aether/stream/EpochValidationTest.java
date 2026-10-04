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
    /// EARLIER life (older than the oldest kept start, at 0) is admitted only at cursor 0 (it read nothing): any cursor above is
    /// told to resume AT 0, the lowest offset the new life may have re-assigned, with the boundary marked as not exact.
    @Test
    void consumerOfAnEarlierLife_isAdmittedAtZero_andResumesAtZeroAboveIt() {
        var fresh = List.of(start(E3, 0L));

        assertThat(EpochValidation.admit(STREAM, 0, E3, fresh, E1, 0L).isSuccess()).as("cursor 0 read nothing").isTrue();

        for (var cursor : new long[]{2L, 500L}) {
            var diverged = divergence(E3, fresh, E1, cursor);

            assertThat(diverged.resumeAt()).as("cursor " + cursor).isZero();
            assertThat(diverged.boundaryKnown()).as("the boundary of a consumer older than the history is not exact").isFalse();
        }
    }

    /// The exact boundary stays exact: a consumer whose own epoch is kept is judged against the start that followed it.
    @Test
    void consumerWithinTheKeptHistory_getsAnExactBoundary() {
        assertThat(divergence(E2, List.of(start(E1, 0), start(E2, 3)), E1, 5L).boundaryKnown()).isTrue();
    }

    /// C2 (v1873 round 2): a consumer older than the oldest kept start must resume at or below the EARLIEST offset that could
    /// have been re-assigned since its epoch. The kept history begins at 100, so the oldest kept start is the lowest offset the
    /// later epochs can have re-assigned: cursor 250 resumes AT 100, never at 250 (which would skip [100, 250)).
    @Test
    void trimmedHistory_resumesAtTheOldestKeptStart_neverAtTheCursor() {
        var kept = keptHistory();
        var diverged = divergence(kept.getLast().epoch(), kept, E1, 250L);

        assertThat(diverged.resumeAt()).as("the lowest offset any kept epoch may have re-assigned").isEqualTo(100L);
        assertThat(diverged.boundaryKnown()).isFalse();
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
