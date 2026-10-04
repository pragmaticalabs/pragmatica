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
        var result = EpochValidation.admit(STREAM, 0, E1, List.of(start(E1, 0)), E2, 5L, 100L);

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.StaleEpochRead.class));
    }

    /// The owner has not committed the start of its current epoch yet: it is not activated for it, and serves nothing.
    @Test
    void ownerWhoseCurrentEpochHasNoCommittedStart_isNotActivated() {
        var result = EpochValidation.admit(STREAM, 0, E2, List.of(start(E1, 0)), E1, 0L, 100L);

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.OwnerNotActivated.class));
    }

    /// #1873, re-create: destroy removes only the stream's config, so the ownership record outlives the stream and a second life
    /// continues its epochs. The new life's first start is offset 0 and supersedes the earlier lives' starts, so a consumer of an
    /// EARLIER life (older than the oldest kept start, which began at 0) is refused from the start of the new life, whatever its
    /// cursor: before, a consumer at E1 with a low cursor was admitted and skipped the new life's first records.
    @Test
    void consumerOfAnEarlierLife_isRefusedFromTheStartOfTheNewLife_whateverItsCursor() {
        var fresh = List.of(start(E3, 0L));

        for (var cursor : new long[]{0L, 2L, 500L}) {
            var result = EpochValidation.admit(STREAM, 0, E3, fresh, E1, cursor, 9L);

            assertThat(result.isFailure()).as("cursor " + cursor).isTrue();
            result.onFailure(cause -> assertThat(cause).isInstanceOfSatisfying(StreamError.EpochDiverged.class,
                                                                              diverged -> assertThat(diverged.resumeAt()).isZero()));
        }
    }

    /// Control for the rule above: a TRIMMED history of one life (the oldest kept start is above offset 0) is not a new life. A
    /// consumer older than it resumes at `min(cursor, head + 1)` (it may redeliver, it never skips), never at 0.
    @Test
    void trimmedHistoryOfTheSameLife_neverResumesFromZero() {
        var kept = java.util.stream.IntStream.range(0, 16).mapToObj(i -> start(Epoch.epoch(1L, 1L, 10L + i), 100L + i * 10L)).toList();
        var result = EpochValidation.admit(STREAM, 0, kept.getLast().epoch(), kept, E1, 250L, 300L);

        result.onFailure(cause -> assertThat(cause).isInstanceOfSatisfying(StreamError.EpochDiverged.class,
                                                                          diverged -> assertThat(diverged.resumeAt()).isEqualTo(250L)));
        assertThat(result.isFailure()).isTrue();
    }

    /// The record keeps only the newest starts. A consumer older than the oldest kept cannot be placed: it resumes from
    /// the head (the clamp's answer: it may redeliver, it never skips).
    @Test
    void consumerOlderThanTheKeptHistory_resumesFromTheHead() {
        var kept = java.util.stream.IntStream.range(0, 16)
                                             .mapToObj(i -> start(Epoch.epoch(1L, 1L, 10L + i), 100L + i * 10L))
                                             .toList();
        var latest = kept.getLast().epoch();
        var result = EpochValidation.admit(STREAM, 0, latest, kept, E1, 90L, 250L);

        result.onFailure(cause -> assertThat(cause).isInstanceOfSatisfying(StreamError.EpochDiverged.class,
                                                                          diverged -> assertThat(diverged.resumeAt()).isEqualTo(90L)));
        assertThat(result.isFailure()).isTrue();

        var above = EpochValidation.admit(STREAM, 0, latest, kept, E1, 400L, 250L);

        above.onFailure(cause -> assertThat(cause).isInstanceOfSatisfying(StreamError.EpochDiverged.class,
                                                                          diverged -> assertThat(diverged.resumeAt()).as("never past the head").isEqualTo(251L)));
        assertThat(above.isFailure()).isTrue();
    }

    private static EpochStart start(Epoch epoch, long startOffset) {
        return new EpochStart(epoch, startOffset);
    }

    private static Epoch check(Epoch owner, List<EpochStart> starts, Epoch consumer, long cursor) {
        return EpochValidation.admit(STREAM, 0, owner, starts, consumer, cursor, 1_000L).unwrap();
    }

    private static StreamError.EpochDiverged divergence(Epoch owner, List<EpochStart> starts, Epoch consumer, long cursor) {
        var result = EpochValidation.admit(STREAM, 0, owner, starts, consumer, cursor, 1_000L);
        var holder = new StreamError.EpochDiverged[1];

        result.onFailure(cause -> holder[0] = (StreamError.EpochDiverged) cause);

        assertThat(holder[0]).as("expected a divergence, got %s", result).isNotNull();

        return holder[0];
    }
}
