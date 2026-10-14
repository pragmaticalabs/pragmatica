// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Map;

import org.pragmatica.aether.slice.kvstore.AetherKey.ArtifactJudgmentKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ArtifactJudgmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.UnresponsiveEntry;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;


/// The ways an exclusion (`unresponsive[n]`, R-4.2.5) ends, beyond the answers and the expiry the NT rows cover.
class GapJudgmentTest {
    private static final long OLD_TOKEN = 0x2222L;
    private static final long NEW_TOKEN = 0x9999L;

    private MbWorld excluded() {
        var w = MbWorld.world(CORE_1, CORE_2, CORE_3);

        w.admit(CORE_1, 0x1111L);
        w.admit(CORE_2, OLD_TOKEN);
        w.admit(CORE_3, 0x3333L);
        w.target(ORDERS, 1, "CORE_ONLY").record(CORE_2, DepartureState.ADMITTED, 1L, OLD_TOKEN, null);
        w.h.seedQuietly(new ArtifactJudgmentKey(ORDERS),
                        new ArtifactJudgmentValue(false,
                                                  Map.of(),
                                                  Map.of(CORE_2,
                                                         new UnresponsiveEntry(gen(1),
                                                                               SliceGoal.LOADED,
                                                                               FsmHarness.START_WALL))));

        return w;
    }

    /// Red when: a core re-admitted as a DIFFERENT process (both tokens known, unequal: positive evidence, J8 core arm) stays
    /// excluded from placement for what its predecessor process did not answer.
    @Test
    void admissionOfADifferentProcess_endsTheExclusion() {
        var w = excluded().activate();

        assertThat(w.h.model().judgment(ORDERS).map(judgment -> judgment.unresponsive()
                                                                        .containsKey(CORE_2))).isEqualTo(Option.some(true));
        w.h.deliver(new ClusterInput.NodeJoined(CORE_2, Fixtures.admission(NEW_TOKEN)));
        assertThat(w.h.model().judgment(ORDERS)).as("nothing left to record").isEqualTo(Option.none());
    }

    /// Red when: a same-process re-admission (a partition heal) ends the exclusion: it is for the next answer, expiry or
    /// departure, not for a heal.
    @Test
    void admissionOfTheSameProcess_keepsTheExclusion() {
        var w = excluded().activate();

        w.h.deliver(new ClusterInput.NodeJoined(CORE_2, w.admit(CORE_2, OLD_TOKEN)));
        assertThat(w.h.model().judgment(ORDERS).map(judgment -> judgment.unresponsive()
                                                                        .containsKey(CORE_2))).isEqualTo(Option.some(true));
    }

    /// Red when: a departure that commits does not end the node's exclusions (R-4.2.5: n's departure, recorded by C17 or C17s).
    @Test
    void departure_endsTheExclusion() {
        var w = excluded().activate();

        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));
        assertThat(w.h.submits(ClusterModel.SubmitKind.DEPARTURE)).hasSize(1);
        assertThat(w.h.model().judgment(ORDERS)).isEqualTo(Option.none());
    }
}
