// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deadsurface;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.cli.cluster.ApplyOrchestrator;
import org.pragmatica.aether.cli.cluster.WaveExecutor;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.DiffPlan;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Production surfaces that are KNOWN to be unwired, registered on purpose with the ticket that
/// owns the decision, so that neither a silent wire nor a silent deletion can happen: wiring one
/// reddens the `assertFalse` (the caller appears in the corpus); deleting one reddens at compile.
/// Each entry says why it is left in place and what would retire it. ENABLED — unlike
/// [DeadSurfaceCommissioningTest] this is a gate, not commissioning evidence.
class KnownUnwiredSurfacesTest {
    private static final List<Path> PRODUCTION_ROOTS = ReactorRoots.productionRoots();

    /// #686 (CTO ruling 2026-09-14, option (b)): plain `aether cluster apply` is scale-only, actuated by
    /// the LEADER from KV desired counts. `ApplyOrchestrator.apply` / `WaveExecutor.execute` are a
    /// complete client-side wave rollout that actuates through the CLI's `ComputeProvider` — a second
    /// actuation authority over the same fleet — reachable only via `--resume`/`--rollback` after a
    /// halt, and evidenced only by their own unit tests. They stay unwired until the server-side
    /// wave design (the ticket drafted in the #686 fix report) lands; wiring plain `apply` to them
    /// is the change that ruling refused.
    @Test
    void clusterApply_freshApplyOrchestrator_isNotCalledFromProduction() throws Exception {
        assertCorpusComplete();
        var reachability = BytecodeReachability.scan(PRODUCTION_ROOTS);
        var freshApply = MethodRef.of(ApplyOrchestrator.class.getDeclaredMethod("apply",
                                                                                ClusterBootstrapConfig.class,
                                                                                ClusterBootstrapConfig.class,
                                                                                boolean.class));
        var waves = MethodRef.of(WaveExecutor.class.getDeclaredMethod("execute",
                                                                      DiffPlan.class,
                                                                      ClusterBootstrapConfig.class,
                                                                      ClusterBootstrapConfig.class));

        assertFalse(reachability.isReachable(freshApply),
                    "#686: ApplyOrchestrator.apply(desired, stored, skipConfirmation) has been wired into production. "
                    + "The ruling is (b): plain apply stays scale-only via the leader; a client-side wave rollout is a "
                    + "second actuation authority. Retire this entry only with the server-side wave design.");
        assertTrue(reachability.isReachable(waves),
                   "control: WaveExecutor.execute IS reachable — through ApplyOrchestrator.resume/rollback (--resume/--rollback), "
                   + "the documented recovery path; if this flips, the scanner or the CLI changed, not the ruling");
    }

    private static void assertCorpusComplete() {
        var missing = ReactorRoots.missingProductionOutput();

        assertTrue(missing.isEmpty(),
                   "Corpus incomplete: module(s) with src/main/java but no target/classes: " + missing
                   + ". Run a full reactor build before trusting this gate.");
    }
}
