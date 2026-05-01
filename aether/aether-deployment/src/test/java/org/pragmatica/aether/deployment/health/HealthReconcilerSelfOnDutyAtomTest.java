// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.health;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhaseValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProvisioningSource;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;


/// R8 — verifies that HealthReconciler's first self ON_DUTY write seeds
/// `host`/`port`/`observedCoreEpoch`/`provisioningSource` via the injected
/// `SelfOnDutyAtomFactory` (ex-NodeDeploymentManager.writeLifecycleOnDuty
/// responsibility).
class HealthReconcilerSelfOnDutyAtomTest {
    private static final NodeId SELF = NodeId.nodeId("node-self").unwrap();

    private RecordingApplier applier;
    private AtomicReference<NodeLifecycleValue> selfPrior;
    private AtomicReference<ClusterPhase> phaseRef;
    private AtomicInteger onDutyCount;

    @BeforeEach
    void setUp() {
        applier = new RecordingApplier();
        selfPrior = new AtomicReference<>(null);
        phaseRef = new AtomicReference<>(ClusterPhase.NORMAL);
        onDutyCount = new AtomicInteger(3);
    }

    @Nested class SelfOnDutyAtomSeeding {
        @Test
        void healthReconciler_signalSelfReady_seedsHostPortEpochSourceFromFactory() {
            var seedEpoch = Epoch.epoch(5L, 17L);
            HealthReconciler.SelfOnDutyAtomFactory factory = (state, nowMs) -> NodeLifecycleValue.nodeLifecycleValue(state,
                                                                                                                     nowMs,
                                                                                                                     "10.0.0.1",
                                                                                                                     9000,
                                                                                                                     seedEpoch,
                                                                                                                     HlcTimestamp.ZERO,
                                                                                                                     ProvisioningSource.MANUAL);
            var reconciler = HealthReconciler.healthReconciler(SELF,
                                                               3,
                                                               nodeId -> nodeId.equals(SELF) ? Option.option(selfPrior.get()) : Option.none(),
                                                               () -> Option.option(phaseRef.get()),
                                                               () -> Option.some(SELF),
                                                               onDutyCount::get,
                                                               applier,
                                                               HealthReconcilerConfig.DEFAULT,
                                                               factory);
            reconciler.start();

            reconciler.signalSelfReady();

            var written = lastSelfWriteValue();
            assertThat(written).isNotNull();
            assertThat(written.state()).isEqualTo(NodeLifecycleState.ON_DUTY);
            assertThat(written.host()).isEqualTo("10.0.0.1");
            assertThat(written.port()).isEqualTo(9000);
            assertThat(written.observedCoreEpoch()).isEqualTo(seedEpoch);
            assertThat(written.provisioningSource()).isEqualTo(ProvisioningSource.MANUAL);
        }

        @Test
        void healthReconciler_signalSelfReady_factoryReadAtWriteTime_capturesLatestEpoch() {
            var ref = new AtomicReference<>(Epoch.ZERO);
            HealthReconciler.SelfOnDutyAtomFactory factory = (state, nowMs) -> NodeLifecycleValue.nodeLifecycleValue(state,
                                                                                                                     nowMs,
                                                                                                                     "10.0.0.1",
                                                                                                                     9000,
                                                                                                                     ref.get(),
                                                                                                                     HlcTimestamp.ZERO,
                                                                                                                     ProvisioningSource.MANUAL);
            ref.set(Epoch.epoch(7L, 42L));
            var reconciler = HealthReconciler.healthReconciler(SELF,
                                                               3,
                                                               _ -> Option.none(),
                                                               () -> Option.option(phaseRef.get()),
                                                               () -> Option.some(SELF),
                                                               onDutyCount::get,
                                                               applier,
                                                               HealthReconcilerConfig.DEFAULT,
                                                               factory);
            reconciler.start();
            reconciler.signalSelfReady();

            assertThat(lastSelfWriteValue().observedCoreEpoch()).isEqualTo(Epoch.epoch(7L, 42L));
        }

        @Test
        void healthReconciler_signalSelfReady_priorPresent_usesPriorMergeNotFactory() {
            var prior = NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.DRAINING,
                                                              0L,
                                                              "prior-host",
                                                              7777,
                                                              Epoch.epoch(11L, 0L),
                                                              HlcTimestamp.ZERO,
                                                              ProvisioningSource.MANUAL);
            selfPrior.set(prior);
            HealthReconciler.SelfOnDutyAtomFactory factory = (state, nowMs) -> NodeLifecycleValue.nodeLifecycleValue(state,
                                                                                                                     nowMs,
                                                                                                                     "factory-host",
                                                                                                                     1111,
                                                                                                                     Epoch.epoch(99L, 0L),
                                                                                                                     HlcTimestamp.ZERO,
                                                                                                                     ProvisioningSource.UNKNOWN);
            var reconciler = HealthReconciler.healthReconciler(SELF,
                                                               3,
                                                               nodeId -> nodeId.equals(SELF) ? Option.option(selfPrior.get()) : Option.none(),
                                                               () -> Option.option(phaseRef.get()),
                                                               () -> Option.some(SELF),
                                                               onDutyCount::get,
                                                               applier,
                                                               HealthReconcilerConfig.DEFAULT,
                                                               factory);
            reconciler.start();
            reconciler.signalSelfReady();

            var written = lastSelfWriteValue();
            assertThat(written.host()).as("prior host preserved").isEqualTo("prior-host");
            assertThat(written.port()).as("prior port preserved").isEqualTo(7777);
            assertThat(written.observedCoreEpoch()).as("prior epoch preserved").isEqualTo(Epoch.epoch(11L, 0L));
        }
    }

    @Nested class PhaseListenerReplay {
        @Test
        void healthReconciler_addPhaseListener_replaysCurrentPhaseImmediately() {
            var reconciler = HealthReconciler.healthReconciler(SELF,
                                                               3,
                                                               _ -> Option.none(),
                                                               () -> Option.some(ClusterPhase.NORMAL),
                                                               () -> Option.some(SELF),
                                                               onDutyCount::get,
                                                               applier,
                                                               HealthReconcilerConfig.DEFAULT);
            reconciler.start();
            var observed = new ArrayList<ClusterPhaseChanged>();
            reconciler.addPhaseListener(observed::add);

            assertThat(observed).hasSize(1);
            assertThat(observed.get(0).previous()).isEqualTo(ClusterPhase.NORMAL);
            assertThat(observed.get(0).current()).isEqualTo(ClusterPhase.NORMAL);
        }

        @Test
        void healthReconciler_addPhaseListener_replayBeforeSubsequentTransitions() {
            var reconciler = HealthReconciler.healthReconciler(SELF,
                                                               3,
                                                               _ -> Option.none(),
                                                               () -> Option.some(ClusterPhase.BOOTING),
                                                               () -> Option.some(SELF),
                                                               onDutyCount::get,
                                                               applier,
                                                               HealthReconcilerConfig.DEFAULT);
            reconciler.start();
            var observed = new ArrayList<ClusterPhaseChanged>();
            reconciler.addPhaseListener(observed::add);
            reconciler.onClusterPhasePut(ClusterPhaseValue.clusterPhaseValue(ClusterPhase.NORMAL));

            assertThat(observed).extracting(ClusterPhaseChanged::current)
                                .containsExactly(ClusterPhase.BOOTING, ClusterPhase.NORMAL);
        }
    }

    @Nested class RequestActivate {
        @Test
        void healthReconciler_requestActivate_writesOnDuty() {
            var reconciler = HealthReconciler.healthReconciler(SELF,
                                                               3,
                                                               _ -> Option.none(),
                                                               () -> Option.some(ClusterPhase.NORMAL),
                                                               () -> Option.some(SELF),
                                                               onDutyCount::get,
                                                               applier,
                                                               HealthReconcilerConfig.DEFAULT);
            reconciler.start();
            var target = NodeId.nodeId("target").unwrap();
            reconciler.requestActivate(target);

            assertThat(applier.commands).hasSize(1);
            var put = (KVCommand.Put<?, ?>) applier.commands.get(0);
            assertThat(((NodeLifecycleValue) put.value()).state()).isEqualTo(NodeLifecycleState.ON_DUTY);
        }
    }

    private NodeLifecycleValue lastSelfWriteValue() {
        return applier.commands.stream()
                                     .filter(cmd -> cmd instanceof KVCommand.Put<?, ?> put && put.key() instanceof NodeLifecycleKey k && k.nodeId().equals(SELF))
                                     .map(cmd -> (NodeLifecycleValue) ((KVCommand.Put<?, ?>) cmd).value())
                                     .reduce((a, b) -> b)
                                     .orElse(null);
    }

    private static final class RecordingApplier implements Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> {
        final List<KVCommand<?>> commands = new ArrayList<>();

        @Override public Promise<List<Object>> apply(List<KVCommand<AetherKey>> input) {
            commands.addAll(input);
            return Promise.success(List.of());
        }
    }

    @SuppressWarnings("unused")
    private static final class UnusedSupplierStub implements Supplier<Option<NodeId>> {
        @Override public Option<NodeId> get() {return Option.none();}
    }
}
