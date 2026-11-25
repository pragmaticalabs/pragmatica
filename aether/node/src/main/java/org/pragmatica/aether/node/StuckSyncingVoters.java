// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.pragmatica.aether.metrics.NodeReportedState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.utility.warning.OperatorWarnings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// #2136: the leader's record of voters stuck in SYNCING, one EPISODE per voter, on an injected monotonic clock (the #1734 pattern).
///
/// SYNCING alone is not evidence of failure (#2098): a healthy follower reports it across a leader change. Rabia sync adopts a whole
/// snapshot, so there is no incremental progress to observe; the evidence is a SUSTAINED episode. An episode starts at the first
/// SYNCING report this leader observes for a voter and counts elapsed time only while every evaluation finds all of:
/// - (i) this node leading under the SAME leader epoch as the previous evaluation ([org.pragmatica.consensus.leader.LeaderManager#currentLeaderEpoch]:
///   a new election commits a strictly higher term, see [LeaderTerm]), so losing and regaining leadership between two evaluations ends it;
/// - (ii) this node's own consensus active (sampled at each evaluation);
/// - (iii) a report from the voter that is still ARRIVING: its last pong is at most [#REPORT_FRESHNESS_PING_INTERVALS] ping intervals old,
///   so a stale retained SYNCING report never advances an episode;
/// - and the previous evaluation at most [#EVALUATION_GAP] ago, so a stretch in which the leader did not evaluate (not leading, or a
///   reconfiguration in flight) is never counted as observed.
///
/// Anything else ends the episode: a READY (or any non-SYNCING) report, a missing or stale report, a leader change, own consensus
/// inactivity, the voter leaving the roster, or a gap; the next SYNCING report starts a new episode from zero. A voter is STUCK once
/// its episode lasted [#STUCK_BOUND]; the first evaluation that finds it stuck raises [OperatorWarningCode#CORE_VOTER_SYNCING_STUCK]
/// once per episode, and the end of a warned episode raises [OperatorWarningCode#CORE_VOTER_SYNCING_ENDED].
///
/// What this does NOT see: consensus is sampled, so an inactivity that begins and ends between two evaluations (shorter than one
/// reconciler tick, 1 s) does not end an episode. A leader that stops evaluating leaves a raised warning open until it evaluates again.
/// Which stuck voters count as failed (at most one, and only with no other evidence) is [FailedVoterEvidence]'s decision, not this one's.
public final class StuckSyncingVoters {
    private static final Logger LOG = LoggerFactory.getLogger(StuckSyncingVoters.class);
    /// How long a voter must stay in one SYNCING episode before it counts as stuck. MARKED GUESS (#2136): nothing was measured. It must
    /// outlast a worst-case snapshot adoption (sync retries plus the transfer of the largest snapshot); what settles it is a measured
    /// worst-case snapshot adoption. A named constant, deliberately not a configuration key.
    public static final TimeSpan STUCK_BOUND = timeSpan(5).minutes();
    /// The freshness bound of a voter's report, in ping intervals: the readiness sweep's own bound (`ClusterSyncAssembly`, three ping
    /// intervals), so a report this old is the one the readiness view already treats as gone.
    static final int REPORT_FRESHNESS_PING_INTERVALS = 3;
    /// Evaluations further apart than this do not continue an episode. MARKED GUESS: five ticks of the reconciler's 1 s cadence.
    static final TimeSpan EVALUATION_GAP = timeSpan(5).seconds();

    private final Supplier<Option<Long>> leaderEpoch;
    private final BooleanSupplier consensusActive;
    private final Function<NodeId, Option<TimeSpan>> sinceLastReport;
    private final long reportFreshnessNanos;
    private final LongSupplier nanoClock;

    private final AtomicReference<OperatorWarningSink> sink = new AtomicReference<>(OperatorWarningSink.logOnly());

    private final Map<NodeId, Episode> episodes = new HashMap<>();
    private Option<Evaluation> last = Option.none();

    private StuckSyncingVoters(Supplier<Option<Long>> leaderEpoch,
                               BooleanSupplier consensusActive,
                               Function<NodeId, Option<TimeSpan>> sinceLastReport,
                               long reportFreshnessNanos,
                               LongSupplier nanoClock) {
        this.leaderEpoch = leaderEpoch;
        this.consensusActive = consensusActive;
        this.sinceLastReport = sinceLastReport;
        this.reportFreshnessNanos = reportFreshnessNanos;
        this.nanoClock = nanoClock;
    }

    /// `leaderEpoch` is present only while this node leads; `sinceLastReport` is the age of the voter's last pong; `pingInterval` is the
    /// configured cluster ping interval the freshness bound is measured in; `nanoClock` is monotonic.
    public static StuckSyncingVoters stuckSyncingVoters(Supplier<Option<Long>> leaderEpoch,
                                                        BooleanSupplier consensusActive,
                                                        Function<NodeId, Option<TimeSpan>> sinceLastReport,
                                                        TimeSpan pingInterval,
                                                        LongSupplier nanoClock) {
        return new StuckSyncingVoters(leaderEpoch,
                                      consensusActive,
                                      sinceLastReport,
                                      pingInterval.nanos() * REPORT_FRESHNESS_PING_INTERVALS,
                                      nanoClock);
    }

    /// Where the warnings go from now on: the node binds its sink after its event layer exists, which is built after this.
    @Contract
    public void operatorWarnings(OperatorWarningSink sink) {
        this.sink.set(sink);
    }

    /// One evaluation: advances or ends every episode of `voters` (the installed roster) from `reported`, and returns the voters stuck
    /// now, each with its episode's elapsed nanoseconds. Every call is an evaluation, so the caller calls it on every pass.
    public synchronized Map<NodeId, Long> stuck(Set<NodeId> voters, Map<NodeId, NodeReportedState> reported) {
        var now = nanoClock.getAsLong();
        var current = leaderEpoch.get()
                                 .filter(_ -> consensusActive.getAsBoolean())
                                 .map(epoch -> Evaluation.evaluation(epoch, now));

        if (current.filter(this::continuesLast).isEmpty()) {
            endEpisodes(Set.of());
        }

        last = current;

        return current.map(_ -> advance(voters, reported, now))
                      .or(Map.of());
    }

    private boolean continuesLast(Evaluation next) {
        return last.filter(previous -> previous.continuedBy(next))
                   .isPresent();
    }

    private Map<NodeId, Long> advance(Set<NodeId> voters, Map<NodeId, NodeReportedState> reported, long now) {
        var syncing = voters.stream()
                            .filter(voter -> isSyncing(reported, voter) && hasFreshReport(voter))
                            .collect(Collectors.toUnmodifiableSet());

        endEpisodes(syncing);
        syncing.forEach(voter -> episodes.putIfAbsent(voter, Episode.episode(now, false)));

        return stuckAt(now);
    }

    private static boolean isSyncing(Map<NodeId, NodeReportedState> reported, NodeId voter) {
        return Option.option(reported.get(voter))
                     .filter(NodeReportedState.SYNCING::equals)
                     .isPresent();
    }

    private boolean hasFreshReport(NodeId voter) {
        return sinceLastReport.apply(voter)
                              .filter(age -> age.nanos() <= reportFreshnessNanos)
                              .isPresent();
    }

    private Unit endEpisodes(Set<NodeId> continuing) {
        episodes.keySet().stream().filter(voter -> !continuing.contains(voter)).toList().forEach(this::endEpisode);

        return unit();
    }

    private Unit endEpisode(NodeId voter) {
        return Option.option(episodes.remove(voter))
                     .filter(Episode::warned)
                     .map(_ -> raiseEnded(voter))
                     .or(unit());
    }

    private Map<NodeId, Long> stuckAt(long now) {
        var stuck = episodes.entrySet()
                            .stream()
                            .filter(entry -> entry.getValue()
                                                  .isStuckAt(now))
                            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                                                                  entry -> entry.getValue()
                                                                                .elapsedAt(now)));

        stuck.forEach(this::warnOnce);

        return stuck;
    }

    private Unit warnOnce(NodeId voter, long elapsedNanos) {
        var episode = episodes.get(voter);

        if (episode.warned()) {
            return unit();
        }

        episodes.put(voter, episode.warnedOnce());

        return raiseStuck(voter, elapsedNanos);
    }

    private Unit raiseStuck(NodeId voter, long elapsedNanos) {
        return OperatorWarnings.raise(LOG,
                                      sink.get(),
                                      OperatorWarningCode.CORE_VOTER_SYNCING_STUCK,
                                      voter.id(),
                                      "Core voter {} has reported SYNCING for {} s, at least the {} s bound, while this leader led without interruption with its consensus active and the voter's reports kept arriving: it now counts as failed for the core voter reconciler, which replaces at most one such voter per settled roster change, only while no voter has other failure evidence and only with an admitted READY candidate",
                                      voter.id(),
                                      TimeUnit.NANOSECONDS.toSeconds(elapsedNanos),
                                      TimeUnit.NANOSECONDS.toSeconds(STUCK_BOUND.nanos()));
    }

    private Unit raiseEnded(NodeId voter) {
        return OperatorWarnings.raise(LOG,
                                      sink.get(),
                                      OperatorWarningCode.CORE_VOTER_SYNCING_ENDED,
                                      voter.id(),
                                      "Core voter {} no longer counts as stuck in SYNCING: its episode ended (a READY or missing report, a leader change, consensus inactivity on this leader, or the voter left the roster)",
                                      voter.id());
    }

    /// One voter's SYNCING episode: when this leader first observed it, and whether its stuck warning was raised.
    record Episode(long startNanos, boolean warned) {
        static Episode episode(long startNanos, boolean warned) {
            return new Episode(startNanos, warned);
        }

        long elapsedAt(long now) {
            return now - startNanos;
        }

        boolean isStuckAt(long now) {
            return elapsedAt(now) >= STUCK_BOUND.nanos();
        }

        Episode warnedOnce() {
            return episode(startNanos, true);
        }
    }

    /// The conditions an evaluation observed: the leader epoch it led under and when it ran.
    record Evaluation(long leaderEpoch, long atNanos) {
        static Evaluation evaluation(long leaderEpoch, long atNanos) {
            return new Evaluation(leaderEpoch, atNanos);
        }

        boolean continuedBy(Evaluation next) {
            return next.leaderEpoch() == leaderEpoch && next.atNanos() - atNanos <= EVALUATION_GAP.nanos();
        }
    }
}
