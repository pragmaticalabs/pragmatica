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

import static org.assertj.core.api.Assertions.assertThat;

/// #2098: the composition AetherNode hands the core voter reconciler as its `failed` set. A dead voter that this set misses is never healed
/// (it stays "healthy" for ever), so each source is pinned on its own: emptying either one turns a case red.
class FailedVoterEvidenceTest {
    private static final NodeId A = new NodeId("a");
    private static final NodeId B = new NodeId("b");
    private static final NodeId C = new NodeId("c");
    private static final NodeId D = new NodeId("d");

    @Test
    void anObservedNonReadyNode_isFailed_evenWhenMembershipStillCallsItAMember() {
        var failed = FailedVoterEvidence.failedVoterEvidence(() -> Map.of(A, NodeReportedState.READY, B, NodeReportedState.SYNCING, C, NodeReportedState.DRAINING),
                                                             () -> Map.of(A, "Member", B, "Member", C, "Member", D, "Member"));

        assertThat(failed.get()).containsExactlyInAnyOrder(B, C);
    }

    @Test
    void aDeadOrDepartingMember_isFailed_withNoReadinessReportAtAll() {
        var failed = FailedVoterEvidence.failedVoterEvidence(Map::of, () -> Map.of(A, "Member", B, "Dead", C, "Departing"));

        assertThat(failed.get()).containsExactlyInAnyOrder(B, C);
    }

    /// Control: a node neither observed non-READY nor held Dead/Departing is NOT failed, whatever else is going on (the amendment).
    @Test
    void anUnobservedAliveNode_isNeverFailed() {
        var failed = FailedVoterEvidence.failedVoterEvidence(() -> Map.of(B, NodeReportedState.SYNCING), () -> Map.of(A, "Member", B, "Dead"));

        assertThat(failed.get()).containsExactly(B).doesNotContain(A, D);
    }

    /// Both sources are read on every call: the set follows the cluster, not the moment of wiring.
    @Test
    void theSetFollowsTheLiveSources() {
        var reported = new AtomicReference<Map<NodeId, NodeReportedState>>(Map.of());
        var members = new AtomicReference<Map<NodeId, String>>(Map.of(A, "Member"));
        var failed = FailedVoterEvidence.failedVoterEvidence(reported::get, members::get);

        assertThat(failed.get()).isEmpty();
        reported.set(Map.of(A, NodeReportedState.SYNCING));
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

        org.mockito.Mockito.when(collector.reportedStates()).thenReturn(Map.of(B, NodeReportedState.SYNCING));
        org.mockito.Mockito.when(fsm.memberStates()).thenReturn(Map.of(A, "Dead", C, "Member"));
        var failed = FailedVoterEvidence.forNode(collector, current::get);

        assertThat(failed.get()).as("no FSM yet: the collector alone").containsExactly(B);
        current.set(fsm);
        assertThat(failed.get()).containsExactlyInAnyOrder(A, B);
    }
}
