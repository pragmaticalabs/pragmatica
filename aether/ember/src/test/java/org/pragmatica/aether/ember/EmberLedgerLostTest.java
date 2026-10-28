// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;

/// #2429 — the limits of the identity ledger, asserted as the behaviour they are (enabled tripwires), and the merge at join
/// (ID-1c) that ends the isolation of a wiped holder.
///
/// - **Every holder's ledger directory is lost** with its memory: nothing is left to remember the retirement, so the
///   restarted cores admit a process for the retired NodeId. The cluster that forms is new; there are no identities left to
///   protect, which is also how a cluster behaved before the ledger existed.
/// - **One holder's ledger FILE is lost while its directory stays**: the node is not new and its facts are gone, so it is
///   WIPED. After a restart every peer is new to it, so it admits no peer until a merge with a peer's ledger completes, and it
///   does so quietly: the process that arrives is not told its identity is retired (that would make it exit), its connection is
///   closed and it keeps redialing. With no peer to merge with it stays that way (the "Alone" arm); with a peer up, the Hello
///   exchange carries the peer's retirements, the wiped holder unions them into its ledger and only then admits (the regain arm).
@PortBudget
class EmberLedgerLostTest {
    private final IdentityRestartHarness harness = new IdentityRestartHarness("ell");

    @AfterEach
    void tearDown() {
        harness.stop();
    }

    @Test
    @Timeout(1200)
    void everyHoldersLedgerDirectoryLost_admitsTheRetiredNodeId_aNewClusterWithNothingToProtect() {
        harness.start();
        harness.retireXAtBothHolders();
        harness.killBothHolders();
        harness.deleteLedgerDirectory(harness.coreA);
        harness.deleteLedgerDirectory(harness.observer);

        var restartedA = harness.relaunch(harness.coreA);
        var restartedB = harness.relaunch(harness.observer);

        IdentityRestartHarness.awaitCondition("the restarted holders link to each other",
                                              () -> restartedA.connectedNodeCount() >= 1 && restartedB.connectedNodeCount() >= 1,
                                              120_000L);

        var verdict = harness.launchRetiredNodeIdAmong(restartedB, 1);

        assertThat(verdict.processExited()).as("nothing remembers the retirement: the process stays up").isFalse();
        assertThat(verdict.linked()).as("and the restarted holder links to it").isTrue();
        assertThat(verdict.refusalsAdded()).as("no refusal").isZero();
    }

    @Test
    @Timeout(1200)
    void oneHoldersLedgerFileLost_leavesItWiped_admittingNoNewPeer() {
        harness.start();
        harness.retireX();
        harness.killCoreA();
        harness.killObserver();
        harness.deleteLedgerFile(harness.observer);

        var restartedB = harness.relaunch(harness.observer);
        var verdict = harness.launchRetiredNodeIdAlone(restartedB);

        assertThat(verdict.linked()).as("a wiped holder links to no new peer (A is down: any CONNECTED link of it is X)").isFalse();
        assertThat(verdict.processExited()).as("it is not told its identity is retired: it is not a verdict").isFalse();
        assertThat(verdict.refusalsAdded()).as("an unavailable registry is not a refusal").isZero();
    }

    @Test
    @Timeout(1200)
    void oneHoldersLedgerFileLost_regainsTheRetirementFromAPeer_thenRefuses() {
        harness.start();
        harness.retireXAtBothHolders();
        harness.killObserver();
        harness.deleteLedgerFile(harness.observer);

        var restartedB = harness.relaunch(harness.observer);

        IdentityRestartHarness.awaitCondition("the wiped holder links to the surviving core: it is not isolated",
                                              () -> restartedB.connectedNodeCount() >= 1,
                                              120_000L);
        IdentityRestartHarness.awaitCondition("the wiped holder's ledger holds the retirement again, durably",
                                              () -> harness.ledgerRetires(harness.observer, harness.retired),
                                              60_000L);

        // The surviving holder goes away, so the only memory of the retirement left in the cluster is the one B regained.
        harness.killCoreA();

        var verdict = harness.launchRetiredNodeIdAlone(restartedB);

        assertThat(verdict.processExited()).as("after the merge the wiped holder refuses the retired NodeId on its own").isTrue();
        assertThat(verdict.refusalsAdded()).as("and it is B that refused it").isPositive();
        assertThat(verdict.linked()).isFalse();
    }
}
