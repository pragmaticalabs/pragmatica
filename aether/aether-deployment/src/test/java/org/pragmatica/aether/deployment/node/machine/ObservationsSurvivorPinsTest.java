// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.ARTIFACT;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.ARTIFACT_B;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.G1;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.SELF;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.SELF_TOKEN;

/// Pins for `Observations`. Each test names the mutation that must turn it red.
class ObservationsSurvivorPinsTest {
    private static Transition ownPut(NodeHarness h, InstanceRecord rec, SliceObservedState state, long version) {
        var model = h.model.withInstance(ARTIFACT, rec);

        return h.fsm.step(model, new NodeFsmInput.ObservedPut(SELF, ARTIFACT, SurvivorRows.row(h, state, version, G1), SELF_TOKEN));
    }

    /// Red when: `known.version() > value.version()` becomes `>=` in `supersededBy` (machine-87): a different row at the mirror's
    /// own version would be ignored as "no newer".
    @Test
    @DisplayName("NI-3: a different row at the mirror's own version is taken, not ignored")
    void ownObservedPut_mirrorsADifferentRow_atTheSameVersion() {
        var h = new NodeHarness().establish();
        var rec = InstanceRecord.instanceRecord()
                                .withObserved(Option.some(SurvivorRows.row(h, SliceObservedState.LOADING, 2L, G1)))
                                .withLastVersion(2L);
        var step = ownPut(h, rec, SliceObservedState.LOADED, 2L);

        assertThat(step.dropped()).isFalse();
        assertThat(step.model().instance(ARTIFACT).observed().map(row -> row.state())).isEqualTo(Option.some(SliceObservedState.LOADED));
    }

    /// Red when: `unconfirmed.version() > value.version()` becomes `>=` in `mirrored` (machine-73): the owed write whose
    /// version is the one now committed would stay owed.
    @Test
    @DisplayName("NI-3: the committed row at the owed write's version clears the owed write; a later owed write stays")
    void ownObservedPut_clearsTheOwedWrite_atItsOwnVersionOnly() {
        var h = new NodeHarness().establish();
        var owedHere = InstanceRecord.instanceRecord()
                                     .withObserved(Option.some(SurvivorRows.row(h, SliceObservedState.LOADING, 2L, G1)))
                                     .withLastVersion(2L)
                                     .withUnconfirmed(Option.some(Unconfirmed.unconfirmed(SliceObservedState.LOADED, 3L, Option.none())));
        var owedLater = owedHere.withUnconfirmed(Option.some(Unconfirmed.unconfirmed(SliceObservedState.ACTIVE, 4L, Option.none())));

        assertThat(ownPut(h, owedHere, SliceObservedState.LOADED, 3L).model().instance(ARTIFACT).unconfirmed().isPresent()).as("committed at version 3 settles the write owed at 3").isFalse();
        assertThat(ownPut(h, owedLater, SliceObservedState.LOADED, 3L).model().instance(ARTIFACT).unconfirmed().isPresent()).as("version 3 does not settle the write owed at 4").isTrue();
    }

    /// Red when: `base.matches(artifact) && parked` becomes `||` in `isParkedFor` (machine-64): a committed target for a base
    /// would re-evaluate an unparked pair of that base.
    @Test
    @DisplayName("NI-4: a committed target re-drives only what is parked for its base")
    void targetPut_redrivesOnlyParkedStarts_ofTheBase() {
        var h = new NodeHarness().establish();
        var idleWithGoal = InstanceRecord.instanceRecord().withDesired(Option.some(new SliceDesiredValue(SliceGoal.ACTIVE, G1, h.wall)));

        h.model = h.model.withInstance(ARTIFACT, idleWithGoal);
        h.goal(ARTIFACT_B, SliceGoal.ACTIVE, G1);

        assertThat(h.record(ARTIFACT_B).parked().isPresent()).as("precondition: no target, the start is parked").isTrue();

        h.permit(ARTIFACT_B);

        assertThat(ran(h, ARTIFACT_B)).as("the parked start of the base is re-driven").isTrue();
        assertThat(ran(h, ARTIFACT)).as("an unparked pair of another base is left alone").isFalse();

        h.permit(ARTIFACT);

        assertThat(ran(h, ARTIFACT)).as("an unparked pair of the same base is left alone too").isFalse();
    }

    private static boolean ran(NodeHarness h, Artifact artifact) {
        return h.localRuns.stream().anyMatch(run -> run.artifact().equals(artifact));
    }
}
