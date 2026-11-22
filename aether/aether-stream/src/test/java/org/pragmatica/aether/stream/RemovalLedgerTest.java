// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.stream.RemovalCause;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;

/// T-C3 property arm and makers (M-2r-1): [RemovalLedger#answer] against a reference list of (from, to, cause), one entry per
/// removal, over the steady single-record case and over sequences past the 16 runs a value keeps.
class RemovalLedgerTest {
    private static final RemovalCause[] CAUSES = {RemovalCause.AGE, RemovalCause.COUNT, RemovalCause.BYTES,
                                                  RemovalCause.RING_FROZEN, RemovalCause.RETENTION};
    private static final int EXERCISED_FLOOR = 1_000;

    private record Removal(long from, long to, RemovalCause cause) {}

    /// The cause SET and the cause at the requested offset, over the whole range, for single-record removals and for sequences
    /// that drop runs into the set. Reports "not exercised" below 1,000 of each, so a generator that stopped producing either
    /// shape cannot pass.
    @Test
    void answer_overRandomRemovals_matchesTheReferenceList() {
        var random = new Random(0x2A1);
        var singleRecordChecks = 0;
        var pastSixteenRunChecks = 0;

        for (var trial = 0; trial < 120; trial++) {
            var seedBase = trial % 3 == 0 ? random.nextInt(5) : -1;
            var ledger = seedBase < 0 ? RemovalLedger.EMPTY : seeded(seedBase);
            var reference = new ArrayList<Removal>();

            for (var step = 0; step < 45; step++) {
                var length = random.nextInt(4) == 0 ? 1 + random.nextInt(3) : 1;
                var cause = CAUSES[random.nextInt(CAUSES.length)];
                var from = ledger.tail();

                ledger = removed(ledger, from + length, cause);
                reference.add(new Removal(from, from + length, cause));

                var checks = checkEveryOffset(ledger, reference);

                singleRecordChecks += length == 1 ? checks : 0;
                pastSixteenRunChecks += ledger.droppedMask() != 0 ? checks : 0;
            }
        }

        assertThat(singleRecordChecks).as("single-record removal checks (not exercised below %d)", EXERCISED_FLOOR)
                                      .isGreaterThanOrEqualTo(EXERCISED_FLOOR);
        assertThat(pastSixteenRunChecks).as("checks past 16 runs (not exercised below %d)", EXERCISED_FLOOR)
                                        .isGreaterThanOrEqualTo(EXERCISED_FLOOR);
    }

    /// One removal per cause, alternating: no two adjacent runs share a cause, so none is merged and 16 runs are kept before
    /// the lowest drops into the set. The 17th run raises the floor and the dropped cause stays in the answer's set.
    @Test
    void removed_pastSixteenRuns_dropsTheLowestIntoTheSetAndRaisesTheFloor() {
        var ledger = RemovalLedger.EMPTY;

        for (var i = 0; i < 17; i++) {
            ledger = removed(ledger, i + 1, i % 2 == 0 ? RemovalCause.AGE : RemovalCause.COUNT);
        }

        assertThat(ledger.runCount()).isEqualTo(RemovalLedger.MAX_RUNS);
        assertThat(ledger.floor()).isEqualTo(1L);
        assertThat(ledger.droppedMask()).isEqualTo(1 << RemovalCause.AGE.ordinal());

        var expired = (StreamError.CursorExpired) ledger.answer(0, false);

        assertThat(expired.cause()).as("below the floor only the dropped SET covers the offset")
                                   .isEqualTo(RemovalCause.UNRECORDED);
        assertThat(expired.causes()).containsExactlyInAnyOrder(RemovalCause.AGE, RemovalCause.COUNT);
    }

    /// A one-record removal is the steady case: its cause must not be lost (mutant pE drops it) nor merged into a run of
    /// another cause (mutant pM), and the answer at the first record names the first cause, not the last.
    @Test
    void answer_overSingleRecordRemovalsOfTwoCauses_namesEachCause() {
        var ledger = removed(removed(RemovalLedger.EMPTY, 1, RemovalCause.AGE), 2, RemovalCause.BYTES);

        var first = (StreamError.CursorExpired) ledger.answer(0, false);
        var second = (StreamError.CursorExpired) ledger.answer(1, false);

        assertThat(first.cause()).isEqualTo(RemovalCause.AGE);
        assertThat(first.causes()).containsExactlyInAnyOrder(RemovalCause.AGE, RemovalCause.BYTES);
        assertThat(second.cause()).isEqualTo(RemovalCause.BYTES);
        assertThat(second.causes()).containsExactly(RemovalCause.BYTES);
    }

    @Test
    void seeded_onAValueThatIsNotEmpty_isRefused() {
        assertRefused(seeded(4).seeded(9));
        assertRefused(removed(RemovalLedger.EMPTY, 3, RemovalCause.AGE).seeded(9));
    }

    @Test
    void seeded_onEmpty_positionsFirstHeldAndTailAboveTheBase() {
        var ledger = seeded(7);

        assertThat(ledger.firstHeld()).isEqualTo(8L);
        assertThat(ledger.tail()).isEqualTo(8L);
        assertThat(ledger.floor()).isEqualTo(8L);
    }

    @Test
    void removed_toAtOrBelowTheTail_isRefused() {
        var ledger = removed(RemovalLedger.EMPTY, 5, RemovalCause.COUNT);

        assertRefused(ledger.removed(5, RemovalCause.AGE));
        assertRefused(ledger.removed(2, RemovalCause.AGE));
        assertRefused(ledger.advanced(5));
    }

    /// The answer below the seed run: the owner is told what the record proves, every other reader where this copy began.
    @Test
    void answer_belowFirstHeld_isLifeLostForTheOwnerAndBelowCopyTailForAnyOtherReader() {
        var ledger = removed(seeded(9), 12, RemovalCause.AGE);

        var owner = (StreamError.CursorExpired) ledger.answer(3, true);

        assertThat(owner.reason()).isEqualTo(StreamError.CursorExpired.Reason.LOST);
        assertThat(owner.cause()).isEqualTo(RemovalCause.LIFE_LOST);
        assertThat(owner.causes()).containsExactlyInAnyOrder(RemovalCause.LIFE_LOST, RemovalCause.AGE);
        assertThat(ledger.answer(3, false)).isEqualTo(new StreamError.BelowCopyTail(10, 12));
    }

    private static int checkEveryOffset(RemovalLedger ledger, List<Removal> reference) {
        var checks = 0;

        for (var requested = 0L; requested < ledger.tail(); requested++) {
            checkOwner(ledger, reference, requested);
            checkOtherReader(ledger, reference, requested);
            checks++;
        }

        return checks;
    }

    private static void checkOwner(RemovalLedger ledger, List<Removal> reference, long requested) {
        var expected = expectedCause(ledger, reference, requested);
        var expectedSet = expectedSet(ledger, reference, requested);
        var answer = (StreamError.CursorExpired) ledger.answer(requested, true);

        assertThat(answer.requestedOffset()).isEqualTo(requested);
        assertThat(answer.tailOffset()).isEqualTo(ledger.tail());
        assertThat(answer.cause()).as("cause at %d", requested).isEqualTo(expected);
        assertThat(answer.causes()).as("causes from %d", requested).isEqualTo(expectedSet);
    }

    private static void checkOtherReader(RemovalLedger ledger, List<Removal> reference, long requested) {
        Cause answer = ledger.answer(requested, false);

        if (requested < ledger.firstHeld()) {
            assertThat(answer).isEqualTo(new StreamError.BelowCopyTail(ledger.firstHeld(), ledger.tail()));
        } else {
            assertThat(((StreamError.CursorExpired) answer).causes())
                .as("causes from %d", requested)
                .isEqualTo(expectedSet(ledger, reference, requested));
        }
    }

    /// The cause covering the requested offset: the seed run's, the unrecorded one below the floor (only the set covers it
    /// there), else the reference entry covering it.
    private static RemovalCause expectedCause(RemovalLedger ledger, List<Removal> reference, long requested) {
        return requested < ledger.firstHeld()
               ? RemovalCause.LIFE_LOST
               : requested < ledger.floor()
                 ? RemovalCause.UNRECORDED
                 : reference.stream()
                            .filter(removal -> removal.from() <= requested && requested < removal.to())
                            .map(Removal::cause)
                            .findFirst()
                            .orElseThrow();
    }

    /// Every cause covering any offset in [requested, tail), the seed's LIFE_LOST below firstHeld, and the dropped set when
    /// requested is below the floor: the dropped set is the causes of the entries that end at or below the floor.
    private static Set<RemovalCause> expectedSet(RemovalLedger ledger, List<Removal> reference, long requested) {
        var causes = EnumSet.noneOf(RemovalCause.class);

        if (requested < ledger.firstHeld()) {
            causes.add(RemovalCause.LIFE_LOST);
        }

        reference.stream()
                 .filter(removal -> removal.to() > requested)
                 .forEach(removal -> causes.add(removal.cause()));

        if (requested < ledger.floor()) {
            reference.stream()
                     .filter(removal -> removal.to() <= ledger.floor())
                     .forEach(removal -> causes.add(removal.cause()));
        }

        return causes;
    }

    private static RemovalLedger seeded(long base) {
        return success(RemovalLedger.EMPTY.seeded(base));
    }

    private static RemovalLedger removed(RemovalLedger ledger, long to, RemovalCause cause) {
        return success(ledger.removed(to, cause));
    }

    private static <T> T success(Result<T> result) {
        return result.unwrap();
    }

    private static void assertRefused(Result<RemovalLedger> result) {
        var seen = new AtomicReference<Cause>();

        result.onSuccess(value -> Assertions.fail("expected a refusal, got a value with tail " + value.tail()))
              .onFailure(seen::set);

        assertThat(seen.get()).isInstanceOf(RingStateViolated.class);
    }
}
