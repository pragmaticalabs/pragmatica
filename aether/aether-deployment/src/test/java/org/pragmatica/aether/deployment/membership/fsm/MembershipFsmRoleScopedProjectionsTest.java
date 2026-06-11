// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.net.NodeRole;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.statemachine.FsmObserver;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// Wave 2 (cluster-topology-overhaul spec, invariant A8 — one core denominator): the
/// ROLE-SCOPED counting projections. [`MembershipFsm#coreCountedMembers`] is the quorum / heal /
/// role-assignment denominator (MEMBER + SUSPECT, explicit `role=worker` excluded, unknown role
/// conservatively included); [`MembershipFsm#strictCoreMemberCount`] is the `QuorumLossDetector`
/// numerator (state exactly MEMBER, core-role only — membership-fsm-unification §6). The
/// role-blind [`MembershipFsm#countedMembers`] stays untouched for genuinely role-agnostic
/// readers; these tests pin the divergence between the two.
class MembershipFsmRoleScopedProjectionsTest {
    private static final NodeId CORE_A = new NodeId("node-core-a");
    private static final NodeId WORKER_B = new NodeId("node-worker-b");
    private static final NodeId WORKER_C = new NodeId("node-worker-c");
    private static final NodeId UNLABELED_D = new NodeId("node-unlabeled-d");

    private static final TimeSpan SHORT_BACKSTOP = TimeSpan.timeSpan(40).millis();
    private static final long NO_HINT_DECAY = Long.MAX_VALUE;

    private static MembershipFsm activeManager() {
        return MembershipFsm.membershipFsm(FsmObserver.noop(),
                                           System::currentTimeMillis,
                                           NO_HINT_DECAY,
                                           SHORT_BACKSTOP);
    }

    private static void promoteToMember(MembershipFsm manager, NodeId id) {
        manager.onSwimHealthy(id, 1L);
    }

    private static void promoteWorker(MembershipFsm manager, NodeId id) {
        promoteToMember(manager, id);
        manager.onMemberDescriptor(labeledInfo(id, Map.of(NodeInfo.LABEL_ROLE, "worker")));
    }

    private static void promoteCore(MembershipFsm manager, NodeId id) {
        promoteToMember(manager, id);
        manager.onMemberDescriptor(labeledInfo(id, Map.of(NodeInfo.LABEL_ROLE, "core")));
    }

    private static NodeInfo labeledInfo(NodeId id, Map<String, String> labels) {
        return NodeInfo.nodeInfo(id, NodeAddress.nodeAddress("host-x", 6000).unwrap(), NodeRole.ACTIVE, labels);
    }

    private static void awaitDead(MembershipFsm manager, NodeId id) {
        await().atMost(2, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(manager.memberStates()).containsEntry(id, "Dead"));
    }

    @Nested
    class CoreCountedMembers {
        @Test
        void coreCountedMembers_excludesExplicitWorker_andIncludesUnknownRole() {
            var manager = activeManager();

            promoteCore(manager, CORE_A);
            promoteWorker(manager, WORKER_B);
            promoteToMember(manager, UNLABELED_D);

            assertThat(manager.countedMembers()).as("role-blind count includes everyone").containsExactlyInAnyOrder(CORE_A, WORKER_B, UNLABELED_D);
            assertThat(manager.coreCountedMembers()).as("core-scoped count excludes the explicit worker, keeps the unknown-role member (conservative)").containsExactlyInAnyOrder(CORE_A, UNLABELED_D);
        }

        @Test
        void coreCountedMembers_includesSuspectCoreMember() {
            var manager = activeManager();

            promoteCore(manager, CORE_A);
            manager.onSwimSuspect(CORE_A, 2L);

            assertThat(manager.memberStates()).containsEntry(CORE_A, "Suspect");
            assertThat(manager.coreCountedMembers()).containsExactly(CORE_A);
        }

        @Test
        void coreCountedMembers_excludesDeadMember() {
            var manager = activeManager();

            promoteCore(manager, CORE_A);
            manager.onSwimFaulty(CORE_A, 3L);
            manager.onLivenessGone(CORE_A);
            awaitDead(manager, CORE_A);

            assertThat(manager.coreCountedMembers()).isEmpty();
        }

        /// The headline W1 scenario: 1 core + 2 workers must NOT look like a quorum of a 3-core
        /// config (quorum 2) — Rabia has no voter majority. The core-scoped denominator is 1.
        @Test
        void coreCountedMembers_oneCoreTwoWorkers_isBelowQuorumTwoOfThree() {
            var manager = activeManager();

            promoteCore(manager, CORE_A);
            promoteWorker(manager, WORKER_B);
            promoteWorker(manager, WORKER_C);

            var quorumOfThree = 3 / 2 + 1;
            assertThat(manager.countedMembers()).as("the role-blind count would falsely pass quorum").hasSize(3);
            assertThat(manager.coreCountedMembers().size()).isLessThan(quorumOfThree);
        }
    }

    @Nested
    class StrictCoreMemberCount {
        @Test
        void strictCoreMemberCount_countsMemberStateCoreAndUnknownRole_excludesWorker() {
            var manager = activeManager();

            promoteCore(manager, CORE_A);
            promoteWorker(manager, WORKER_B);
            promoteToMember(manager, UNLABELED_D);

            assertThat(manager.strictCoreMemberCount()).isEqualTo(2);
        }

        @Test
        void strictCoreMemberCount_excludesSuspect_strictMemberOnly() {
            var manager = activeManager();

            promoteCore(manager, CORE_A);
            manager.onSwimSuspect(CORE_A, 2L);

            assertThat(manager.memberStates()).containsEntry(CORE_A, "Suspect");
            assertThat(manager.strictCoreMemberCount()).as("strict numerator excludes SUSPECT").isZero();
            assertThat(manager.coreCountedMembers()).as("the counting projection still counts SUSPECT").containsExactly(CORE_A);
        }

        @Test
        void strictCoreMemberCount_oneCoreTwoWorkers_isOne() {
            var manager = activeManager();

            promoteCore(manager, CORE_A);
            promoteWorker(manager, WORKER_B);
            promoteWorker(manager, WORKER_C);

            assertThat(manager.strictCoreMemberCount()).isEqualTo(1);
        }
    }
}
