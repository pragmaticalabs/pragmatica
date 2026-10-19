// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import java.util.Map;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.statemachine.FsmObserver;

import static org.assertj.core.api.Assertions.assertThat;

/// #1535 item 3: process evidence that the membership FSM refuses (different boot token, DEAD identity,
/// malformed report) must leave the member's descriptor exactly as it was. Before, the descriptor was merged
/// BEFORE the refusal, so a refused report still overwrote the member's address.
class MembershipRefusedEvidenceDescriptorTest {
    private static final NodeId WORKER = new NodeId("refused-worker");
    private static final NodeId GOVERNOR_NODE = new NodeId("refused-governor");
    private static final NodeAddress ORIGINAL = NodeAddress.nodeAddress("original-host", 5000).unwrap();
    private static final NodeAddress HIJACKER = NodeAddress.nodeAddress("hijacker-host", 6000).unwrap();
    private static final MemberDescriptor ORIGINAL_DESCRIPTOR = new MemberDescriptor(Option.some(ORIGINAL), "worker", "source");
    private static final MemberDescriptor HIJACK_DESCRIPTOR = new MemberDescriptor(Option.some(HIJACKER), "worker", "source");
    private static final long SWIM_BOOT = 1_790_018_000_000L;
    private static final long TOKEN = 0x5eed_0001L;
    private static final long OTHER_TOKEN = 0x5eed_0002L;

    enum Evidence {
        GOVERNOR, ADMISSION;

        void publish(MembershipFsm membership, long bootToken, MemberDescriptor descriptor) {
            switch (this) {
                case GOVERNOR -> membership.onGovernorHealthy(WORKER, "community", GOVERNOR_NODE, 1, bootToken, descriptor);
                case ADMISSION -> membership.onWorkerAdmissionHealthy(WORKER, bootToken, descriptor);
            }
        }
    }

    private static NodeAddress addressOf(MembershipFsm membership) {
        return membership.memberDescriptor(WORKER).unwrap().address().unwrap();
    }

    @ParameterizedTest
    @EnumSource(Evidence.class)
    void admittedEvidence_updatesTheDescriptor_control(Evidence evidence) {
        var membership = MembershipFsm.membershipFsm(FsmObserver.noop());

        evidence.publish(membership, TOKEN, ORIGINAL_DESCRIPTOR);
        evidence.publish(membership, TOKEN, HIJACK_DESCRIPTOR);

        assertThat(addressOf(membership)).as("an admitted report merges its descriptor (last address wins)").isEqualTo(HIJACKER);
    }

    @ParameterizedTest
    @EnumSource(Evidence.class)
    void differentTokenEvidence_leavesTheDescriptorUnchanged(Evidence evidence) {
        var membership = MembershipFsm.membershipFsm(FsmObserver.noop());

        evidence.publish(membership, TOKEN, ORIGINAL_DESCRIPTOR);
        evidence.publish(membership, OTHER_TOKEN, HIJACK_DESCRIPTOR);

        assertThat(membership.refusedProcessEvidenceCount()).isEqualTo(1);
        assertThat(addressOf(membership)).isEqualTo(ORIGINAL);
    }

    @ParameterizedTest
    @EnumSource(Evidence.class)
    void deadIdentityEvidence_leavesTheDescriptorUnchanged(Evidence evidence) {
        var membership = MembershipFsm.membershipFsm(FsmObserver.noop());

        evidence.publish(membership, TOKEN, ORIGINAL_DESCRIPTOR);
        membership.onSwimHealthy(WORKER, SWIM_BOOT);
        membership.onSwimDeparted(WORKER, SWIM_BOOT + 1);
        evidence.publish(membership, TOKEN, HIJACK_DESCRIPTOR);

        assertThat(membership.memberStates()).containsEntry(WORKER, "Dead");
        assertThat(membership.refusedProcessEvidenceCount()).isEqualTo(1);
        assertThat(addressOf(membership)).isEqualTo(ORIGINAL);
    }

    @ParameterizedTest
    @EnumSource(Evidence.class)
    void malformedEvidence_leavesTheDescriptorUnchanged(Evidence evidence) {
        var membership = MembershipFsm.membershipFsm(FsmObserver.noop());

        evidence.publish(membership, TOKEN, ORIGINAL_DESCRIPTOR);
        evidence.publish(membership, 0L, HIJACK_DESCRIPTOR);

        assertThat(addressOf(membership)).isEqualTo(ORIGINAL);
    }

    @ParameterizedTest
    @EnumSource(Evidence.class)
    void coreMemberEvidence_leavesTheDescriptorUnchanged(Evidence evidence) {
        var membership = MembershipFsm.membershipFsm(FsmObserver.noop());

        membership.onMemberDescriptor(NodeInfo.nodeInfo(WORKER, ORIGINAL, Map.of(NodeInfo.LABEL_ROLE, "core")));
        evidence.publish(membership, TOKEN, HIJACK_DESCRIPTOR);

        assertThat(membership.memberDescriptor(WORKER).unwrap().role()).as("process evidence never re-roles a core member").isEqualTo("core");
        assertThat(addressOf(membership)).isEqualTo(ORIGINAL);
        assertThat(membership.refusedProcessEvidenceCount()).as("a core member is skipped, not refused").isZero();
    }

    @ParameterizedTest
    @EnumSource(Evidence.class)
    void spotEvidence_isAdmittedLikeWorkerEvidence(Evidence evidence) {
        var membership = MembershipFsm.membershipFsm(FsmObserver.noop());
        var spotOriginal = new MemberDescriptor(Option.some(ORIGINAL), "spot", "source");
        var spotMoved = new MemberDescriptor(Option.some(HIJACKER), "spot", "source");

        evidence.publish(membership, TOKEN, spotOriginal);
        evidence.publish(membership, TOKEN, spotMoved);

        assertThat(addressOf(membership)).as("the spot role is a worker role: its report is admitted").isEqualTo(HIJACKER);
    }

    @ParameterizedTest
    @EnumSource(Evidence.class)
    void nonWorkerRoleEvidence_isIgnored(Evidence evidence) {
        var membership = MembershipFsm.membershipFsm(FsmObserver.noop());
        var gateway = new MemberDescriptor(Option.some(HIJACKER), "gateway", "source");

        evidence.publish(membership, TOKEN, ORIGINAL_DESCRIPTOR);
        evidence.publish(membership, TOKEN, gateway);

        assertThat(addressOf(membership)).isEqualTo(ORIGINAL);
    }
}
