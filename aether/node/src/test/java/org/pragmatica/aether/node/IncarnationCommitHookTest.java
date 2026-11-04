// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.aether.deployment.generation.BootstrapModule;
import org.pragmatica.aether.node.assembly.ClusterBootstrapAssembly;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterIncarnationKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterIncarnationValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/// #2654: the incarnation-commit hook re-drives the bootstrap module, whose core-partition ownership mint was refused
/// while the life's incarnation read `ClusterIncarnation.NONE`. On a fresh cluster the restore decision commits
/// before genesis, so this hook is the only re-drive left after a refusal at leader gain.
class IncarnationCommitHookTest {
    private final BootstrapModule bootstrapModule = mock(BootstrapModule.class);

    @Test
    void onIncarnationCommit_reDrivesTheBootstrapModule() {
        var incarnation = ObservedIncarnation.observedIncarnation(ClusterIncarnation.NONE);

        ClusterBootstrapAssembly.onIncarnationCommit(genesisPut(), incarnation, bootstrapModule);

        verify(bootstrapModule).retryIfNeeded();
    }

    /// Pins that the hook reads the put's value: the re-drive must already see the committed incarnation, even when
    /// the router delivers this hook BEFORE the mirror's own route. Red if the hook re-drives before applying the put,
    /// or stops applying it.
    @Test
    void onIncarnationCommit_reDriveSeesTheCommittedIncarnation_beforeTheMirrorRouteRan() {
        var incarnation = ObservedIncarnation.observedIncarnation(ClusterIncarnation.NONE);
        var seenAtReDrive = new AtomicLong(-1L);

        doAnswer(_ -> recordCurrent(seenAtReDrive, incarnation)).when(bootstrapModule)
                                                               .retryIfNeeded();

        ClusterBootstrapAssembly.onIncarnationCommit(genesisPut(), incarnation, bootstrapModule);

        assertThat(seenAtReDrive.get()).as("the incarnation the re-driven mint reads")
                                       .isEqualTo(ClusterIncarnationValue.GENESIS);
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
