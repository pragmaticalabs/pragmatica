// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.aether.deployment.generation.BootstrapModule;
import org.pragmatica.aether.node.assembly.ClusterBootstrapAssembly;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterIncarnationKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterIncarnationValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/// #2654: the incarnation-commit hook re-drives the bootstrap module through [BootstrapModule#onIncarnationCommitted],
/// which re-opens its attempt budget, so the core record minted provisionally while the life's incarnation read
/// `ClusterIncarnation.NONE` is rewritten under the committed one. On a fresh cluster the restore decision commits before
/// genesis, so this hook is the re-drive that rewrites it.
class IncarnationCommitHookTest {
    private final BootstrapModule bootstrapModule = mock(BootstrapModule.class);

    @Test
    void onIncarnationCommit_reDrivesTheBootstrapModule() {
        var incarnation = ObservedIncarnation.observedIncarnation(ClusterIncarnation.NONE);

        ClusterBootstrapAssembly.onIncarnationCommit(genesisPut(), incarnation, bootstrapModule);

        verify(bootstrapModule).onIncarnationCommitted();
    }

    /// Pins that the hook reads the put's value: the re-drive must already see the committed incarnation, even when
    /// the router delivers this hook BEFORE the mirror's own route. Red if the hook re-drives before applying the put,
    /// or stops applying it.
    @Test
    void onIncarnationCommit_reDriveSeesTheCommittedIncarnation_beforeTheMirrorRouteRan() {
        var incarnation = ObservedIncarnation.observedIncarnation(ClusterIncarnation.NONE);
        var seenAtReDrive = new AtomicLong(-1L);

        doAnswer(_ -> recordCurrent(seenAtReDrive, incarnation)).when(bootstrapModule)
                                                               .onIncarnationCommitted();

        ClusterBootstrapAssembly.onIncarnationCommit(genesisPut(), incarnation, bootstrapModule);

        assertThat(seenAtReDrive.get()).as("the incarnation the re-driven mint reads")
                                       .isEqualTo(ClusterIncarnationValue.GENESIS);
    }

    /// H1 (v2691): the incarnation-commit route is registered on the bootstrap KV router, and the re-drive it triggers
    /// reads the committed incarnation through the SAME mirror the module's epoch supplier mints from. Drives a genesis
    /// put through the router `bootstrapRouting` installs, not through the static hook. Red if the route is not
    /// registered (no re-drive), or if the route is handed a mirror other than `EpochSources.incarnation()` (the
    /// re-drive still mints at NONE).
    @Test
    void healthKvRouter_routesTheIncarnationCommit_toAReDriveThatMintsAtTheCommittedIncarnation() {
        var incarnation = ObservedIncarnation.observedIncarnation(ClusterIncarnation.NONE);
        var epochSources = new AetherNode.EpochSources(incarnation,
                                                       new AtomicLong(),
                                                       AetherNode.generationEpoch(incarnation, () -> 1L),
                                                       AetherNode.generationEpoch(incarnation, () -> 1L));
        var mintedAtReDrive = new AtomicLong(-1L);
        doAnswer(_ -> recordMinted(mintedAtReDrive, epochSources)).when(bootstrapModule)
                                                                  .onIncarnationCommitted();
        var router = ClusterBootstrapAssembly.healthKvRouter(routingInputs(epochSources));

        router.asRouteEntries()
              .stream()
              .filter(entry -> entry.type() == ValuePut.class)
              .forEach(entry -> entry.asRouter()
                                     .unwrap()
                                     .route(genesisPut()));

        verify(bootstrapModule, times(1)).onIncarnationCommitted();
        assertThat(mintedAtReDrive.get()).as("the incarnation the module's generation epoch carries at the re-drive")
                                         .isEqualTo(ClusterIncarnationValue.GENESIS);
    }

    private static Object recordMinted(AtomicLong sink, AetherNode.EpochSources epochSources) {
        sink.set(epochSources.generationEpoch()
                             .get()
                             .incarnation());
        return null;
    }

    /// Only the bootstrap module and the epoch sources are read by the route under test; the other routes capture
    /// their inputs without calling them, and no other key is put here.
    private ClusterBootstrapAssembly.BootstrapRoutingInputs routingInputs(AetherNode.EpochSources epochSources) {
        return new ClusterBootstrapAssembly.BootstrapRoutingInputs(null,
                                                                   null,
                                                                   null,
                                                                   null,
                                                                   null,
                                                                   null,
                                                                   null,
                                                                   null,
                                                                   null,
                                                                   null,
                                                                   null,
                                                                   null,
                                                                   new ArrayList<MessageRouter.Entry<?>>(),
                                                                   null,
                                                                   bootstrapModule,
                                                                   epochSources,
                                                                   null);
    }

    private static Object recordCurrent(AtomicLong sink, ObservedIncarnation incarnation) {
        sink.set(incarnation.current());

        return null;
    }

    private static ValuePut<ClusterIncarnationKey, ClusterIncarnationValue> genesisPut() {
        return new ValuePut<>(new KVCommand.Put<>(ClusterIncarnationKey.clusterIncarnationKey(),
                                                  ClusterIncarnationValue.genesis("lineage-1", "incarnation-1")),
                              Option.none());
    }
}
