// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.deadsurface;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.Set;
import java.util.stream.Stream;

import org.pragmatica.aether.cli.cluster.ApplyOrchestrator;
import org.pragmatica.aether.cli.cluster.ApplyRecorder;
import org.pragmatica.aether.cli.cluster.WaveExecutor;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.DiffPlan;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.storage.AnchorRetention;
import org.pragmatica.storage.AppendLog;
import org.pragmatica.storage.MetadataStore;
import org.pragmatica.storage.StorageInstance;
import org.pragmatica.storage.WritePolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    /// actuation authority over the same fleet — entered only via `--resume` (`--rollback` is refused as not implemented), and
    /// evidenced only by their own unit tests. They stay unwired until the server-side wave design
    /// (the ticket drafted in the #686 fix report) lands; wiring plain `apply` to them is the change
    /// that ruling refused.
    ///
    /// EVERY `apply` overload is registered, not just the one a caller is expected to reach for.
    /// [BytecodeReachability] records the descriptor the call site actually names, and the overloads
    /// delegate to each other INSIDE the declaring class — where edges are deliberately not recorded.
    /// So registering `apply(desired, stored, skipConfirmation)` alone leaves
    /// `apply(desired, stored)` a silent door to the same rollout: wiring it reddens nothing.
    /// [#declaredApplyOverloads] keeps the hand-written list below honest when an overload is ADDED.
    @Test
    void clusterApply_freshApplyOrchestrator_isNotCalledFromProduction() throws Exception {
        assertCorpusComplete();
        var reachability = BytecodeReachability.scan(PRODUCTION_ROOTS);
        var freshApply = MethodRef.of(ApplyOrchestrator.class.getDeclaredMethod("apply",
                                                                                ClusterBootstrapConfig.class,
                                                                                ClusterBootstrapConfig.class,
                                                                                boolean.class));
        var defaultedApply = MethodRef.of(ApplyOrchestrator.class.getDeclaredMethod("apply",
                                                                                    ClusterBootstrapConfig.class,
                                                                                    ClusterBootstrapConfig.class));
        var waves = MethodRef.of(WaveExecutor.class.getDeclaredMethod("execute",
                                                                      DiffPlan.class,
                                                                      ClusterBootstrapConfig.class,
                                                                      ClusterBootstrapConfig.class,
                                                                      ApplyRecorder.class));

        assertEquals(Set.of(freshApply, defaultedApply),
                     declaredApplyOverloads(),
                     "#686: the set of ApplyOrchestrator.apply overloads has changed. Each overload is a separate door "
                    + "to the same client-side wave rollout and the scan matches on descriptor, so any overload missing "
                    + "from the assertions below can be wired into production without reddening anything. Register the "
                    + "new one, or retire the entry with the server-side wave design.");
        assertFalse(reachability.isReachable(freshApply),
                    "#686: ApplyOrchestrator.apply(desired, stored, skipConfirmation) has been wired into production. "
                   + "The ruling is (b): plain apply stays scale-only via the leader; a client-side wave rollout is a "
                   + "second actuation authority. Retire this entry only with the server-side wave design.");
        assertFalse(reachability.isReachable(defaultedApply),
                    "#686: ApplyOrchestrator.apply(desired, stored) has been wired into production. This overload only "
                   + "defaults skipConfirmation and delegates to the three-argument one, so it reaches the same "
                   + "client-side wave rollout the ruling refused. Retire this entry only with the server-side wave design.");
        assertTrue(reachability.isReachable(waves),
                   "control: WaveExecutor.execute IS reachable — through ApplyOrchestrator.resume (--resume), "
                  + "the documented recovery path; if this flips, the scanner or the CLI changed, not the ruling");
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

    /// Every `apply` overload declared on [ApplyOrchestrator], by bytecode identity. The assertions
    /// above name their overloads explicitly so that DELETING one fails to compile; this set is what
    /// makes that hand-written list fail when one is ADDED.
    private static Set<MethodRef> declaredApplyOverloads() {
        return Set.copyOf(Stream.of(ApplyOrchestrator.class.getDeclaredMethods())
                                .filter(method -> "apply".equals(method.getName()))
                                .map(MethodRef::of)
                                .toList());
    }

    private static void assertCorpusComplete() {
        var missing = ReactorRoots.missingProductionOutput();

        assertTrue(missing.isEmpty(),
                   "Corpus incomplete: module(s) with src/main/java but no target/classes: " + missing
                  + ". Run a full reactor build before trusting this gate.");
    }
}
