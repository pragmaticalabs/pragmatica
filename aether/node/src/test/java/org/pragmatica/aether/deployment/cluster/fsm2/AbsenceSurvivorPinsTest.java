// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Set;

import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;


/// Survivor pins for [Absence] (F1 closure, lane fsmf1): each test names the line whose mutation it holds.
class AbsenceSurvivorPinsTest {
    private static final long TOKEN_2 = 0x2222L;

    /// Red when: the class labels of a core and of a worker are swapped (`labelOf`: `==` weakened to `!=`); the label names the class in the
    /// unjudgeable warning and tags the inferred-departure metric.
    @Test
    void labelOf_namesCoreAndWorkerClasses() {
        assertThat(Absence.labelOf(Absence.NodeClass.CORE)).isEqualTo("core");
        assertThat(Absence.labelOf(Absence.NodeClass.ASSIGNED_WORKER)).isEqualTo("worker");
        assertThat(Absence.labelOf(Absence.NodeClass.UNASSIGNED_WORKER)).isEqualTo("worker");
    }

    @Test
    void unjudgeableCore_warningNamesTheCoreClass() {
        var w = MbWorld.world(CORE_1, CORE_2);

        w.admit(CORE_2, TOKEN_2);
        w.directive(CORE_2, ActivationDirectiveValue.core()).activate();
        w.h.advance(3 * ms(bounds().coreRemoval()));

        assertThat(w.h.warns("worker-absence-unjudgeable")).singleElement()
                  .satisfies(warn -> assertThat(warn.subject()).isEqualTo("core"));
    }

    private static Absence.Verdict verdictOf(NodeId node, Set<NodeId> rostered, Set<NodeId> alive) {
        var w = MbWorld.world(CORE_1).activate();
        var m = w.h.model();

        for (var member : rostered) {
            m.rosterCount = m.rosterCount.put(member, 1);
        }

        m.rosterCount = m.rosterCount.put(node("roster-anchor"), 1);
        m.seams = m.seams.withLocalAlive(Option.some(alive));

        return Absence.verdict(m, new Ctx(w.h.settings, w.h.wallMs, w.h.monoNs), node);
    }

    /// Red when: an unassigned worker needs both signals to be present (`rosterCount.containsKey || locallyAlive` weakened to `&&`): each
    /// of the two alone keeps it present (B-017).
    @Test
    void unassignedWorker_isPresentOnEitherSignal_absentOnNeither() {
        var n = node("u1");

        assertThat(verdictOf(n, Set.of(n), Set.of(CORE_1))).as("in a roster only").isEqualTo(Absence.Verdict.PRESENT);
        assertThat(verdictOf(n, Set.of(), Set.of(n))).as("locally alive only").isEqualTo(Absence.Verdict.PRESENT);
        assertThat(verdictOf(n, Set.of(), Set.of(CORE_1))).as("neither").isEqualTo(Absence.Verdict.ABSENT);
    }
}
