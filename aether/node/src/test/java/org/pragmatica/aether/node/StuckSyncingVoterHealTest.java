// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.cluster.CoreVoterReconciler;
import org.pragmatica.aether.metrics.NodeReportedState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.rabia.ClusterConfig;
import org.pragmatica.consensus.rabia.VoterConfiguration;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.metrics.NodeReportedState.READY;
import static org.pragmatica.aether.metrics.NodeReportedState.SYNCING;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2136 through the REAL [CoreVoterReconciler]: the node's evidence composition ([FailedVoterEvidence] over [StuckSyncingVoters]) is
/// its `failed` set, one reconcile per clock tick, a reconfiguration installs the requested roster, and the test decides when the
/// installed roster has settled. Pins the heal and the no-heal, the no-candidate case, and the addendum's one-seat rule: two stuck
/// voters are healed one seat per settled change, and a dead voter beside a stuck one is replaced first.
class StuckSyncingVoterHealTest {
    private static final NodeId A = new NodeId("a");
    private static final NodeId B = new NodeId("b");
    private static final NodeId C = new NodeId("c");
    private static final NodeId D = new NodeId("d");
    private static final NodeId E = new NodeId("e");
    private static final NodeId F = new NodeId("f");
    private static final NodeId G = new NodeId("g");
    private static final long TICK = timeSpan(1).seconds().nanos();
    private static final long BOUND_TICKS = StuckSyncingVoters.STUCK_BOUND.nanos() / TICK;

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final AtomicReference<Option<Long>> epoch = new AtomicReference<>(Option.some(3L));
    private final AtomicReference<VoterConfiguration> installed = new AtomicReference<>();
    private final AtomicReference<Option<VoterConfiguration>> settled = new AtomicReference<>(Option.none());
    private final Map<NodeId, NodeReportedState> reported = new HashMap<>();
    private final AtomicReference<Map<NodeId, String>> members = new AtomicReference<>(Map.of());
    private final AtomicReference<Set<NodeId>> ready = new AtomicReference<>(Set.of());
    private final List<Set<NodeId>> requested = new ArrayList<>();
    private final List<OperatorWarning> raised = new CopyOnWriteArrayList<>();
    private final OperatorWarningSink sink = OperatorWarningSink.handingOffTo(raised::add);
    private final StuckSyncingVoters stuck = StuckSyncingVoters.stuckSyncingVoters(epoch::get,
                                                                                   () -> true,
                                                                                   _ -> Option.some(timeSpan(200).millis()),
                                                                                   timeSpan(1).seconds(),
                                                                                   clock::get);

    /// A leader `a` with the installed roster `voters` (settled), as many seats desired, and the real reconciler over the real evidence.
    private CoreVoterReconciler leaderOf(List<NodeId> voters) {
        installed.set(new VoterConfiguration(0, new ClusterConfig(voters)));
        settle();
        voters.forEach(voter -> reported.put(voter, READY));
        stuck.operatorWarnings(sink);

        return CoreVoterReconciler.coreVoterReconciler(A,
                                                       () -> true,
                                                       () -> Option.some(installed.get()),
                                                       settled::get,
                                                       voters::size,
                                                       ready::get,
                                                       Map::of,
                                                       FailedVoterEvidence.failedVoterEvidence(() -> Map.copyOf(reported),
                                                                                               members::get,
                                                                                               () -> Set.copyOf(installed.get()
                                                                                                                         .members()),
                                                                                               stuck),
                                                       this::install);
    }

    /// The engine applying a requested roster: it is installed at once and settles only when the test says so.
    private Promise<Unit> install(ClusterConfig roster) {
        requested.add(Set.copyOf(roster.members()));
        installed.set(new VoterConfiguration(installed.get()
                                                      .epoch() + 1,
                                             roster));

        return Promise.unitPromise();
    }

    private void settle() {
        settled.set(Option.some(installed.get()));
    }

    private void unsettle() {
        settled.set(Option.none());
    }

    private void ticks(CoreVoterReconciler reconciler, long ticks) {
        for (var i = 0L; i < ticks; i++) {
            clock.addAndGet(TICK);
            reconciler.reconcile()
                      .await();
        }
    }

    /// The hand-off sink is one FIFO thread: once a sentinel offered after the ticks arrives, every warning raised before it has.
    private List<OperatorWarning> delivered() {
        var sentinel = OperatorWarning.operatorWarning(OperatorWarningCode.CORE_VOTER_SYNCING_ENDED, "sentinel-" + UUID.randomUUID(), "sentinel");

        sink.accept(sentinel);
        await().atMost(Duration.ofSeconds(10)).until(() -> raised.contains(sentinel));

        return raised.stream().filter(warning -> !warning.subject().startsWith("sentinel-")).toList();
    }

    /// Heal: a voter SYNCING for the whole bound is replaced by the admitted READY candidate, at the bound and not one tick before, as
    /// one seat change; the warning is raised once.
    @Test
    void sustainedSyncing_healsTheSeatAtTheBound_andNotOneTickBefore() {
        var reconciler = leaderOf(List.of(A, B, C));

        reported.put(B, SYNCING);
        reported.put(D, READY);
        ready.set(Set.of(A, C, D));
        ticks(reconciler, BOUND_TICKS);
        assertThat(requested).as("one tick before the bound: the syncing voter is kept").isEmpty();
        ticks(reconciler, 1);
        assertThat(requested).containsExactly(Set.of(A, C, D));
        ticks(reconciler, 1);
        assertThat(requested).as("the healed roster is final").hasSize(1);
        assertThat(delivered()).as("raised once; the voter leaving the roster ends the episode and closes it")
                               .extracting(OperatorWarning::code, OperatorWarning::subject)
                               .containsExactly(tuple(OperatorWarningCode.CORE_VOTER_SYNCING_STUCK, "b"),
                                                tuple(OperatorWarningCode.CORE_VOTER_SYNCING_ENDED, "b"));
    }

    /// No heal: SYNCING across a leader change, then READY, is what a healthy follower does; together it lasts longer than the bound,
    /// but no one episode does, so nothing is requested and nothing is raised.
    @Test
    void briefSyncingAcrossALeaderChange_healsNothing() {
        var reconciler = leaderOf(List.of(A, B, C));

        reported.put(B, SYNCING);
        reported.put(D, READY);
        ready.set(Set.of(A, C, D));
        ticks(reconciler, BOUND_TICKS - 10);
        epoch.set(Option.some(4L));
        ticks(reconciler, 20);
        reported.put(B, READY);
        ready.set(Set.of(A, B, C, D));
        ticks(reconciler, BOUND_TICKS);

        assertThat(requested).isEmpty();
        assertThat(delivered()).isEmpty();
    }

    /// No admitted READY candidate: nothing is removed, however long the voter stays stuck, and the warning still fires, once.
    @Test
    void noCandidate_removesNothing_andWarnsOnce() {
        var reconciler = leaderOf(List.of(A, B, C));

        reported.put(B, SYNCING);
        ready.set(Set.of(A, C));
        ticks(reconciler, BOUND_TICKS + 60);

        assertThat(requested).isEmpty();
        assertThat(delivered()).extracting(OperatorWarning::code)
                               .containsExactly(OperatorWarningCode.CORE_VOTER_SYNCING_STUCK);
    }

    /// Addendum B: two stuck voters, two READY candidates: one seat per settled change, never both in one. The first one out is the
    /// LONGER episode (`c`, although `b` has the lower id); `b` follows only after that change applied and settled.
    @Test
    void twoStuckVoters_areHealedOneSeatPerSettledChange_longestEpisodeFirst() {
        var reconciler = leaderOf(List.of(A, B, C, D, E));

        unsettle();
        reported.put(C, SYNCING);
        reported.put(F, READY);
        reported.put(G, READY);
        ready.set(Set.of(A, D, E, F, G));
        ticks(reconciler, 10);
        reported.put(B, SYNCING);
        ticks(reconciler, BOUND_TICKS + 20);
        assertThat(requested).as("a change still pending: nothing is requested while both become stuck").isEmpty();

        settle();
        ticks(reconciler, 1);
        assertThat(requested).as("one seat: c out, f in").containsExactly(Set.of(A, B, D, E, F));
        ticks(reconciler, 30);
        assertThat(requested).as("b waits until that change settled").hasSize(1);

        settle();
        ticks(reconciler, 1);
        assertThat(requested).containsExactly(Set.of(A, B, D, E, F), Set.of(A, D, E, F, G));
    }

    /// Addendum B: a dead voter beside a stuck one: the dead one is replaced first (other evidence holds the stuck one back), and the
    /// stuck one only after that change settled.
    @Test
    void deadAndStuckVoters_theDeadOneIsReplacedFirst() {
        var reconciler = leaderOf(List.of(A, B, C, D, E));

        unsettle();
        reported.remove(B);
        members.set(Map.of(B, "Dead"));
        reported.put(C, SYNCING);
        reported.put(F, READY);
        reported.put(G, READY);
        ready.set(Set.of(A, D, E, F, G));
        ticks(reconciler, BOUND_TICKS + 20);

        settle();
        ticks(reconciler, 1);
        assertThat(requested).as("only the dead one").containsExactly(Set.of(A, C, D, E, F));

        settle();
        ticks(reconciler, 1);
        assertThat(requested).containsExactly(Set.of(A, C, D, E, F), Set.of(A, D, E, F, G));
    }
}
