// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.util.List;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.node.AssemblySource;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.OwnerActivation;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2682 (v-2699 F3): pins the wiring that starts the owner-promotion gate when a committed ownership record newly names this
/// node. Before, only Ember arm 4 reached it, and with the route deleted that arm still converged through the backfill path in
/// about half its runs. The route itself is driven here with a committed-record notification; its registration and the cap's
/// are pinned by source text, like `CommitWitnessWiringTest` [unverified: registration pinned by text only].
class StreamAssemblyEagerActivationTest {
    private static final NodeId SELF = new NodeId("self");
    private static final String STREAM = "orders";
    private static final int PARTITION = 3;

    @Test
    void aCommittedRecordNamingThisNode_reachesTheHook_andActivatesWithNoDemand() {
        var owned = StreamPartitionOwnershipValue.streamPartitionOwnershipValue(SELF, Epoch.epoch(0L, 1L, 0), 1L, HlcTimestamp.ZERO);
        var activation = OwnerActivation.ownerActivation(SELF,
                                                         (_, _) -> Option.some(owned),
                                                         (_, _) -> true,
                                                         Option.none(),
                                                         () -> List.of(SELF),
                                                         (_, _, _) -> Promise.success(-1L),
                                                         (_, _) -> -1L,
                                                         (_, _, _, _) -> Promise.success(-1L),
                                                         () -> true,
                                                         (_, _, _, _, _) -> Promise.success(List.of()),
                                                         _ -> Unit.unit(),
                                                         timeSpan(1).hours());
        var put = new ValuePut<>(new KVCommand.Put<>(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(STREAM, PARTITION),
                                                     owned),
                                 Option.<StreamPartitionOwnershipValue> none());

        StreamAssembly.committedOwnershipRoutes(activation)
                      .forEach(entry -> entry.asRouter()
                                             .onSuccess(router -> router.route(put)));

        assertThat(eventually(() -> activation.isActivated(STREAM, PARTITION))).as("the routed record started the gate")
                                                                               .isTrue();
    }

    @Test
    void theRouteIsRegistered_andTheCapIsWired() {
        var code = AssemblySource.stripped();

        assertThat(code).containsOnlyOnce("allEntries.addAll(committedOwnershipRoutes(ownerActivation));");
        assertThat(code).containsOnlyOnce("ownerActivation.eagerConcurrency(streamingConfig.reshuffleConcurrency());");
    }

    private static boolean eventually(BooleanSupplier condition) {
        for (var attempt = 0; attempt < 500 && !condition.getAsBoolean(); attempt++) {
            LockSupport.parkNanos(10_000_000L);
        }

        return condition.getAsBoolean();
    }
}
