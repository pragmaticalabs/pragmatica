// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;

/// #2429 — the limits of the identity ledger, asserted as the behaviour they are today (enabled tripwires).
///
/// - **Every holder's ledger directory is lost** with its memory: nothing is left to remember the retirement, so the
///   restarted cores admit a process for the retired NodeId. The cluster that forms is new; there are no identities left to
///   protect, which is also how a cluster behaved before the ledger existed.
/// - **One holder's ledger FILE is lost while its directory stays**: the node is not new and its facts are gone, so it is
///   WIPED. It admits no new peer (it cannot tell which NodeIds it retired), and it does so quietly: the process that arrives
///   is not told its identity is retired (that would make it exit), its connection is closed and it keeps redialing.
///   Recovering the fact from a peer is the merge at join, which is not part of this change: that arm is disabled below.
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

        harness.relaunch(harness.coreA);
        var restartedB = harness.relaunch(harness.observer);
        var verdict = harness.launchRetiredNodeId(restartedB);

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
        var verdict = harness.launchRetiredNodeId(restartedB);

        assertThat(verdict.linked()).as("a wiped holder links to no new peer").isFalse();
        assertThat(verdict.processExited()).as("it is not told its identity is retired: it is not a verdict").isFalse();
        assertThat(verdict.refusalsAdded()).as("an unavailable registry is not a refusal").isZero();
    }

    @Disabled("enable with the merge at join (ID-1c): a wiped holder regains the retirement from a peer's ledger, then refuses the retired NodeId")
    @Test
    @Timeout(1200)
    void oneHoldersLedgerFileLost_regainsTheRetirementFromAPeer_thenRefuses() {
        harness.start();
        harness.retireXAtBothHolders();
        harness.killObserver();
        harness.deleteLedgerFile(harness.observer);

        var restartedB = harness.relaunch(harness.observer);
        var verdict = harness.launchRetiredNodeId(restartedB);

        assertThat(verdict.processExited()).as("after the merge the wiped holder refuses the retired NodeId").isTrue();
        assertThat(verdict.linked()).isFalse();
    }
}
