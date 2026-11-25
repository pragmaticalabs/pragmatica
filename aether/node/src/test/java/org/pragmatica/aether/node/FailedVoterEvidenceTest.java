// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.metrics.NodeReportedState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2098: the composition AetherNode hands the core voter reconciler as its `failed` set. A dead voter that this set misses is never healed
/// (it stays "healthy" for ever), so each source is pinned on its own: emptying either one turns a case red.
class FailedVoterEvidenceTest {
    private static final NodeId A = new NodeId("a");
    private static final NodeId B = new NodeId("b");
    private static final NodeId C = new NodeId("c");
    private static final NodeId D = new NodeId("d");

    /// The #2136 episodes of a node that is not leading: they never report a stuck voter, so the cases above see only the #2098 sources.
    private static StuckSyncingVoters notLeading() {
        return StuckSyncingVoters.stuckSyncingVoters(Option::none, () -> true, _ -> Option.none(), timeSpan(1).seconds(), System::nanoTime);
    }

    @Test
    void anObservedNonReadyNode_isFailed_evenWhenMembershipStillCallsItAMember() {
        var failed = FailedVoterEvidence.failedVoterEvidence(() -> Map.of(A, NodeReportedState.READY, B, NodeReportedState.DRAINING, C, NodeReportedState.DRAINING),
                                                             () -> Map.of(A, "Member", B, "Member", C, "Member", D, "Member"),
                                                             Set::of,
                                                             notLeading());

        assertThat(failed.get()).containsExactlyInAnyOrder(B, C);
    }

    @Test
    void aDeadOrDepartingMember_isFailed_withNoReadinessReportAtAll() {
        var failed = FailedVoterEvidence.failedVoterEvidence(Map::of, () -> Map.of(A, "Member", B, "Dead", C, "Departing"), Set::of, notLeading());

        assertThat(failed.get()).containsExactlyInAnyOrder(B, C);
    }

    /// Control: a node neither observed non-READY nor held Dead/Departing is NOT failed, whatever else is going on (the amendment).
    @Test
    void anUnobservedAliveNode_isNeverFailed() {
        var failed = FailedVoterEvidence.failedVoterEvidence(() -> Map.of(B, NodeReportedState.DRAINING), () -> Map.of(A, "Member", B, "Dead"), Set::of, notLeading());

        assertThat(failed.get()).containsExactly(B).doesNotContain(A, D);
    }

    /// Both sources are read on every call: the set follows the cluster, not the moment of wiring.
    @Test
    void theSetFollowsTheLiveSources() {
        var reported = new AtomicReference<Map<NodeId, NodeReportedState>>(Map.of());
        var members = new AtomicReference<Map<NodeId, String>>(Map.of(A, "Member"));
        var failed = FailedVoterEvidence.failedVoterEvidence(reported::get, members::get, Set::of, notLeading());

        assertThat(failed.get()).isEmpty();
        reported.set(Map.of(A, NodeReportedState.DRAINING));
        assertThat(failed.get()).containsExactly(A);
        reported.set(Map.of());
        members.set(Map.of(A, "Dead"));
        assertThat(failed.get()).containsExactly(A);
    }

    /// The node's real wiring: the collector's reported states and the FSM's member verdicts reach the set; with no FSM yet, only the
    /// collector speaks.
    @Test
    void forNode_readsTheCollectorAndTheMembershipFsm() {
        var collector = org.mockito.Mockito.mock(org.pragmatica.aether.metrics.ClusterSyncCollector.class);
        var fsm = org.mockito.Mockito.mock(org.pragmatica.aether.deployment.membership.fsm.MembershipFsm.class);
        var current = new AtomicReference<org.pragmatica.aether.deployment.membership.fsm.MembershipFsm>();

        org.mockito.Mockito.when(collector.reportedStates()).thenReturn(Map.of(B, NodeReportedState.DRAINING));
        org.mockito.Mockito.when(fsm.memberStates()).thenReturn(Map.of(A, "Dead", C, "Member"));
        var failed = FailedVoterEvidence.forNode(collector, current::get, Set::of, notLeading());

        assertThat(failed.get()).as("no FSM yet: the collector alone").containsExactly(B);
        current.set(fsm);
        assertThat(failed.get()).containsExactlyInAnyOrder(A, B);
    }

    /// SYNCING is what a healthy follower reports across a leader change (the holder reports it whenever consensus is inactive), so it is
    /// not evidence of failure; DRAINING is.
    @Test
    void anObservedSyncingNode_isNotFailed() {
        var failed = FailedVoterEvidence.failedVoterEvidence(() -> Map.of(A, NodeReportedState.SYNCING, B, NodeReportedState.DRAINING),
                                                             () -> Map.of(A, "Member", B, "Member"),
                                                             Set::of,
                                                             notLeading());

        assertThat(failed.get()).containsExactly(B);
    }

    /// #2136 (addendum A): of several stuck voters only ONE counts, the longest episode; equal episodes fall to the lower id.
    @Test
    void withAtMostOneStuck_addsOnlyTheLongestEpisode_tiesByTheLowerId() {
        var voters = Set.of(A, B, C);

        assertThat(FailedVoterEvidence.withAtMostOneStuck(Set.of(), voters, Map.of(B, 10L, C, 20L))).as("the longest, not the lowest id")
                                                                                                  .containsExactly(C);
        assertThat(FailedVoterEvidence.withAtMostOneStuck(Set.of(), voters, Map.of(C, 10L, B, 10L))).as("a tie falls to the lower id")
                                                                                                  .containsExactly(B);
        assertThat(FailedVoterEvidence.withAtMostOneStuck(Set.of(), voters, Map.of())).isEmpty();
    }

    /// #2136 (addendum A): a stuck voter counts only while NO voter of the installed roster has other evidence; evidence against a node
    /// outside the roster does not hold it back, and the other evidence itself is returned unchanged.
    @Test
    void withAtMostOneStuck_addsNothing_whileAVoterHasOtherEvidence() {
        var voters = Set.of(A, B, C);

        assertThat(FailedVoterEvidence.withAtMostOneStuck(Set.of(A), voters, Map.of(B, 10L))).containsExactly(A);
        assertThat(FailedVoterEvidence.withAtMostOneStuck(Set.of(D), voters, Map.of(B, 10L))).as("D is no voter")
                                                                                           .containsExactlyInAnyOrder(B, D);
    }
}
