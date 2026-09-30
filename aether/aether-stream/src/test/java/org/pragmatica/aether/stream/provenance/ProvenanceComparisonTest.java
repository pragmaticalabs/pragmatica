// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.provenance;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.provenance.ProvenanceComparison.Incompleteness;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// #1596: the divergence rule of spec #1569 §7.5.2 over the reviewer's T7 sequences (rev1569). Each test names the
/// mutation of `ProvenanceComparison` that turns it red.
class ProvenanceComparisonTest {
    private static final Epoch E1 = Epoch.epoch(0,1, 0);
    private static final Epoch E2 = Epoch.epoch(0,2, 0);
    private static final Epoch E3 = Epoch.epoch(0,3, 0);

    @Nested
    class Divergence {
        @Test
        void sameHistory_differentHeads_isPrefixConsistent() {
            var longer = copy(0, 40, at(E1, 0), at(E2, 20));
            var shorter = copy(0, 25, at(E1, 0), at(E2, 20));

            assertThat(ProvenanceComparison.diverge(longer, shorter)).isFalse();
            assertThat(ProvenanceComparison.diverge(shorter, longer)).isFalse();
        }

        /// rev1569 F1: A wrote 11..15 at e1, unacked; B took over at e2 from 11 and wrote 11'..12'. A returns with the
        /// higher head. The copies disagree at 11.
        @Test
        void f1_deposedOwnersUnackedTail_diverges() {
            var a = copy(0, 15, at(E1, 0));
            var b = copy(0, 12, at(E1, 0), at(E2, 11));

            assertThat(ProvenanceComparison.firstDivergence(a, b, 0, 12)).isEqualTo(Option.some(11L));
        }

        /// rev1569 N3: source (e1,0),(e3,20); candidate (e1,0),(e2,15) at head 25 -- a head-and-last-epoch check alone
        /// would accept it, the walk finds 15.
        @Test
        void n3_intermediateEpochTheSourceNeverHad_diverges() {
            var source = copy(0, 30, at(E1, 0), at(E3, 20));
            var candidate = copy(0, 25, at(E1, 0), at(E2, 15));

            assertThat(ProvenanceComparison.firstDivergence(source, candidate, 0, 25)).isEqualTo(Option.some(15L));
        }

        /// rev1569 S-1: a deposed A keeps replicating e1 records to B after C took over at e2 and D at e3. B's copy
        /// carries e1 where C's and D's carry e2 -- B diverges from both, C and D agree up to C's head.
        @Test
        void s1_deposedOwnerReplicatingToAPeer_divergesFromTheLaterLineage() {
            var b = copy(0, 18, at(E1, 0));
            var c = copy(0, 16, at(E1, 0), at(E2, 10));
            var d = copy(0, 24, at(E1, 0), at(E2, 10), at(E3, 17));

            assertThat(ProvenanceComparison.diverge(b, c)).isTrue();
            assertThat(ProvenanceComparison.diverge(b, d)).isTrue();
            assertThat(ProvenanceComparison.diverge(c, d)).isFalse();
        }

        /// rev1569 R5-1: the copies' RETAINED ranges do not overlap (a holds 0..10, b holds 50..60), yet they
        /// disagree at 5. Red under "compare only the retained overlap".
        @Test
        void r51_nonOverlappingRetainedRanges_stillDivergeBelowBoth() {
            var a = copy(0, 10, at(E1, 0));
            var b = LogProvenance.logProvenance(0, 50, 60, List.of(at(E1, 0), at(E2, 5)));

            assertThat(ProvenanceComparison.diverge(a, b)).isTrue();
            assertThat(ProvenanceComparison.firstDivergence(a, b, 0, 10)).isEqualTo(Option.some(5L));
        }

        /// UNDEFINED equals nothing, not even UNDEFINED: two copies with the same gap below their first entry are not
        /// thereby consistent. Red under "UNDEFINED equals UNDEFINED".
        @Test
        void undefinedOffsets_neverCompareEqual_evenToEachOther() {
            var a = copy(0, 10, at(E1, 5));
            var b = copy(0, 10, at(E1, 5));

            assertThat(ProvenanceComparison.firstDivergence(a, b, 0, 10)).isEqualTo(Option.some(0L));
        }

        /// #1625 slot: the same epoch number minted under two incarnation ULIDs is two epochs.
        @Test
        void sameEpochDifferentIncarnationUlid_diverges() {
            var a = copy(0, 10, ProvenanceEntry.provenanceEntry(E1, Option.some("A"), 0));
            var b = copy(0, 10, ProvenanceEntry.provenanceEntry(E1, Option.some("B"), 0));

            assertThat(ProvenanceComparison.diverge(a, b)).isTrue();
        }

        /// UNKNOWN(d) equals only itself: two copies that took an unattributed range in two decisions diverge over it,
        /// and a copy compared with the same decision's range does not.
        @Test
        void unattributedRanges_equalOnlyTheSameDecision() {
            var unknown = ProvenanceEpoch.unknown(E2);
            var a = copy(0, 20, at(E1, 0), ProvenanceEntry.provenanceEntry(unknown, 10));
            var sameDecision = copy(0, 20, at(E1, 0), ProvenanceEntry.provenanceEntry(unknown, 10));
            var otherDecision = copy(0, 20, at(E1, 0), ProvenanceEntry.provenanceEntry(ProvenanceEpoch.unknown(E2), 10));

            assertThat(ProvenanceComparison.diverge(a, sameDecision)).isFalse();
            assertThat(ProvenanceComparison.firstDivergence(a, otherDecision, 0, 20)).isEqualTo(Option.some(10L));
        }

        /// #1638 N1 (v1638 probe4): a synthetic entry without its `d` names no decision, so two of them are UNDEFINED and
        /// never equal -- not `Unknown("")` twice.
        @Test
        void syntheticEntriesWithoutD_neverCompareEqual() {
            var x = new ProvenanceEntry(ProvenanceEntry.ProvenanceKind.UNATTRIBUTED, E1, Option.none(), 0);
            var y = new ProvenanceEntry(ProvenanceEntry.ProvenanceKind.UNKNOWN, E1, Option.none(), 0);
            var z = new ProvenanceEntry(ProvenanceEntry.ProvenanceKind.UNATTRIBUTED, E1, Option.some(""), 0);

            assertThat(ProvenanceComparison.diverge(copy(0, 9, x), copy(0, 9, y))).isTrue();
            assertThat(ProvenanceComparison.diverge(copy(0, 9, x), copy(0, 9, x))).isTrue();
            assertThat(ProvenanceComparison.diverge(copy(0, 9, z), copy(0, 9, z))).isTrue();
        }

        @Test
        void copyHoldingNothing_neverDiverges() {
            var empty = copy(0, -1);
            var full = copy(0, 30, at(E1, 0));

            assertThat(ProvenanceComparison.diverge(empty, full)).isFalse();
        }
    }

    @Nested
    class Completeness {
        /// rev1569 T7(l): a pre-A11 log (records, no history) is HISTORY_MISSING; once it gains one entry above its
        /// records it is HISTORY_INCOMPLETE -- flagged either way, never trusted. Red under "skip completeness".
        @Test
        void t7l_preA11LogGainingOneEntry_isIncomplete() {
            assertThat(ProvenanceComparison.incompleteness(copy(0, 10))).isEqualTo(Option.some(Incompleteness.HISTORY_MISSING));
            assertThat(ProvenanceComparison.incompleteness(copy(0, 11, at(E2, 11)))).isEqualTo(Option.some(Incompleteness.HISTORY_INCOMPLETE));
        }

        /// rev1569 T7(p), R7-1: a copy whose history starts at its base (500) but which still holds a record below it
        /// (400) is incomplete.
        @Test
        void t7p_recordBelowTheBase_isIncomplete() {
            var copy = LogProvenance.logProvenance(500, 400, 600, List.of(at(E2, 500)));

            assertThat(ProvenanceComparison.incompleteness(copy)).isEqualTo(Option.some(Incompleteness.HISTORY_INCOMPLETE));
        }

        /// A first `BASE(d)` entry sets the base: offsets below it are NONE, not UNDEFINED.
        @Test
        void baseEntry_setsTheBase() {
            var history = List.of(ProvenanceEntry.provenanceEntry(new ProvenanceEpoch.Base("d", E1), 500), at(E2, 520));

            assertThat(LogProvenance.baseOf(history)).isEqualTo(500L);
            assertThat(LogProvenance.baseOf(List.of(at(E1, 0)))).isEqualTo(0L);
            assertThat(ProvenanceComparison.incompleteness(LogProvenance.logProvenance(500, 500, 600, history))).isEqualTo(Option.none());
        }

        @Test
        void historyFromTheBase_isComplete_andAnEmptyCopyIsComplete() {
            assertThat(ProvenanceComparison.incompleteness(copy(0, 30, at(E1, 0), at(E2, 12)))).isEqualTo(Option.none());
            assertThat(ProvenanceComparison.incompleteness(copy(0, -1))).isEqualTo(Option.none());
        }
    }

    @Nested
    class SourceOrder {
        /// Among candidates the later last epoch wins over the higher head (F1's pair). Red under "rank by head only".
        @Test
        void laterLastEpoch_outranksHigherHead() {
            var deposed = copy(0, 15, at(E1, 0));
            var current = copy(0, 12, at(E1, 0), at(E2, 11));

            assertThat(List.of(deposed, current).stream().max(LogProvenance.SOURCE_ORDER)).contains(current);
        }

        @Test
        void sameLastEpoch_higherHeadWins_andNoHistoryRanksLowest() {
            var behind = copy(0, 20, at(E1, 0), at(E2, 11));
            var ahead = copy(0, 25, at(E1, 0), at(E2, 11));
            var bare = copy(0, 99);

            assertThat(List.of(behind, bare, ahead).stream().max(LogProvenance.SOURCE_ORDER)).contains(ahead);
            assertThat(List.of(behind, bare, ahead).stream().min(LogProvenance.SOURCE_ORDER)).contains(bare);
        }

        /// #1638 F2 (v1638 probe r1): an entry starting above the head is a ghost no record reaches, and never ranks the
        /// copy. An EMPTY copy holding the ghost e3 ranks below a copy holding records 0..5 at e2, and a copy with
        /// records ranks by the last entry its records reach. Red under "rank every entry".
        @Test
        void entriesAboveTheHead_neverRank() {
            var emptyWithGhost = copy(0, -1, at(E1, 0), at(E3, 2));
            var holder = copy(0, 5, at(E1, 0), at(E2, 3));
            var ghostAboveHead = copy(0, 2, at(E1, 0), at(E3, 6));

            assertThat(emptyWithGhost.lastEpoch()).isEqualTo(Option.none());
            assertThat(ghostAboveHead.lastEpoch()).isEqualTo(Option.some(E1));
            assertThat(List.of(emptyWithGhost, holder, ghostAboveHead).stream().max(LogProvenance.SOURCE_ORDER)).contains(holder);
            assertThat(LogProvenance.SOURCE_ORDER.compare(emptyWithGhost, holder)).isNegative();
        }
    }

    private static ProvenanceEntry at(Epoch epoch, long start) {
        return ProvenanceEntry.provenanceEntry(epoch, Option.none(), start);
    }

    private static LogProvenance copy(long base, long head, ProvenanceEntry... history) {
        return LogProvenance.logProvenance(base, Math.min(base, Math.max(head, 0)), head, Arrays.asList(history));
    }
}
