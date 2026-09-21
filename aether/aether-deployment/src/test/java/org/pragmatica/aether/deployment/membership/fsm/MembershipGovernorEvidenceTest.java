// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.statemachine.FsmObserver;
import static org.assertj.core.api.Assertions.assertThat;

class MembershipGovernorEvidenceTest {
    @Test void governorEvidence_promotesWorkerAndRecordsDistinctRecoveryProvenance() {
        var membership = MembershipFsm.membershipFsm(FsmObserver.noop());
        var worker = new NodeId("worker");
        var governor = new NodeId("governor");
        var descriptor = new MemberDescriptor(Option.none(), "worker", "source");
        var transitions = new ArrayList<MembershipTransitionRecord>();
        membership.onTransition(transitions::add);
        membership.onGovernorHealthy(worker, "community", governor, 1, 4, descriptor);
        assertThat(membership.memberStates()).containsEntry(worker, "Member");
        assertThat(membership.coreCountedMembers()).doesNotContain(worker);
        membership.onSwimSuspect(worker, 4);
        membership.onGovernorHealthy(worker, "community", governor, 1, 3, descriptor);
        assertThat(membership.memberStates()).containsEntry(worker, "Suspect");
        membership.onGovernorHealthy(worker, "community", governor, 1, 4, descriptor);
        assertThat(membership.memberStates()).containsEntry(worker, "Member");
        assertThat(transitions.getLast().cause()).isEqualTo("GovernorHealthy");
    }

    @Test void governorEvidence_cannotAdmitCoreRole() {
        var membership = MembershipFsm.membershipFsm(FsmObserver.noop());
        var core = new NodeId("core");
        membership.onGovernorHealthy(core, "community", new NodeId("governor"), 1, 1,
            new MemberDescriptor(Option.none(), "core", "source"));
        assertThat(membership.memberStates()).doesNotContainKey(core);
    }
    @Test void indirectWorkerAwaitingFirstReport_isNotReapedByDirectProbeGrace() {
        var membership = MembershipFsm.membershipFsm(FsmObserver.noop());
        var worker = new NodeId("worker");
        membership.setJoinGraceReapEligibility(node -> !node.equals(worker));
        membership.onSwimUnknown(worker, 1);
        membership.onJoinGraceExpired(worker);
        assertThat(membership.memberStates()).containsEntry(worker, "Observed");
    }

    @Test void admittedWorkerQueuedBehindProbeBudget_survivesGraceAndPromotesFromDirectProof() {
        var membership = MembershipFsm.membershipFsm(FsmObserver.noop());
        var worker = new NodeId("late-admitted-worker");
        var descriptor = new MemberDescriptor(Option.none(), "worker", "source");
        var trustedAdmissions = java.util.Set.of(worker);
        membership.setJoinGraceReapEligibility(node -> !trustedAdmissions.contains(node));
        membership.onSwimUnknown(worker, 1);
        membership.onJoinGraceExpired(worker);
        assertThat(membership.memberStates()).containsEntry(worker, "Observed");
        membership.onWorkerAdmissionHealthy(worker, 1, descriptor);
        assertThat(membership.memberStates()).containsEntry(worker, "Member");
        assertThat(membership.coreCountedMembers()).doesNotContain(worker);
        membership.onWorkerAdmissionHealthy(new NodeId("claimed-core"), 1, new MemberDescriptor(Option.none(), "core", "source"));
        assertThat(membership.memberStates()).doesNotContainKey(new NodeId("claimed-core"));
    }

}
