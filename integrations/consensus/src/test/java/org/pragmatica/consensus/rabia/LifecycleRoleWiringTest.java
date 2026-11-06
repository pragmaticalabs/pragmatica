// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.consensus.rabia;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// L-1 site wiring: the role predicates are pinned by `LifecycleRoleTruthTableTest`; these tests pin that the readers
/// whose variant is not exercised elsewhere still use the right one (the spec's named L-1 mutants).
class LifecycleRoleWiringTest {
    private LifecycleRig rig;

    @AfterEach
    void tearDown() {
        if (rig != null) {
            rig.stop();
        }
    }

    /// Variant B at `adoptionCandidates`: a node outside the electorate that never declared itself an observer
    /// (`REMOVED_UNDECLARED`) still needs a response MAJORITY; its own state cannot supply the missing member. Swapping
    /// in variant A (`observerMode` alone) would let two answers adopt and restore.
    @Test
    void adoption_byARemovedUndeclaredNode_needsAResponseMajority() {
        rig = LifecycleRig.fiveNodeRig();
        rig.engine.initializeVoters(new VoterConfiguration(0, new ClusterConfig(List.of(LifecycleRig.N2, LifecycleRig.N3, LifecycleRig.N4))));
        rig.started();
        assertThat(rig.engine.role()).isEqualTo(RabiaEngine.Role.REMOVED_UNDECLARED);
        rig.active();

        rig.answer(LifecycleRig.N2, 10, "peer".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        rig.settle();

        assertThat(rig.machine.restores.get()).as("one answer of a three-voter roster is not a majority for a node outside the electorate").isZero();
        rig.answer(LifecycleRig.N3, 10, "peer".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        rig.settle();
        assertThat(rig.machine.restores.get()).as("control: a majority of two answers adopts").isGreaterThanOrEqualTo(1);
    }

    /// `PRE_GENESIS_OBSERVER` at `activate()` (variant A passes, so it would activate as an observer): the engine never gets
    /// there while genesis is pending, because no sync answer is eligible without a resolved voter roster. Pinned so the
    /// day that changes is a visible edit; the spec's "pre-genesis observer behaves as PRE_GENESIS" mutant at `activate()`
    /// is therefore unreachable through the public API.
    @Test
    void preGenesisObserver_withSyncAnswers_staysSyncingUntilGenesisResolves() {
        rig = LifecycleRig.genesisPendingRig();
        rig.engine.authorizeObservation();
        rig.engine.clusterState(org.pragmatica.consensus.topology.ClusterStateNotification.active());
        rig.settle();
        rig.snapshotAnswers(10);

        assertThat(rig.engine.role()).isEqualTo(RabiaEngine.Role.PRE_GENESIS_OBSERVER);
        assertThat(rig.mode()).isEqualTo("Syncing");
        assertThat(rig.machine.restores.get()).isZero();
    }
}
