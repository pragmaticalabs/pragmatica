// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;

/// #2429 — a restarted observer still refuses what it retired before the restart. The boot-token registry used to be
/// process memory (nothing survived a restart, the first token seen won again), so an observer restarted after every other
/// holder of a retirement was gone admitted a process for a NodeId it had retired (found by the MS-0a experiment; this
/// class was its flip). The retirement now rests in the observer's identity ledger, loaded before admission opens.
///
/// The sequence, three cores `obs-1` (A), `obs-2` (B, the observer) and `obs-3` (X):
///
/// 1. X's original process is killed; a NEW process under X's NodeId (new boot token) is refused by B and A: B retires X.
/// 2. A is killed, so that B is the only process left that can refuse anything. CONTROL, no restart of B: yet another
///    process under X is launched. B must refuse it again from its own registry (its refusal counter rises, the process
///    exits identity-refused, B never links to it). A control that does not reach B's registry fails the test; it does not
///    pass vacuously.
/// 3. B is killed and started again with its original configuration and its ledger directory (an operator restarting a
///    container). A is gone by now on purpose: it holds B's old boot token and would refuse a restarted B (matrix rows 1, 2
///    and 5 pin that for any peer that saw the old process), so B cannot restart under its own NodeId while any peer that
///    saw it is alive.
/// 4. A process is started under X, exactly as in the control. It is a process B's new registry has never seen, which is
///    what X's old process would be to it: the harness cannot replay a chosen token, and the registry's decision depends
///    on its content only (`BootTokensLedgerTest.admit_afterARestart_refusesEveryProcessForARetiredNodeId` pins the
///    old-token row at registry level).
///
/// Control and treatment run the same launch and the same assertions against the same observer NodeId, with the same peers
/// alive (none); the restart of B is the only difference. Before the ledger the treatment arm was red: B linked to X's
/// process, its refusal counter stayed at zero, the process stayed up.
///
/// The same-id relaunch matrix, one row per cell and the test that pins it:
///
/// | # | Cell | Pinned by |
/// |---|------|-----------|
/// | 1 | killed core's NodeId relaunched at a new address; survivors hold its token: refused, never counts | `EmberSameIdentityRelaunchTest.relaunchedSameIdentityProcess_atNewAddress_neverRestoresQuorum` |
/// | 2 | same, at the killed node's own address (container restart) | `EmberSameIdentityRelaunchTest.relaunchedSameIdentityProcess_atSameAddress_neverRestoresQuorum` |
/// | 3 | core killed while the cluster formed, NodeId then taken over: takeover refused | `EmberSameIdentityRelaunchTest.coreKilledWhileForming_andSupersededByTheSameNodeId_isDemotedOnTheSurvivor` |
/// | 4 | partitioned-but-live process, same token, partition heals: heals, no refusal | `EmberSameIdentityRelaunchTest.partitionedLiveProcess_sameToken_healsAndRestoresQuorum` |
/// | 5 | same-id restart after a voter swap, lagging voter holds the old roster: refused at transport | `EmberAmnesiacRestartTest.sameIdRestartAfterASwap_isRefusedAtTransport_andSlotAgreementHolds` |
/// | 6 | SWIM: different token in ANNOUNCE: identity retired, new process refused | `SwimBootTokenTest.differentToken_announce_retiresIdentity_andRefusesNewProcess` |
/// | 7 | SWIM: same token, higher incarnation: heals as before | `SwimBootTokenTest.sameToken_higherIncarnation_healsAsBefore` |
/// | 8 | SWIM: token 0 carries no identity, admitted | `SwimBootTokenTest.zeroToken_carriesNoProcessIdentity_andIsAdmitted` |
/// | 9 | QUIC acceptor: different token refused and retired / same token admitted | `QuicBootTokenAdmissionTest.acceptor_differentTokenProcess_isRefusedAndRetired`, `QuicBootTokenAdmissionTest.acceptor_sameTokenProcess_isAdmitted` |
/// | 10 | QUIC dialer: different token refused and retired / same token admitted | `QuicBootTokenAdmissionTest.dialer_differentTokenServer_isRefusedAndRetired`, `QuicBootTokenAdmissionTest.dialer_sameTokenServer_isAdmitted` |
/// | 11 | the rule itself: unknown / equal / different / retired / token 0 | `BootTokensTest`, and with a ledger `BootTokensLedgerTest` |
/// | 12 | membership FSM against the registry: FSM admits what the registry retired | `MembershipFsmBootTokenDivergenceTest` |
/// | 13 | the OBSERVER restarts, a process for the retired NodeId arrives: refused | this class |
/// | 14 | EVERY holder restarts (ledgers kept): refused; ledgers lost: admitted (the limit) | `EmberAllHoldersRestartKeepRetirementTest`, `EmberLedgerLostTest` |
@PortBudget
class EmberObserverRestartReadmitsRetiredTest {
    private final IdentityRestartHarness harness = new IdentityRestartHarness("obs");

    @AfterEach
    void tearDown() {
        harness.stop();
    }

    @Test
    @Timeout(900)
    void restartedObserver_refusesAProcessForTheNodeIdItRetired() {
        harness.start();
        harness.retireX();
        harness.killCoreA();

        var control = harness.launchRetiredNodeId(harness.node(harness.observer));
        var restarted = harness.restartObserver();
        var afterRestart = harness.launchRetiredNodeId(restarted);

        assertThat(control.processExited()).as("control: without the restart the process is refused and exits").isTrue();
        assertThat(control.linked()).as("control: without the restart the observer never links to it").isFalse();
        assertThat(control.refusalsAdded()).as("control: the refusal came from the observer's own registry").isPositive();

        assertThat(afterRestart.processExited()).as("the restarted observer refuses the process: it exits identity-refused").isTrue();
        assertThat(afterRestart.linked()).as("the restarted observer never links to it").isFalse();
        assertThat(afterRestart.refusalsAdded()).as("the refusal is counted by the restarted observer's own registry").isPositive();
    }
}
