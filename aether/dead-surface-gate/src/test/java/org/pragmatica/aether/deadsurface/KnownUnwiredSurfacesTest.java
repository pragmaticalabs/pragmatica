// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.deadsurface;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import org.pragmatica.aether.cli.cluster.ApplyOrchestrator;
import org.pragmatica.aether.cli.cluster.ApplyRecorder;
import org.pragmatica.aether.cli.cluster.WaveExecutor;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.DiffPlan;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn4;
import org.pragmatica.lang.Option;
import org.pragmatica.storage.AnchorRetention;
import org.pragmatica.storage.AppendLog;
import org.pragmatica.storage.MetadataStore;
import org.pragmatica.storage.StorageInstance;
import org.pragmatica.storage.WritePolicy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


/// Production surfaces that are KNOWN to be unwired, registered on purpose with the ticket that
/// owns the decision, so that neither a silent wire nor a silent deletion can happen: wiring one
/// reddens the `assertFalse` (the caller appears in the corpus); deleting one reddens at compile.
/// Each entry says why it is left in place and what would retire it. ENABLED — unlike
/// [DeadSurfaceCommissioningTest] this is a gate, not commissioning evidence.
class KnownUnwiredSurfacesTest {
    private static final List<Path> PRODUCTION_ROOTS = ReactorRoots.productionRoots();

    /// #686 (owner ruling 2026-09-30, option (a)): plain `aether cluster apply` reaches the wave rollout. The entry that registered
    /// `ApplyOrchestrator.apply` as unwired is RETIRED: this is now the control that the default dispatch still reaches
    /// the orchestrator and, through it, the waves. If either flips, `ClusterApplyCommand` stopped calling the orchestrator
    /// and the default apply is back to a server-side scale-only POST.
    @Test
    void clusterApply_freshApplyOrchestrator_isCalledFromProduction() throws Exception {
        assertCorpusComplete();
        var reachability = BytecodeReachability.scan(PRODUCTION_ROOTS);
        var freshApply = MethodRef.of(ApplyOrchestrator.class.getDeclaredMethod("apply",
                                                                                ClusterBootstrapConfig.class,
                                                                                ClusterBootstrapConfig.class,
                                                                                boolean.class,
                                                                                long.class,
                                                                                Fn1.class,
                                                                                Fn4.class));
        var waves = MethodRef.of(WaveExecutor.class.getDeclaredMethod("execute",
                                                                      DiffPlan.class,
                                                                      ClusterBootstrapConfig.class,
                                                                      ClusterBootstrapConfig.class,
                                                                      ApplyRecorder.class));

        assertTrue(reachability.isReachable(freshApply),
                   "#686: ApplyOrchestrator.apply is no longer called from production: the default `aether cluster apply` "
                  + "does not reach the wave rollout (ruling (a) requires it to).");
        assertTrue(reachability.isReachable(waves),
                   "#686: WaveExecutor.execute is not reachable from production (via apply or --resume).");
    }

    /// Reachability control for the read-hit listener overload of `StorageInstance.storageInstance` (the one that also
    /// takes the instance's maximum block size and anchor retention, which `StorageFactory` passes from `max_block_bytes`
    /// and `anchor_retention`): `StorageFactory`
    /// builds every production instance through it, so the scanner must reach it. It was registered here as unwired until
    /// the prefetch wiring landed; if this flips, the scanner or the wiring changed.
    ///
    /// `StorageInstance.warm` was registered beside it, and is NOT asserted here any more: its production caller is the
    /// prefetch manager in `integrations/storage`, which this gate's corpus (aether modules only) does not scan, so a
    /// reachability check on `warm` would answer "no" whether or not the call exists. The call is pinned where the manager
    /// is tested: `PrefetchManagerTest#processHints_blockOnlyInTheSharedTier_copiesItIntoTheCacheTier` goes red when the
    /// manager stops calling `warm`.
    @Test
    void storageReadHitListener_isCalledFromProduction() throws Exception {
        assertCorpusComplete();
        var reachability = BytecodeReachability.scan(PRODUCTION_ROOTS);
        var listenerOverload = MethodRef.of(StorageInstance.class.getDeclaredMethod("storageInstance",
                                                                                    String.class,
                                                                                    List.class,
                                                                                    MetadataStore.class,
                                                                                    WritePolicy.class,
                                                                                    Option.class,
                                                                                    AppendLog.TornTailSink.class,
                                                                                    Consumer.class,
                                                                                    int.class,
                                                                                    AnchorRetention.class));

        assertTrue(reachability.isReachable(listenerOverload),
                   "The read-hit listener overload is no longer reached from production: StorageFactory stopped using it.");
    }

    private static void assertCorpusComplete() {
        var missing = ReactorRoots.missingProductionOutput();

        assertTrue(missing.isEmpty(),
                   "Corpus incomplete: module(s) with src/main/java but no target/classes: " + missing
                  + ". Run a full reactor build before trusting this gate.");
    }
}
