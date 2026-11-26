// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.metrics.NodeReportedState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.metrics.NodeReportedState.READY;
import static org.pragmatica.aether.metrics.NodeReportedState.SYNCING;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2136: the leader's SYNCING episodes, one per voter, on an injected clock. Each case pins one clause of the episode rule (the brief's
/// item 1): the time counts only while this node leads under one epoch, its consensus is active and the voter's reports keep arriving,
/// and anything else ends the episode so the next SYNCING starts from zero.
class StuckSyncingVotersTest {
    private static final NodeId A = new NodeId("a");
    private static final NodeId B = new NodeId("b");
    private static final NodeId C = new NodeId("c");
    private static final Set<NodeId> VOTERS = Set.of(A, B, C);
    private static final long TICK = timeSpan(1).seconds().nanos();
    private static final long BOUND_TICKS = StuckSyncingVoters.STUCK_BOUND.nanos() / TICK;
    private static final TimeSpan PING_INTERVAL = timeSpan(1).seconds();

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final AtomicReference<Option<Long>> epoch = new AtomicReference<>(Option.some(7L));
    private final AtomicBoolean consensusActive = new AtomicBoolean(true);
    private final Map<NodeId, TimeSpan> pongAge = new ConcurrentHashMap<>();
    private final List<OperatorWarning> raised = new CopyOnWriteArrayList<>();
    private final OperatorWarningSink sink = OperatorWarningSink.handingOffTo(raised::add);
    private final StuckSyncingVoters stuck = StuckSyncingVoters.stuckSyncingVoters(epoch::get,
                                                                                   consensusActive::get,
                                                                                   node -> Option.option(pongAge.get(node)),
                                                                                   PING_INTERVAL,
                                                                                   clock::get);

    @BeforeEach
    void freshReports() {
        VOTERS.forEach(voter -> pongAge.put(voter, timeSpan(200).millis()));
        stuck.operatorWarnings(sink);
    }

    /// One evaluation after one more tick of the clock.
    private Map<NodeId, Long> tick(Map<NodeId, NodeReportedState> reported) {
        clock.addAndGet(TICK);

        return stuck.stuck(VOTERS, reported);
    }

    /// `ticks` evaluations, one per tick; the last one's result.
    private Map<NodeId, Long> ticks(long ticks, Map<NodeId, NodeReportedState> reported) {
        var last = Map.<NodeId, Long>of();

        for (var i = 0L; i < ticks; i++) {
            last = tick(reported);
        }

        return last;
    }

    /// The hand-off sink is one FIFO thread: once a sentinel offered after the evaluations arrives, every warning raised before it has.
    private List<OperatorWarning> delivered() {
        var sentinel = OperatorWarning.operatorWarning(OperatorWarningCode.CORE_VOTER_SYNCING_ENDED, "sentinel-" + UUID.randomUUID(), "sentinel");

        sink.accept(sentinel);
        await().atMost(Duration.ofSeconds(10)).until(() -> raised.contains(sentinel));

        return raised.stream().filter(warning -> !warning.subject().startsWith("sentinel-")).toList();
    }

    @Test
    void sustainedSyncing_isStuckAtExactlyTheBound_andNotOneTickBefore() {
        var syncing = Map.of(A, READY, B, SYNCING, C, READY);

        assertThat(tick(syncing)).as("the first SYNCING report starts the episode").isEmpty();
        assertThat(ticks(BOUND_TICKS - 1, syncing)).as("one tick before the bound").isEmpty();
        assertThat(tick(syncing)).as("at exactly the bound").containsExactly(Map.entry(B, StuckSyncingVoters.STUCK_BOUND.nanos()));
    }

    @Test
    void briefSyncingAcrossALeaderChange_isNotStuck_theNewEpochStartsFromZero() {
        var syncing = Map.of(A, READY, B, SYNCING, C, READY);

        ticks(BOUND_TICKS - 10, syncing);
        epoch.set(Option.some(8L));
        assertThat(ticks(20, syncing)).as("the old epoch's time does not carry over").isEmpty();
        assertThat(ticks(BOUND_TICKS - 19, syncing)).as("the new epoch counts from its own first report")
                                                .containsExactly(Map.entry(B, StuckSyncingVoters.STUCK_BOUND.nanos()));
    }

    @Test
    void aReadyReportInBetween_endsTheEpisode() {
        var syncing = Map.of(A, READY, B, SYNCING, C, READY);

        ticks(BOUND_TICKS - 1, syncing);
        tick(Map.of(A, READY, B, READY, C, READY));
        assertThat(ticks(BOUND_TICKS, syncing)).as("one tick short of the bound since the READY").isEmpty();
        assertThat(tick(syncing)).containsOnlyKeys(B);
    }

    @Test
    void losingAndRegainingLeadership_endsTheEpisode() {
        var syncing = Map.of(A, READY, B, SYNCING, C, READY);

        ticks(BOUND_TICKS - 1, syncing);
        epoch.set(Option.none());
        assertThat(tick(syncing)).as("not leading: nothing is stuck").isEmpty();
        epoch.set(Option.some(7L));
        assertThat(ticks(BOUND_TICKS, syncing)).as("same epoch regained, but the interruption reset it").isEmpty();
        assertThat(tick(syncing)).containsOnlyKeys(B);
    }

    @Test
    void ownConsensusInactive_endsTheEpisode() {
        var syncing = Map.of(A, READY, B, SYNCING, C, READY);

        ticks(BOUND_TICKS - 1, syncing);
        consensusActive.set(false);
        assertThat(tick(syncing)).isEmpty();
        consensusActive.set(true);
        assertThat(ticks(BOUND_TICKS, syncing)).isEmpty();
        assertThat(tick(syncing)).containsOnlyKeys(B);
    }

    /// A retained SYNCING report whose pong is older than the freshness bound (three ping intervals) never advances an episode, and a
    /// report going stale mid-episode ends it.
    @Test
    void aStaleReport_neverAdvancesTheEpisode() {
        var syncing = Map.of(A, READY, B, SYNCING, C, READY);

        pongAge.put(B, timeSpan(3001).millis());
        assertThat(ticks(BOUND_TICKS + 10, syncing)).as("stale for the whole bound").isEmpty();
        pongAge.put(B, timeSpan(3).seconds());
        assertThat(ticks(BOUND_TICKS, syncing)).as("a pong exactly at the freshness bound still counts").isEmpty();
        assertThat(tick(syncing)).containsOnlyKeys(B);
        pongAge.remove(B);
        assertThat(tick(syncing)).as("no pong at all ends the episode").isEmpty();
    }

    @Test
    void twoVoters_haveIndependentEpisodes() {
        ticks(60, Map.of(A, READY, B, SYNCING, C, READY));
        var both = Map.of(A, READY, B, SYNCING, C, SYNCING);

        assertThat(ticks(BOUND_TICKS - 59, both)).as("B reached the bound, C started 60 ticks later")
                                                 .containsExactly(Map.entry(B, StuckSyncingVoters.STUCK_BOUND.nanos()));
        assertThat(ticks(59, both)).containsOnlyKeys(B);
        assertThat(ticks(1, both)).containsOnlyKeys(B, C);
        tick(Map.of(A, READY, B, READY, C, SYNCING));
        assertThat(tick(both)).as("B's READY ends only B's episode").containsOnlyKeys(C);
    }

    @Test
    void aDepartedAndReAddedVoter_startsFromZero() {
        var syncing = Map.of(A, READY, B, SYNCING, C, READY);

        ticks(BOUND_TICKS - 1, syncing);
        clock.addAndGet(TICK);
        assertThat(stuck.stuck(Set.of(A, C), syncing)).as("B left the roster").isEmpty();
        assertThat(ticks(BOUND_TICKS, syncing)).isEmpty();
        assertThat(tick(syncing)).containsOnlyKeys(B);
    }

    /// Evaluations further apart than the gap (the leader did not evaluate) do not continue an episode; a gap of exactly the bound does.
    @Test
    void anEvaluationGap_endsTheEpisode() {
        var syncing = Map.of(A, READY, B, SYNCING, C, READY);

        ticks(BOUND_TICKS - 9, syncing);
        clock.addAndGet(StuckSyncingVoters.EVALUATION_GAP.nanos() - TICK);
        assertThat(ticks(5, syncing)).as("a gap of exactly the bound continues, and its time counts").isEmpty();
        assertThat(tick(syncing)).containsExactly(Map.entry(B, StuckSyncingVoters.STUCK_BOUND.nanos()));
        clock.addAndGet(StuckSyncingVoters.EVALUATION_GAP.nanos());
        assertThat(tick(syncing)).as("a gap longer than the bound ends it").isEmpty();
    }

    @Test
    void theStuckWarning_isRaisedOncePerEpisode_andTheEndRaisesItsRecovery() {
        var syncing = Map.of(A, READY, B, SYNCING, C, READY);

        ticks(BOUND_TICKS + 30, syncing);
        tick(Map.of(A, READY, B, READY, C, READY));
        ticks(BOUND_TICKS + 1, syncing);

        var warnings = delivered();

        assertThat(warnings).extracting(OperatorWarning::code)
                            .containsExactly(OperatorWarningCode.CORE_VOTER_SYNCING_STUCK,
                                             OperatorWarningCode.CORE_VOTER_SYNCING_ENDED,
                                             OperatorWarningCode.CORE_VOTER_SYNCING_STUCK);
        assertThat(warnings).extracting(OperatorWarning::subject).containsOnly("b");
        assertThat(warnings.getFirst().message()).contains("Core voter b has reported SYNCING for 300 s");
    }

    /// An episode that never reached the bound ends silently: the recovery follows only a raised warning.
    @Test
    void anEpisodeShorterThanTheBound_raisesNothing() {
        ticks(BOUND_TICKS - 1, Map.of(A, READY, B, SYNCING, C, READY));
        tick(Map.of(A, READY, B, READY, C, READY));

        assertThat(delivered()).isEmpty();
    }

    /// #3743: a deposed leader's reconciler never evaluates again, so the loss of leadership itself ends the warned episode, once.
    @Test
    void aDeposedLeader_raisesTheRecoveryOnTheLossEdge_exactlyOnce() {
        ticks(BOUND_TICKS + 1, Map.of(A, READY, B, SYNCING, C, READY));
        stuck.onLeaderChange(LeaderNotification.leaderChange(Option.none(), false));
        stuck.onLeaderChange(LeaderNotification.leaderChange(Option.some(A), false));

        var warnings = delivered();

        assertThat(warnings).extracting(OperatorWarning::code, OperatorWarning::subject)
                            .containsExactly(tuple(OperatorWarningCode.CORE_VOTER_SYNCING_STUCK, "b"),
                                             tuple(OperatorWarningCode.CORE_VOTER_SYNCING_ENDED, "b"));
        assertThat(warnings.getLast().message()).as("the message names the end causes the docs name, the loss of leadership and the gap")
                                                .contains("lost leadership", "stale report", "did not evaluate for more than 5 s");
    }

    /// #3743, the verifier's attack: raise STUCK, depose the leader, regain leadership under a new epoch and keep evaluating: still one
    /// recovery for the one warning, and the regained tenure counts its episode from zero.
    @Test
    void regainingLeadership_afterTheLossEdge_raisesNoSecondRecovery() {
        var syncing = Map.of(A, READY, B, SYNCING, C, READY);

        ticks(BOUND_TICKS + 1, syncing);
        stuck.onLeaderChange(LeaderNotification.leaderChange(Option.none(), false));
        assertThat(delivered()).as("the recovery is raised on the edge, while still deposed")
                               .extracting(OperatorWarning::code)
                               .containsExactly(OperatorWarningCode.CORE_VOTER_SYNCING_STUCK,
                                                OperatorWarningCode.CORE_VOTER_SYNCING_ENDED);
        epoch.set(Option.some(8L));

        assertThat(ticks(BOUND_TICKS, syncing)).as("the regained tenure starts from zero").isEmpty();
        assertThat(delivered()).extracting(OperatorWarning::code)
                               .containsExactly(OperatorWarningCode.CORE_VOTER_SYNCING_STUCK,
                                                OperatorWarningCode.CORE_VOTER_SYNCING_ENDED);
        assertThat(tick(syncing)).as("and is stuck again only after its own bound").containsOnlyKeys(B);
        assertThat(delivered()).extracting(OperatorWarning::code)
                               .containsExactly(OperatorWarningCode.CORE_VOTER_SYNCING_STUCK,
                                                OperatorWarningCode.CORE_VOTER_SYNCING_ENDED,
                                                OperatorWarningCode.CORE_VOTER_SYNCING_STUCK);
    }

    /// Every warned voter's episode ends on the one edge, each with its own single recovery.
    @Test
    void theLossEdge_endsEveryWarnedEpisode_oneRecoveryEach() {
        ticks(BOUND_TICKS + 1, Map.of(A, SYNCING, B, SYNCING, C, READY));
        stuck.onLeaderChange(LeaderNotification.leaderChange(Option.none(), false));

        assertThat(delivered()).extracting(OperatorWarning::code, OperatorWarning::subject)
                               .containsExactlyInAnyOrder(tuple(OperatorWarningCode.CORE_VOTER_SYNCING_STUCK, "a"),
                                                          tuple(OperatorWarningCode.CORE_VOTER_SYNCING_STUCK, "b"),
                                                          tuple(OperatorWarningCode.CORE_VOTER_SYNCING_ENDED, "a"),
                                                          tuple(OperatorWarningCode.CORE_VOTER_SYNCING_ENDED, "b"));
    }

    /// A change that names this node leader is not a loss: the episode goes on and is not ended by it.
    @Test
    void aLeaderChangeNamingThisNode_endsNothing() {
        var syncing = Map.of(A, READY, B, SYNCING, C, READY);

        ticks(BOUND_TICKS + 1, syncing);
        stuck.onLeaderChange(LeaderNotification.leaderChange(Option.some(A), true));

        assertThat(tick(syncing)).as("the same episode, still stuck").containsOnlyKeys(B);
        assertThat(delivered()).extracting(OperatorWarning::code)
                               .containsExactly(OperatorWarningCode.CORE_VOTER_SYNCING_STUCK);
    }

    /// The recovery follows only a raised warning, on the edge as everywhere else.
    @Test
    void theLossEdge_endsAnUnwarnedEpisodeSilently() {
        ticks(BOUND_TICKS - 1, Map.of(A, READY, B, SYNCING, C, READY));
        stuck.onLeaderChange(LeaderNotification.leaderChange(Option.none(), false));

        assertThat(delivered()).isEmpty();
    }
}
