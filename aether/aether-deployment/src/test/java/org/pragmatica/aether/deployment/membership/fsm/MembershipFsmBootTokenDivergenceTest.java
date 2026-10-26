// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.BootTokens;
import org.pragmatica.lang.Option;
import org.pragmatica.statemachine.FsmObserver;

import static org.assertj.core.api.Assertions.assertThat;

/// Two token stores decide "is this evidence from the process that owns NodeId X": the per-process
/// `BootTokens` registry (SWIM, QUIC handshake, inbound dispatch) and the membership FSM's own
/// first-token-wins slot (`MembershipContext#acceptBootToken`, behind `MemberTracking#processEvidenceRefusal`,
/// consulted by the governor-report and worker-admission paths only). They share no state, so the FSM can
/// admit a NodeId the registry retired. (The converse, refusing a token the registry admitted, also holds
/// today and has no twin here: under one store the two-different-tokens premise cannot be built.)
///
/// Each ingress path has two tests. The ENABLED one asserts today's acceptance and goes red the day the FSM
/// consults the registry (the day that change lands: flip it then). The DISABLED twin holds the real assertion,
/// the refusal the registry-backed FSM must give; enable it, and it is red on today's code.
class MembershipFsmBootTokenDivergenceTest {
    private static final String COMMUNITY = "community";
    private static final long FIRST_TOKEN = 0xA1L;
    private static final long CONFLICTING_TOKEN = 0xB2L;

    private final NodeId worker = new NodeId("worker");
    private final NodeId governor = new NodeId("governor");
    private final MemberDescriptor workerDescriptor = new MemberDescriptor(Option.none(), "worker", "source");
    private final BootTokens registry = BootTokens.bootTokens(0L);
    private final MembershipFsm fsm = MembershipFsm.membershipFsm(FsmObserver.noop());

    /// The registry saw `FIRST_TOKEN`, then `CONFLICTING_TOKEN` for the same NodeId: the NodeId is retired,
    /// every later token is refused.
    private void retireAtRegistry() {
        registry.admit(worker, FIRST_TOKEN);
        registry.admit(worker, CONFLICTING_TOKEN);
    }

    private void governorReports(long bootToken) {
        fsm.onGovernorHealthy(worker, COMMUNITY, governor, 1, bootToken, workerDescriptor);
    }

    @Test
    void governorReport_retiredAtTheRegistry_isAcceptedByAnFsmThatNeverSawTokens_today() {
        retireAtRegistry();

        assertThat(registry.admit(worker, CONFLICTING_TOKEN).admitted()).as("control: the registry refuses this token").isFalse();
        assertThat(registry.isRetired(worker)).as("control: the registry holds the NodeId retired").isTrue();

        governorReports(CONFLICTING_TOKEN);

        assertThat(fsm.memberStates()).as("today the FSM admits the retired NodeId as a member").containsEntry(worker, "Member");
        assertThat(fsm.refusedProcessEvidenceCount()).isZero();
    }

    @Disabled("enable at #2429: the FSM must consult the registry; today it holds its own first-token-wins slot")
    @Test
    void governorReport_retiredAtTheRegistry_isRefusedByTheFsm_target() {
        retireAtRegistry();

        governorReports(CONFLICTING_TOKEN);

        assertThat(fsm.memberStates()).doesNotContainKey(worker);
        assertThat(fsm.refusedProcessEvidenceCount()).isEqualTo(1);
    }

    @Test
    void workerAdmission_retiredAtTheRegistry_isAcceptedByAnFsmThatNeverSawTokens_today() {
        retireAtRegistry();

        assertThat(registry.admit(worker, FIRST_TOKEN).admitted()).as("control: even the first process is refused once retired").isFalse();

        fsm.onWorkerAdmissionHealthy(worker, FIRST_TOKEN, workerDescriptor);

        assertThat(fsm.memberStates()).containsEntry(worker, "Member");
        assertThat(fsm.refusedProcessEvidenceCount()).isZero();
    }

    @Disabled("enable at #2429: the FSM must consult the registry; today it holds its own first-token-wins slot")
    @Test
    void workerAdmission_retiredAtTheRegistry_isRefusedByTheFsm_target() {
        retireAtRegistry();

        fsm.onWorkerAdmissionHealthy(worker, FIRST_TOKEN, workerDescriptor);

        assertThat(fsm.memberStates()).doesNotContainKey(worker);
        assertThat(fsm.refusedProcessEvidenceCount()).isEqualTo(1);
    }
}
