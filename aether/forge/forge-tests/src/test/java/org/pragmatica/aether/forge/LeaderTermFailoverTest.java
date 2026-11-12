// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.time.Duration;

import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.deployment.membership.ntt.LeaderReconciler.ProvisioningDecisionSnapshot;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.ProvisioningDiagnostics;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;


/// #1527 live path: across real leader failovers (each a NEW election), every new leader's minted term
/// (the `rabiaTerm` of its generation epoch, the dominant component of every leader-authored
/// ownership/assignment epoch) equals the committed `LeaderValue.viewSequence` that named it and is
/// strictly above its predecessor's. A per-process count of local leader gains fails on the FIRST
/// failover: the successor is on its first tenure and mints term 1, which is both below its committed
/// sequence 2 (the assertion that reports first) and equal to the dead leader's term.
///
/// This test is the only one that exercises `AetherNode`'s wiring of `LeaderTerm`, so it runs in the
/// default forge job rather than behind the Heavy tag.
///
/// Five cores so two successive kills keep a quorum of three.
@Execution(ExecutionMode.SAME_THREAD)
class LeaderTermFailoverTest {
    private static final TimeSpan BUDGET = TimeSpan.timeSpan(120).seconds();

    private final EmberCluster cluster = EmberCluster.emberCluster(5, 24000, 24100, 24200, "leader-term");

    @AfterEach
    void stop() {
        LifecycleAwait.bestEffort("stop leader-term cluster", cluster, cluster.stop());
    }

    @Test
    void newLeaderTerm_isStrictlyAboveThePriorLeaders_acrossTwoFailovers() {
        LifecycleAwait.settled("start leader-term cluster", cluster, cluster.start());
        var first = awaitLeaderTerm(none());

        first.preLatched()
             .onPresent(preLatched -> assertThat(preLatched).as("the first leader (term %d) pre-latches reachedFullMembership "
                                                               + "exactly when its term is above 1",
                                                                first.term())
                                                .isEqualTo(first.term() > 1L));
        var second = failOver(first);

        failOver(second);
    }

    private LeaderTermObservation failOver(LeaderTermObservation prior) {
        LifecycleAwait.nodeSettled("kill leader " + prior.leaderId(),
                                   cluster,
                                   cluster.killNode(prior.leaderId()));
        var successor = awaitLeaderTerm(some(prior.leaderId()));

        assertThat(successor.term()).as("successor %s term vs prior leader %s term",
                                        successor.leaderId(),
                                        prior.leaderId())
                  .isGreaterThan(prior.term());
        assertThat(successor.preLatched().or(false)).as("never-led successor %s (committed term %d) must pre-latch reachedFullMembership "
                                                       + "at activation, before its first reconcile pass (#1559)",
                                                        successor.leaderId(),
                                                        successor.term())
                  .isTrue();

        return successor;
    }

    /// Waits for a leader other than `excluded` whose committed `LeaderKey` names it, then reads the term
    /// it mints. The committed sequence is the expected term: the value the fix derives the term from.
    /// Polled at 5 ms so that sampling of the first leader starts before its first pass (#2941).
    private LeaderTermObservation awaitLeaderTerm(Option<String> excluded) {
        await().atMost(BUDGET.duration())
             .pollInterval(Duration.ofMillis(5))
             .until(() -> settledLeader(excluded).isPresent());
        var leader = settledLeader(excluded).unwrap();
        var committed = committedLeader(leader).unwrap();
        var term = mintedTerm(leader);
        var preLatched = activationSnapshot(leader, excluded.isPresent());

        assertThat(term).as("leader %s term vs its committed viewSequence",
                            leader.self().id())
                  .isEqualTo(committed.viewSequence());

        return LeaderTermObservation.leaderTermObservation(leader.self().id(),
                                                           term,
                                                           preLatched);
    }

    /// #1559: what the pre-latch sets at activation is only visible until the first reconcile pass, which is
    /// delayed by the activation quiesce and can latch the same flag from observed membership. The snapshot is
    /// therefore read inside that window, and the window is asserted rather than assumed: a snapshot taken after
    /// the first pass could not tell the pre-latch from the pass.
    ///
    /// D1-react (#2648): the reconciler activates on the LeaderManager react executor, after the store delivered the
    /// `LeaderKey` notification, so a leader whose committed `LeaderKey` and minted term are visible may not have
    /// activated yet. A successor (`awaitActivation`) is therefore polled until its activation latched the flag or its
    /// first pass ran, whichever comes first. The pre-latch is set synchronously inside `activate()`, and every pass
    /// that can follow it is at least one reconcile debounce (100 ms) later: the activation pass one quiesce later, an
    /// event-triggered pass (`triggerReconcile`) one debounce after its trigger. Every trigger except `QUORUM_LOSS` is
    /// leader-gated, so armed only by that same `activate()`, and no quorum is lost here (each kill leaves 4, then 3 of
    /// 5). A pre-latch is therefore seen first, and a missing one shows as a snapshot taken after the pass (red).
    ///
    /// #2941: the first leader of a fresh cluster cannot be waited for the same way. Without a pre-latch there is
    /// nothing to see before its first pass, and that pass is NOT one quiesce away: on leader gain the bootstrap seeds
    /// the absent `ClusterConfigKey.CURRENT`, the commit fires `onConfigChange`, and a `CONFIG_CHANGE` pass runs one
    /// debounce after activation and latches the flag from observed full membership. A single read cannot be placed
    /// inside that window, and one taken before activation sees an unlatched flag whatever activation would do. The first
    /// leader is therefore sampled (see [`FirstLeaderSampler`]) from before its first pass until that pass has run.
    private static Option<Boolean> activationSnapshot(AetherNode leader, boolean awaitActivation) {
        if (!awaitActivation) {
            return FirstLeaderSampler.sampleUntilFirstPass(leader);
        }

        await().atMost(BUDGET.duration())
             .pollInterval(Duration.ofMillis(5))
             .until(() -> latchedOrEvaluated(decisionOf(leader)));
        var snapshot = decisionOf(leader);

        assertThat(snapshot.reason()).as("leader %s must be observed before its first reconcile pass",
                                         leader.self().id())
                  .isEqualTo("NOT_EVALUATED");

        return some(snapshot.reachedFullMembership());
    }

    /// #2941: samples the first leader's decision snapshot every 5 ms until its first pass has run, and decides from the
    /// whole series whether activation pre-latched `reachedFullMembership`.
    ///
    /// - A sample shows the pre-latch when it is `NOT_EVALUATED`, latched, and not yet armed: arming happens only inside a
    ///   pass, just before the pass latches the flag, so such a sample is a pre-latch, except when a single read straddles
    ///   the pass between its two latch writes. Two such samples in a row are therefore required; the straddling read is
    ///   followed by one that sees the pass's arm.
    /// - The window between activation and the first pass is at least one reconcile debounce (100 ms; see
    ///   [`#activationSnapshot`]). When the series starts before the pass and no two consecutive samples lie more than
    ///   [`#MAX_GAP`] apart, at least two samples fall inside that window, so a pre-latch cannot be missed and an
    ///   all-unlatched series proves there was none.
    /// - Anything else (the first sample already after the pass, a sampling stall, a lone latched sample) is
    ///   inconclusive: the observation is absent rather than guessed, and the run says so on stdout.
    private static final class FirstLeaderSampler {
        private static final long MAX_GAP = Duration.ofMillis(50).toNanos();

        private final AetherNode leader;
        private long previousStart = -1L;
        private long widestGap = 0L;
        private boolean startedAfterPass = false;
        private int latchedRun = 0;
        private int latchedSamples = 0;
        private boolean preLatchSeen = false;

        private FirstLeaderSampler(AetherNode leader) {
            this.leader = leader;
        }

        static Option<Boolean> sampleUntilFirstPass(AetherNode leader) {
            var sampler = new FirstLeaderSampler(leader);

            await().atMost(BUDGET.duration())
                 .pollDelay(Duration.ZERO)
                 .pollInterval(Duration.ofMillis(5))
                 .until(sampler::sampleIsEvaluated);

            return sampler.verdict();
        }

        private boolean sampleIsEvaluated() {
            var start = System.nanoTime();
            var snapshot = decisionOf(leader);

            record(start, System.nanoTime(), snapshot);

            return isEvaluated(snapshot);
        }

        private void record(long start, long end, ProvisioningDecisionSnapshot snapshot) {
            if (previousStart < 0L) {
                startedAfterPass = isEvaluated(snapshot);
            } else {
                widestGap = Math.max(widestGap, end - previousStart);
            }

            previousStart = start;
            var latched = latchedBeforeAnyPass(snapshot);

            latchedRun = latched
                         ? latchedRun + 1
                         : 0;
            latchedSamples += latched
                              ? 1
                              : 0;
            preLatchSeen |= latchedRun >= 2;
        }

        private Option<Boolean> verdict() {
            if (preLatchSeen) {
                return some(true);
            }

            if (startedAfterPass || widestGap >= MAX_GAP || latchedSamples > 0) {
                System.out.println("#2941 first leader " + leader.self().id()
                                  + ": pre-latch not observable in this run (startedAfterPass=" + startedAfterPass
                                  + ", widestGapMs=" + widestGap / 1_000_000L
                                  + ", latchedSamples=" + latchedSamples
                                  + ")");

                return none();
            }

            return some(false);
        }

        private static boolean latchedBeforeAnyPass(ProvisioningDecisionSnapshot snapshot) {
            return ! isEvaluated(snapshot)
                   && snapshot.reachedFullMembership()
                   && !snapshot.armedForProvisioning();
        }
    }

    private static boolean isEvaluated(ProvisioningDecisionSnapshot snapshot) {
        return ! "NOT_EVALUATED".equals(snapshot.reason());
    }

    private static boolean latchedOrEvaluated(ProvisioningDecisionSnapshot snapshot) {
        return snapshot.reachedFullMembership() || isEvaluated(snapshot);
    }

    private static ProvisioningDecisionSnapshot decisionOf(AetherNode leader) {
        return leader.provisioningDiagnostics()
                     .map(ProvisioningDiagnostics::decision)
                     .unwrap();
    }

    private Option<AetherNode> settledLeader(Option<String> excluded) {
        return cluster.currentLeader()
                      .filter(id -> isNotExcluded(excluded, id))
                      .flatMap(cluster::getNode)
                      .filter(LeaderTermFailoverTest::committedAsLeader)
                      .filter(node -> mintedTerm(node) > 0L);
    }

    private static boolean isNotExcluded(Option<String> excluded, String id) {
        return excluded.filter(id::equals)
                       .isEmpty();
    }

    private static long mintedTerm(AetherNode node) {
        return node.currentGenerationEpoch()
                   .rabiaTerm();
    }

    private static boolean committedAsLeader(AetherNode node) {
        return committedLeader(node).filter(value -> value.leader()
                                                          .equals(node.self()))
                              .isPresent();
    }

    private static Option<LeaderValue> committedLeader(AetherNode node) {
        return node.kvStore()
                   .getTyped(LeaderKey.INSTANCE, LeaderValue.class);
    }

    /// `preLatched` is absent when the leader was first read after its first reconcile pass (#2941).
    private record LeaderTermObservation(String leaderId, long term, Option<Boolean> preLatched) {
        static LeaderTermObservation leaderTermObservation(String leaderId, long term, Option<Boolean> preLatched) {
            return new LeaderTermObservation(leaderId, term, preLatched);
        }
    }
}
