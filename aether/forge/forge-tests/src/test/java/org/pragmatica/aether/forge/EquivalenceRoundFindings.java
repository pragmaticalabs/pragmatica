// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.pragmatica.aether.forge.EquivalenceComparison.Arm;
import org.pragmatica.aether.forge.EquivalenceComparison.Divergence;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;

/// The findings of one F4 round as a pure function of what the round recorded (the arms, the per-engine control results, the oracle results,
/// the placements), extracted from the round test so that every line of the assembly is unit-tested (vf4cfable: R07-R19, 13 surviving mutants,
/// 7 of them fail-open, because only a live round reached them).
///
/// Every per-engine control map must carry one entry per engine it is required for: an absent entry is a silent control, never "nothing to
/// report". The O6 live probe is FSM2-only (LEGACY's counters are 0 by construction); the O4 scripted batch and the monitor live plant run in both arms.
record EquivalenceRoundFindings(List<String> silent,
                                List<String> voids,
                                List<String> divergences,
                                List<String> notMeasured,
                                List<Divergence> controlDivergences,
                                EquivalenceVerdict verdict) {
    static final List<DeploymentEngine> BOTH_ENGINES = List.of(DeploymentEngine.LEGACY, DeploymentEngine.FSM2);
    static final List<DeploymentEngine> FSM2_ONLY = List.of(DeploymentEngine.FSM2);

    static EquivalenceRoundFindings assemble(Arm legacy,
                                             Arm fsm2,
                                             Map<DeploymentEngine, Boolean> o4Controls,
                                             Map<DeploymentEngine, Boolean> monitorLive,
                                             Map<DeploymentEngine, Boolean> o6Live,
                                             List<EquivalenceOracle.Verdict> oracle,
                                             Map<DeploymentEngine, String> placements,
                                             EquivalenceMonitor monitor,
                                             String scenario) {
        var control = EquivalenceDivergentControl.of(fsm2, legacy, monitor);
        var fsm2Divergences = EquivalenceComparison.compare(legacy, fsm2, monitor, scenario);
        var controlDivergences = EquivalenceComparison.compare(legacy, control, monitor, scenario);
        var notMeasured = EquivalenceComparison.notMeasured(legacy, fsm2);
        var silent = new ArrayList<>(EquivalenceDivergentControl.silentFamilies(fsm2Divergences, controlDivergences, notMeasured));

        silent.addAll(silentEngines("O4-scripted-batch", o4Controls, BOTH_ENGINES));
        silent.addAll(silentEngines("MONITOR-live-plant", monitorLive, BOTH_ENGINES));
        silent.addAll(silentEngines("O6-live-probe", o6Live, FSM2_ONLY));
        silent.addAll(silentOracle(oracle));
        var divergences = new ArrayList<>(fsm2Divergences.stream().map(Divergence::describe).toList());
        var voids = new ArrayList<>(placementVoid(placements));

        voids.addAll(EquivalenceComparison.voids(legacy, fsm2));
        oracle.forEach(result -> result.disagreements().forEach(submit -> divergences.add("ORACLE: " + submit.describe())));
        var verdict = EquivalenceVerdict.wired(EquivalenceVerdict.of(silent, voids, divergences), silent, voids, divergences);

        return new EquivalenceRoundFindings(silent, voids, divergences, notMeasured, controlDivergences, verdict);
    }

    /// One silent entry per required engine whose control is absent or did not fire.
    private static List<String> silentEngines(String label, Map<DeploymentEngine, Boolean> fired, List<DeploymentEngine> required) {
        return required.stream().filter(engine -> !Boolean.TRUE.equals(fired.get(engine))).map(engine -> label + "(" + engine + ")").toList();
    }

    /// The oracle runs over the LEGACY arm: no result at all is as silent as a result whose controls did not fire.
    private static List<String> silentOracle(List<EquivalenceOracle.Verdict> oracle) {
        return oracle.isEmpty()
               ? List.of("ORACLE-controls")
               : oracle.stream().filter(verdict -> !verdict.controlsFired()).map(_ -> "ORACLE-controls").toList();
    }

    /// O4 is compared only at equal placement: arms whose per-artifact node counts differ, or whose placement was never recorded, void the round.
    static List<String> placementVoid(Map<DeploymentEngine, String> placements) {
        var legacy = placements.get(DeploymentEngine.LEGACY);
        var fsm2 = placements.get(DeploymentEngine.FSM2);

        return legacy != null && legacy.equals(fsm2) ? List.of() : List.of("O4-placement-mismatch(legacy=" + legacy + ", fsm2=" + fsm2 + ")");
    }
}
