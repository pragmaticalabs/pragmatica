// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.pragmatica.aether.forge.EquivalenceComparison.Arm;
import org.pragmatica.aether.forge.EquivalenceComparison.Divergence;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;

/// The orchestration of one F4 round with the arms passed in (f4fixE: `round()` was reachable only by a live `@Tag("Heavy")` round, so six
/// wiring mutants survived offline, among them a hard-coded PASS). Order of work: discard the earlier verdict file, run the arms (LEGACY
/// first on an even round), assemble the findings, log them, write the report. The live round test and the offline stub-arm tests call this same
/// function; only the arms differ.
///
/// An arm or the verdict assembly that throws is logged as ERROR, the report is written, and the exception is rethrown ([EquivalenceVerdict#guarded]).
final class EquivalenceRound {
    /// What the arms record as they run: the oracle results, and the per-engine control outcomes and placements that [EquivalenceRoundFindings] reads.
    record Recording(List<EquivalenceOracle.Verdict> oracle,
                     Map<DeploymentEngine, Boolean> o4Controls,
                     Map<DeploymentEngine, Boolean> monitorLive,
                     Map<DeploymentEngine, Boolean> o6Live,
                     Map<DeploymentEngine, String> placements) {
        static Recording recording() {
            return new Recording(new ArrayList<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>());
        }
    }

    /// Runs one arm of `engine`, recording into `recording`; `firstBase` is the first port block it may try.
    interface ArmRun {
        Arm run(DeploymentEngine engine, int firstBase, Recording recording);
    }

    static final int FIRST_BASE = 33000;

    private final String scenario;
    private final int round;
    private final Path file;
    private final EquivalenceMonitor monitor;
    private final List<String> report;
    private final Consumer<String> log;

    private EquivalenceRound(String scenario, int round, Path file, EquivalenceMonitor monitor, List<String> report, Consumer<String> log) {
        this.scenario = scenario;
        this.round = round;
        this.file = file;
        this.monitor = monitor;
        this.report = report;
        this.log = log;
    }

    /// `log` must append to `report`: the file written is `report`.
    static EquivalenceRound equivalenceRound(String scenario, int round, Path dir, EquivalenceMonitor monitor, List<String> report, Consumer<String> log) {
        return new EquivalenceRound(scenario, round, EquivalenceVerdict.reportFile(dir, scenario, round), monitor, report, log);
    }

    EquivalenceRoundFindings run(String settings, ArmRun armRun) {
        var engines = round % 2 == 0 ? List.of(DeploymentEngine.LEGACY, DeploymentEngine.FSM2) : List.of(DeploymentEngine.FSM2, DeploymentEngine.LEGACY);
        var arms = new HashMap<DeploymentEngine, Arm>();
        var recording = Recording.recording();

        EquivalenceVerdict.discardStale(file);
        log.accept("F4 round scenario=%s round=%d order=%s %s".formatted(scenario, round, engines, settings));
        for (var index = 0; index < engines.size(); index++) {
            var engine = engines.get(index);
            var firstBase = FIRST_BASE + EquivalencePorts.STEP * index;

            arms.put(engine, EquivalenceVerdict.guarded(() -> armRun.run(engine, firstBase, recording), error -> armError(engine, error)));
        }
        var legacy = arms.get(DeploymentEngine.LEGACY);
        var fsm2 = arms.get(DeploymentEngine.FSM2);
        var findings = EquivalenceVerdict.guarded(() -> EquivalenceRoundFindings.assemble(legacy,
                                                                                          fsm2,
                                                                                          recording.o4Controls(),
                                                                                          recording.monitorLive(),
                                                                                          recording.o6Live(),
                                                                                          recording.oracle(),
                                                                                          recording.placements(),
                                                                                          monitor,
                                                                                          scenario),
                                                  this::verdictError);

        logFindings(legacy, fsm2, recording, findings);
        EquivalenceVerdict.writeReport(file, report);
        return findings;
    }

    private void logFindings(Arm legacy, Arm fsm2, Recording recording, EquivalenceRoundFindings findings) {
        log.accept("arm legacy: o4=%d o5=%d ms o6=%s samples=%d".formatted(legacy.o4Batches(), legacy.o5PlacementMs(), legacy.o6(), legacy.samples().size()));
        log.accept("arm fsm2:   o4=%d o5=%d ms o6=%s samples=%d".formatted(fsm2.o4Batches(), fsm2.o5PlacementMs(), fsm2.o6(), fsm2.samples().size()));
        recording.oracle().forEach(result -> log.accept(result.describe()));
        log.accept("control divergences: " + findings.controlDivergences().stream().map(Divergence::describe).toList());
        log.accept("silent controls: " + findings.silent());
        log.accept("not measured (compared zero items, no control credited): " + findings.notMeasured());
        log.accept("placement legacy=%s fsm2=%s; void: %s".formatted(recording.placements().get(DeploymentEngine.LEGACY),
                                                                       recording.placements().get(DeploymentEngine.FSM2),
                                                                       findings.voids()));
        findings.divergences().forEach(divergence -> log.accept("DIVERGENCE " + divergence));
        log.accept("VERDICT " + findings.verdict());
    }

    /// An arm threw: the ERROR line, no verdict claimed, the report written (the exception itself is rethrown by [EquivalenceVerdict#guarded]).
    private void armError(DeploymentEngine engine, Throwable error) {
        log.accept("arm %s ERROR %s".formatted(engine, error));
        log.accept("VERDICT ERROR");
        EquivalenceVerdict.writeReport(file, report);
    }

    /// The verdict contradicted the round's own findings: the ERROR line, the report written (the exception is rethrown by [EquivalenceVerdict#guarded]).
    private void verdictError(Throwable error) {
        log.accept("verdict ERROR %s".formatted(error));
        log.accept("VERDICT ERROR");
        EquivalenceVerdict.writeReport(file, report);
    }
}
