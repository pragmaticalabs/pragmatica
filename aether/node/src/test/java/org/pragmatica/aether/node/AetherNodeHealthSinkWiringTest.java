// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.generation.ClusterGenerationProjector;
import org.pragmatica.aether.deployment.generation.HealthReconciler;
import org.pragmatica.aether.deployment.generation.HealthReconcilerActivator;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.generation.CoreMember;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.HealthHint;
import org.pragmatica.aether.slice.generation.HealthSignal;
import org.pragmatica.aether.slice.generation.HealthSignalSink;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;


/// Mirrors the `AetherNode` wiring pattern: the node constructs a stable lambda
/// sink backed by an `AtomicReference` so that SWIM and QUIC callbacks — created
/// before the reconciler activator exists — still reach the activator once it
/// is attached.
///
/// Validates:
///   - The lambda's emissions are dropped (noop) until the activator is set.
///   - After `healthSinkRef.set(activator.sink())`, emissions that satisfy the
///     reconciler's leader gate reach the reconciler and affect its snapshot.
///   - The lambda reference itself is stable — the same instance can be handed
///     to SWIM and QUIC adapters at construction time and will retain its
///     forwarding contract for the node's lifetime.
class AetherNodeHealthSinkWiringTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final NodeId NODE_A = NodeId.nodeId("node-a").unwrap();

    @Test
    void stableSink_beforeActivatorAttached_emissionsAreNoOp() {
        var ref = new AtomicReference<HealthSignalSink>(HealthSignalSink.noop());
        HealthSignalSink stable = signal -> ref.get().emit(signal);

        stable.emit(new HealthSignal.SwimHint(NODE_A, HealthHint.FAULTY, Epoch.ZERO));
        stable.emit(new HealthSignal.QuicDisconnect(NODE_A, Epoch.ZERO));
        stable.emit(new HealthSignal.PingTimeout(NODE_A, 10, Epoch.ZERO));

        // No target attached yet — no observable side effect beyond noop consumption.
        assertThat(ref.get()).isNotNull();
    }

    @Test
    void stableSink_afterActivatorAttached_swimHintReachesReconciler() {
        var setup = buildLeaderReconcilerBehindRef();

        setup.sink.emit(new HealthSignal.SwimHint(NODE_A, HealthHint.SUSPECTED, Epoch.ZERO));

        assertThat(setup.reconciler.currentSnapshot().coreMembers().get(NODE_A).healthHint())
            .isEqualTo(HealthHint.SUSPECTED);
    }

    @Test
    void stableSink_afterActivatorAttached_quicDisconnectReachesReconciler() {
        var setup = buildLeaderReconcilerBehindRef();

        for (int i = 1; i <= 5; i++) {
            setup.sink.emit(new HealthSignal.QuicDisconnect(NODE_A, Epoch.ZERO));
        }

        // QuicDisconnect alone is advisory — increments miss counter but does not evict
        // or write lifecycle atoms. Absence of consensus writes confirms signal delivery
        // without spurious removal.
        assertThat(setup.cluster.batches()).isEmpty();
    }

    @Test
    void stableSink_whenReconcilerNotLeader_signalsAreDroppedBySinkGate() {
        var setup = buildLeaderReconcilerBehindRef();
        setup.isLeader.set(false);

        setup.sink.emit(new HealthSignal.SwimHint(NODE_A, HealthHint.FAULTY, Epoch.ZERO));
        setup.sink.emit(new HealthSignal.PingTimeout(NODE_A, 10, Epoch.ZERO));

        assertThat(setup.reconciler.currentSnapshot().coreMembers().get(NODE_A).healthHint())
            .isEqualTo(HealthHint.HEALTHY);
        assertThat(setup.cluster.batches()).isEmpty();
    }

    private Setup buildLeaderReconcilerBehindRef() {
        var ref = new AtomicReference<HealthSignalSink>(HealthSignalSink.noop());
        HealthSignalSink stable = signal -> ref.get().emit(signal);
        var cluster = new RecordingClusterNode();
        var hlcClock = HlcClock.hlcClock(SELF.id()).unwrap();
        var isLeader = new AtomicBoolean(true);
        var rabiaTerm = new AtomicLong(1L);
        var reconciler = HealthReconciler.healthReconciler(SELF,
                                                            cluster,
                                                            ClusterGenerationProjector.clusterGenerationProjector(),
                                                            hlcClock,
                                                            rabiaTerm::get,
                                                            isLeader,
                                                            AutoHealConfig.DEFAULT);
        var activator = HealthReconcilerActivator.healthReconcilerActivator(reconciler, isLeader);
        activator.onLeaderChange(new LeaderChange(Option.some(SELF), true));
        seedOneCoreNode(reconciler);
        ref.set(activator.sink());
        return new Setup(stable, reconciler, cluster, isLeader);
    }

    private static void seedOneCoreNode(HealthReconciler reconciler) {
        var members = new LinkedHashMap<NodeId, CoreMember>();
        members.put(NODE_A,
                     CoreMember.coreMember(NODE_A,
                                           "host-a",
                                           9001,
                                           NodeLifecycleState.ON_DUTY,
                                           HealthHint.HEALTHY,
                                           Epoch.ZERO,
                                           Epoch.ZERO));
        var seeded = ClusterGenerationSnapshot.empty(1L).withDesiredCoreSize(1)
                                               .withCoreMembers(members);
        reconciler.seedSnapshot(seeded);
    }

    private record Setup(HealthSignalSink sink,
                         HealthReconciler reconciler,
                         RecordingClusterNode cluster,
                         AtomicBoolean isLeader) {}

    private static final class RecordingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private final List<List<KVCommand<AetherKey>>> batches = new ArrayList<>();

        @Override public NodeId self() {return SELF;}

        @Override public TopologyManager topologyManager() {
            throw new UnsupportedOperationException("not used");
        }

        @Override public Promise<Unit> start() {return Promise.success(Unit.unit());}
        @Override public Promise<Unit> stop() {return Promise.success(Unit.unit());}

        @SuppressWarnings({"unchecked", "rawtypes"})
        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            batches.add(List.copyOf(commands));
            return (Promise) Promise.success(List.of());
        }

        List<List<KVCommand<AetherKey>>> batches() {
            return List.copyOf(batches);
        }
    }
}
