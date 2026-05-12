// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.health;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhaseValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.swim.SwimObservation;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;


public interface HealthReconciler {
    Promise<Unit> start();
    Promise<Unit> stop();
    @Contract void onSwimObservation(SwimObservation observation);
    Promise<Unit> requestDrain(NodeId target);
    Promise<Unit> requestDecommission(NodeId target);
    Promise<Unit> requestActivate(NodeId target);
    Promise<Unit> requestFailedDrain(NodeId target);
    ClusterPhase phase();
    @Contract void addPhaseListener(Consumer<ClusterPhaseChanged> listener);
    @Contract void onClusterPhasePut(ClusterPhaseValue value);

    @FunctionalInterface interface RetryScheduler {
        @Contract void schedule(Runnable runnable, TimeSpan delay);
    }

    static RetryScheduler defaultRetryScheduler() {
        return SharedScheduler::schedule;
    }

    /// E.6 / spec §7.2: phase-write gate. When the gate returns `true`, this reconciler
    /// continues to write `ClusterPhaseKey` to KV on phase transitions (legacy behaviour,
    /// flag off). When the gate returns `false`, phase writes are suppressed because
    /// `ClusterPhaseView` is now the source of truth (flag on). Default = writes enabled.
    static BooleanSupplier defaultPhaseWritesEnabled() {
        return () -> true;
    }

    static HealthReconciler healthReconciler(NodeId self,
                                             int expectedClusterSize,
                                             Function<NodeId, Option<NodeLifecycleValue>> lifecycleReader,
                                             Supplier<Option<ClusterPhase>> phaseReader,
                                             Supplier<Option<NodeId>> leaderReader,
                                             Supplier<Integer> onDutyCountSupplier,
                                             Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier,
                                             HealthReconcilerConfig config) {
        return healthReconciler(self,
                                expectedClusterSize,
                                lifecycleReader,
                                phaseReader,
                                leaderReader,
                                onDutyCountSupplier,
                                commandApplier,
                                config,
                                defaultRetryScheduler());
    }

    static HealthReconciler healthReconciler(NodeId self,
                                             int expectedClusterSize,
                                             Function<NodeId, Option<NodeLifecycleValue>> lifecycleReader,
                                             Supplier<Option<ClusterPhase>> phaseReader,
                                             Supplier<Option<NodeId>> leaderReader,
                                             Supplier<Integer> onDutyCountSupplier,
                                             Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier,
                                             HealthReconcilerConfig config,
                                             RetryScheduler retryScheduler) {
        return healthReconciler(self,
                                expectedClusterSize,
                                lifecycleReader,
                                phaseReader,
                                leaderReader,
                                onDutyCountSupplier,
                                commandApplier,
                                config,
                                retryScheduler,
                                defaultPhaseWritesEnabled());
    }

    static HealthReconciler healthReconciler(NodeId self,
                                             int expectedClusterSize,
                                             Function<NodeId, Option<NodeLifecycleValue>> lifecycleReader,
                                             Supplier<Option<ClusterPhase>> phaseReader,
                                             Supplier<Option<NodeId>> leaderReader,
                                             Supplier<Integer> onDutyCountSupplier,
                                             Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier,
                                             HealthReconcilerConfig config,
                                             RetryScheduler retryScheduler,
                                             BooleanSupplier phaseWritesEnabled) {
        return HealthReconcilerImpl.healthReconcilerImpl(self,
                                                         expectedClusterSize,
                                                         lifecycleReader,
                                                         phaseReader,
                                                         leaderReader,
                                                         onDutyCountSupplier,
                                                         commandApplier,
                                                         config,
                                                         retryScheduler,
                                                         phaseWritesEnabled);
    }
}
