// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.api.OperationalEvent;
import org.pragmatica.aether.slice.kvstore.AetherValue.DhtReplicationChangeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DhtReplicationReportValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DhtReplicationStage;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// #1777, CTO ruling R1b: the leader's decisions on a live DHT replication change — when a change is committed, when it
/// advances, who it waits for, and which operator event a committed transition calls for.
class DhtReplicationSettlementTest {
    private static final NodeId CORE_A = new NodeId("core-a");
    private static final NodeId CORE_B = new NodeId("core-b");
    private static final NodeId CORE_C = new NodeId("core-c");
    private static final NodeId WORKER = new NodeId("worker-1");
    private static final Set<NodeId> CORES = Set.of(CORE_A, CORE_B, CORE_C);
    private static final long N = 9L;
    private static final long NOW = 1_000_000L;

    @Nested
    class Commit {
        @Test
        void nextChange_nothingCommitted_isASettledBaseline() {
            var baseline = DhtReplicationSettlement.nextChange(Option.none(), 1, 3, 2, NOW).unwrap();

            assertThat(baseline.stage()).isEqualTo(DhtReplicationStage.SETTLED);
            assertThat(baseline.version()).as("R1c: the baseline is no change, so it fences no writer")
                                          .isEqualTo(org.pragmatica.dht.DHTNode.NO_CHANGE);
        }

        @Test
        void nextChange_sameFactors_commitsNothing() {
            assertThat(DhtReplicationSettlement.nextChange(Option.some(settled(5, 3, 2)), 6, 3, 2, NOW)).matches(Option::isEmpty);
        }

        /// A configuration notification at or below the committed change's version is stale: it never re-commits.
        @Test
        void nextChange_staleConfigurationVersion_commitsNothing() {
            assertThat(DhtReplicationSettlement.nextChange(Option.some(settled(5, 3, 2)), 5, 3, 1, NOW)).matches(Option::isEmpty);
            assertThat(DhtReplicationSettlement.nextChange(Option.some(settled(5, 3, 2)), 4, 3, 1, NOW)).matches(Option::isEmpty);
        }

        /// RF3/CF2 -> RF3/CF1: the floor is the old quorums, W2 R2.
        @Test
        void nextChange_differentFactors_commitsAnApplyingChange_withTheOldQuorumsAsItsFloor() {
            var change = DhtReplicationSettlement.nextChange(Option.some(settled(5, 3, 2)), 6, 3, 1, NOW).unwrap();

            assertThat(change.stage()).isEqualTo(DhtReplicationStage.APPLYING);
            assertThat(change.version()).isEqualTo(6);
            assertThat(change.floorWriteQuorum()).isEqualTo(2);
            assertThat(change.floorReadQuorum()).isEqualTo(2);
            assertThat(change.since()).isEqualTo(NOW);
        }

        /// Consecutive changes keep the strictest quorums across all unsettled changes, and the widest source set.
        @Test
        void nextChange_overAnUnsettledChange_carriesItsFloorAndSources() {
            // RF5/CF4 (W4 R2) -> RF5/CF2 (W2 R4), unsettled, floor W4 R2, sources 5
            var unsettled = new DhtReplicationChangeValue(6, 5, 2, 4, 2, 5, DhtReplicationStage.APPLYING, NOW, false);
            // -> RF3/CF1: floor max(W4, W_prev 2) = 4, max(R2, R_prev 4) = 4; sources max(5, 5, 3) = 5
            var next = DhtReplicationSettlement.nextChange(Option.some(unsettled), 7, 3, 1, NOW).unwrap();

            assertThat(next.floorWriteQuorum()).isEqualTo(4);
            assertThat(next.floorReadQuorum()).isEqualTo(4);
            assertThat(next.sourceReplicationFactor()).isEqualTo(5);
        }
    }

    @Nested
    class Advance {
        @Test
        void allApplied_movesToWritersSwitched_thenAllCaughtUp_settles() {
            var change = applying();
            var applied = Map.of(CORE_A, report(N, -1, true), CORE_B, report(N, -1, true), CORE_C, report(N, -1, true),
                                 WORKER, report(N, -1, false));
            var switched = advance(change, applied, Set.of(WORKER)).unwrap();

            assertThat(switched.stage()).isEqualTo(DhtReplicationStage.WRITERS_SWITCHED);

            var caughtUp = Map.of(CORE_A, report(N, N, true), CORE_B, report(N, N, true), CORE_C, report(N, N, true),
                                  WORKER, report(N, -1, false));

            assertThat(advance(switched, caughtUp, Set.of(WORKER)).unwrap().stage()).isEqualTo(DhtReplicationStage.SETTLED);
        }

        /// A lagging worker is a lagging writer: every core applied and caught up, but the worker has not applied the
        /// change, so the writers have not switched.
        @Test
        void workerLaggard_holdsTheChange() {
            var reports = Map.of(CORE_A, report(N, N, true), CORE_B, report(N, N, true), CORE_C, report(N, N, true),
                                 WORKER, report(N - 1, -1, false));

            assertThat(advance(applying(), reports, Set.of(WORKER))).as("the worker has not applied N").matches(Option::isEmpty);
        }

        /// A worker the membership tracks but that has not filed a report at all is waited for too.
        @Test
        void silentWorker_holdsTheChange() {
            var reports = Map.of(CORE_A, report(N, N, true), CORE_B, report(N, N, true), CORE_C, report(N, N, true));

            assertThat(advance(applying(), reports, Set.of(WORKER))).matches(Option::isEmpty);
        }

        /// A worker that filed a report counts even when the membership view has not listed it (yet).
        @Test
        void reportingWorkerUnknownToTheMembership_isStillWaitedFor() {
            var reports = Map.of(CORE_A, report(N, N, true), CORE_B, report(N, N, true), CORE_C, report(N, N, true),
                                 WORKER, report(N - 1, -1, false));

            assertThat(advance(applying(), reports, Set.of())).matches(Option::isEmpty);
        }

        /// Members the membership view holds Dead are excluded: a dead worker and a dead core no longer hold the change.
        @Test
        void departedMembers_areNotWaitedFor() {
            var reports = Map.of(CORE_A, report(N, N, true), CORE_B, report(N, N, true),
                                 CORE_C, report(N - 1, -1, true), WORKER, report(N - 1, -1, false));
            Set<NodeId> dead = Set.of(CORE_C, WORKER);

            var switched = DhtReplicationSettlement.advance(applying(), reports, CORES, Set.of(WORKER), dead::contains, NOW)
                                                   .unwrap();

            assertThat(switched.stage()).isEqualTo(DhtReplicationStage.WRITERS_SWITCHED);
            assertThat(DhtReplicationSettlement.advance(switched, reports, CORES, Set.of(WORKER), dead::contains, NOW)
                                               .unwrap()
                                               .stage()).isEqualTo(DhtReplicationStage.SETTLED);
        }

        /// Control for the exclusion: the same members, alive, hold the change.
        @Test
        void sameMembersAlive_holdTheChange() {
            var reports = Map.of(CORE_A, report(N, N, true), CORE_B, report(N, N, true),
                                 CORE_C, report(N - 1, -1, true), WORKER, report(N - 1, -1, false));

            assertThat(advance(applying(), reports, Set.of(WORKER))).matches(Option::isEmpty);
        }

        /// Reports for the previous change (N-1) — applied AND caught up — never advance change N.
        @Test
        void staleReports_neverAdvanceANewerChange() {
            var stale = Map.of(CORE_A, report(N - 1, N - 1, true), CORE_B, report(N - 1, N - 1, true),
                               CORE_C, report(N - 1, N - 1, true), WORKER, report(N - 1, -1, false));

            assertThat(advance(applying(), stale, Set.of(WORKER))).as("APPLYING on stale reports").matches(Option::isEmpty);
            assertThat(advance(applying().withStage(DhtReplicationStage.WRITERS_SWITCHED), stale, Set.of(WORKER)))
                .as("WRITERS_SWITCHED on stale caught-up reports")
                .matches(Option::isEmpty);
        }

        /// A core's catch-up from before the writers switched does not settle: settling needs the pass that began after.
        /// Caught-up versions are only ever reported for a writers-switched pass, so a core that applied N and caught up
        /// in its FIRST pass still reports caughtUp < N.
        @Test
        void appliedButNotCaughtUpAfterTheSwitch_doesNotSettle() {
            var reports = Map.of(CORE_A, report(N, N, true), CORE_B, report(N, N, true), CORE_C, report(N, -1, true));

            assertThat(advance(applying().withStage(DhtReplicationStage.WRITERS_SWITCHED), reports, Set.of())).matches(Option::isEmpty);
        }

        /// A worker's catch-up is never waited for: it holds no partitions.
        @Test
        void workers_areNotWaitedForToCatchUp() {
            var reports = Map.of(CORE_A, report(N, N, true), CORE_B, report(N, N, true), CORE_C, report(N, N, true),
                                 WORKER, report(N, -1, false));

            assertThat(advance(applying().withStage(DhtReplicationStage.WRITERS_SWITCHED), reports, Set.of(WORKER)).unwrap()
                                                                                                            .stage())
                .isEqualTo(DhtReplicationStage.SETTLED);
        }

        @Test
        void unsettledPastTheBound_isCommittedOverdue_once() {
            var change = applying();
            var later = NOW + DhtReplicationSettlement.OVERDUE_AFTER_MS + 1;
            var flagged = DhtReplicationSettlement.advance(change, Map.of(), CORES, Set.of(), _ -> false, later).unwrap();

            assertThat(flagged.overdue()).isTrue();
            assertThat(DhtReplicationSettlement.advance(flagged, Map.of(), CORES, Set.of(), _ -> false, later + 1000))
                .as("already overdue: nothing more to commit")
                .matches(Option::isEmpty);
            assertThat(DhtReplicationSettlement.advance(change, Map.of(), CORES, Set.of(), _ -> false,
                                                        NOW + DhtReplicationSettlement.OVERDUE_AFTER_MS))
                .as("at the bound, not past it")
                .matches(Option::isEmpty);
        }

        @Test
        void settling_clearsOverdue() {
            var overdue = applying().withStage(DhtReplicationStage.WRITERS_SWITCHED).withOverdue(true);
            var reports = Map.of(CORE_A, report(N, N, true), CORE_B, report(N, N, true), CORE_C, report(N, N, true));
            var settled = advance(overdue, reports, Set.of()).unwrap();

            assertThat(settled.settled()).isTrue();
            assertThat(settled.overdue()).isFalse();
        }
    }

    /// The owner rule: entering and leaving the operator-attention condition each emit one typed event, derived from
    /// the committed transition so it is deduped per change.
    @Nested
    class Events {
        @Test
        void enteringOverdue_emitsUnsettled() {
            var event = DhtReplicationSettlement.transition(Option.some(applying()), applying().withOverdue(true)).unwrap();

            assertThat(event).isInstanceOf(OperationalEvent.DhtReplicationUnsettled.class);
            assertThat(((OperationalEvent.DhtReplicationUnsettled) event).changeVersion()).isEqualTo(N);
        }

        @Test
        void settlingWhileOverdue_emitsSettled() {
            var overdue = applying().withStage(DhtReplicationStage.WRITERS_SWITCHED).withOverdue(true);
            var event = DhtReplicationSettlement.transition(Option.some(overdue),
                                                            overdue.withStage(DhtReplicationStage.SETTLED).withOverdue(false))
                                                .unwrap();

            assertThat(event).isInstanceOf(OperationalEvent.DhtReplicationSettled.class);
            assertThat(((OperationalEvent.DhtReplicationSettled) event).reason()).isEqualTo("settled");
        }

        @Test
        void supersedingAnOverdueChange_emitsSettledForIt() {
            var overdue = applying().withOverdue(true);
            var next = new DhtReplicationChangeValue(N + 1, 3, 1, 2, 2, 3, DhtReplicationStage.APPLYING, NOW, false);
            var event = (OperationalEvent.DhtReplicationSettled) DhtReplicationSettlement.transition(Option.some(overdue), next)
                                                                                          .unwrap();

            assertThat(event.changeVersion()).as("the overdue change left the condition").isEqualTo(N);
            assertThat(event.reason()).contains("superseded");
        }

        @Test
        void transitionsThatNeitherEnterNorLeave_emitNothing() {
            var overdue = applying().withOverdue(true);

            assertThat(DhtReplicationSettlement.transition(Option.none(), applying())).as("a fresh change").matches(Option::isEmpty);
            assertThat(DhtReplicationSettlement.transition(Option.some(applying()),
                                                           applying().withStage(DhtReplicationStage.WRITERS_SWITCHED)))
                .as("a stage move while not overdue").matches(Option::isEmpty);
            assertThat(DhtReplicationSettlement.transition(Option.some(overdue),
                                                           overdue.withStage(DhtReplicationStage.WRITERS_SWITCHED)))
                .as("still overdue: no second event").matches(Option::isEmpty);
            assertThat(DhtReplicationSettlement.transition(Option.some(applying()),
                                                           applying().withStage(DhtReplicationStage.WRITERS_SWITCHED)
                                                                     .withStage(DhtReplicationStage.SETTLED)))
                .as("settling without ever being overdue").matches(Option::isEmpty);
        }
    }

    private static Option<DhtReplicationChangeValue> advance(DhtReplicationChangeValue change,
                                                             Map<NodeId, DhtReplicationReportValue> reports,
                                                             Set<NodeId> workers) {
        return DhtReplicationSettlement.advance(change, reports, CORES, workers, _ -> false, NOW);
    }

    private static DhtReplicationChangeValue applying() {
        return new DhtReplicationChangeValue(N, 3, 2, 1, 3, 3, DhtReplicationStage.APPLYING, NOW, false);
    }

    private static DhtReplicationChangeValue settled(long version, int rf, int cf) {
        return new DhtReplicationChangeValue(version, rf, cf, cf, rf - cf + 1, rf, DhtReplicationStage.SETTLED, NOW, false);
    }

    private static DhtReplicationReportValue report(long applied, long caughtUp, boolean replica) {
        return new DhtReplicationReportValue(applied, caughtUp, replica);
    }
}
