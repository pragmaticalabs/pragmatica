// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;

/// #2429 — EVERY holder of a retirement is stopped and started again with its ledger directory, then a process for the
/// retired NodeId presents itself: refused. This is the event the issue is about (all the memory that held the retirement
/// is gone at once, so no peer is left to teach it); `EmberObserverRestartReadmitsRetiredTest` is the narrower case where one
/// holder restarts while the other is dead.
///
/// The sequence, three cores `ahr-1` (A), `ahr-2` (B) and `ahr-3` (X):
///
/// 1. X's original process is killed; new processes under X's NodeId are refused until both A and B hold the retirement in
///    their ledger files (checked on disk, not inferred).
/// 2. A and B are both killed and started again with their original configuration and ledger directories.
/// 3. The restarted pair link to each other: a cold restart of the whole cluster still forms, because the ledger keeps
///    retirements and not the boot tokens of live peers (a ledger that kept those would have every restarted core refuse
///    every other, and `EmberGenesisRecoveryTest` case (a) would fail with it).
/// 4. A process is started under X: the restarted A and B refuse it, it exits identity-refused, and neither ever links to it.
@PortBudget
class EmberAllHoldersRestartKeepRetirementTest {
    private final IdentityRestartHarness harness = new IdentityRestartHarness("ahr");

    @AfterEach
    void tearDown() {
        harness.stop();
    }

    @Test
    @Timeout(1200)
    void everyHolderRestarted_stillRefusesAProcessForTheNodeIdTheyRetired() {
        harness.start();
        harness.retireXAtBothHolders();
        harness.killBothHolders();

        var restartedA = harness.relaunch(harness.coreA);
        var restartedB = harness.relaunch(harness.observer);

        IdentityRestartHarness.awaitCondition("the restarted pair link to each other",
                                              () -> restartedA.connectedPeerIds().contains(new NodeId(harness.observer))
                                                    && restartedB.connectedPeerIds().contains(new NodeId(harness.coreA)),
                                              90_000L);

        assertThat(restartedA.transportMetrics().get("identity_ledger_durable").intValue()).as("the restarted holders report their ledger durable").isEqualTo(1);
        assertThat(restartedB.transportMetrics().get("identity_ledger_durable").intValue()).isEqualTo(1);

        var refusalsBefore = IdentityRestartHarness.refusals(restartedA) + IdentityRestartHarness.refusals(restartedB);
        var verdict = harness.launchRetiredNodeId(restartedB);

        assertThat(verdict.processExited()).as("the process for the retired NodeId is refused and exits identity-refused").isTrue();
        assertThat(verdict.linked()).as("the restarted observer never links to it").isFalse();
        assertThat(restartedA.connectedPeerIds()).as("neither does the restarted A").doesNotContain(new NodeId(harness.retired));
        assertThat(IdentityRestartHarness.refusals(restartedA) + IdentityRestartHarness.refusals(restartedB))
            .as("the refusal is counted by a restarted holder's own registry")
            .isGreaterThan(refusalsBefore);
    }
}
