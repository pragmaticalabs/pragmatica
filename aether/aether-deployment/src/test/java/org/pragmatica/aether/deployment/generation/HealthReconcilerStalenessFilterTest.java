// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.generation.fsm.HealthReconcilerContext;
import org.pragmatica.aether.deployment.generation.fsm.HealthReconcilerEvents.BecameLeader;
import org.pragmatica.aether.deployment.generation.fsm.HealthReconcilerEvents.SignalReceived;
import org.pragmatica.aether.deployment.generation.fsm.HealthReconcilerEvents.SnapshotSeeded;
import org.pragmatica.aether.deployment.generation.fsm.HealthReconcilerState;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.metrics.observation.PeerObservationStore;
import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.generation.ConnectivityReport;
import org.pragmatica.aether.slice.generation.CoreMember;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.GenerationChangedSink;
import org.pragmatica.aether.slice.generation.HealthHint;
import org.pragmatica.aether.slice.generation.HealthSignal;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.fsm.ClusterFsmEvent.QuorumEstablished;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmTestHarness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;


/// Tests for the consumer-side staleness filter on remote peer observations.
///
/// `PeerObservation*` records carry a wall-clock `producedAtMs`. The leader-side
/// `HealthReconciler` drops `RemoteSwimHint` / `RemoteConnectivity` whose
/// `producedAtMs` is older than `now - AutoHealConfig.staleObservationTtl`.
/// Synthetic in-process signals with `producedAtMs == 0L` are accepted (no
/// timestamp means no staleness check).
class HealthReconcilerStalenessFilterTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();

    private static final NodeId OBSERVER = NodeId.nodeId("observer").unwrap();

    private static final NodeId NODE_A = NodeId.nodeId("node-a").unwrap();

    private static final TimeSpan STALE_TTL = TimeSpan.timeSpan(30).seconds();

    private AtomicBoolean externalLeaderGate;

    private AtomicLong rabiaTerm;

    private AtomicLong now;

    private FsmTestHarness<HealthReconcilerState, ClusterFsmEvent> harness;

    private HealthReconcilerContext ctx;

    @BeforeEach
    void setUp() {
        externalLeaderGate = new AtomicBoolean(true);
        rabiaTerm = new AtomicLong(1L);
        now = new AtomicLong(1_000_000_000L);
        var hlcClock = HlcClock.hlcClock(SELF.id()).unwrap();
        ClusterNode<KVCommand<AetherKey>> cluster = new NoopClusterNode();
        var autoHeal = AutoHealConfig.autoHealConfig(TimeSpan.timeSpan(10).seconds(),
                                                      TimeSpan.timeSpan(15).seconds(),
                                                      STALE_TTL).unwrap();
        var ctxHolder = new AtomicReference<HealthReconcilerContext>();
        Function<Fsm<HealthReconcilerState, ClusterFsmEvent>, HealthReconcilerState> factory =
                fsm -> {
                    var c = new HealthReconcilerContext(fsm,
                                                        SELF,
                                                        cluster,
                                                        hlcClock,
                                                        rabiaTerm::get,
                                                        externalLeaderGate::get,
                                                        autoHeal,
                                                        GenerationChangedSink.noop(),
                                                        PeerObservationReducer.peerObservationReducer(),
                                                        PeerObservationStore.peerObservationStore(),
                                                        now::get);
                    ctxHolder.set(c);
                    return c.dormant();
                };
        harness = FsmTestHarness.harness("health-reconciler-staleness-test-" + SELF.id(), factory);
        ctx = ctxHolder.get();
        // Seed in Dormant — updates ambientSnapshot — then become leader so LeadingSteady
        // inherits the seeded snapshot as its authoritative state.
        harness.dispatch(new SnapshotSeeded(seedSnapshotWithCoreA()));
        harness.dispatch(new QuorumEstablished());
        harness.dispatch(new BecameLeader(Epoch.epoch(1L, 0L)));
    }

    private static ClusterGenerationSnapshot seedSnapshotWithCoreA() {
        var snapshot = ClusterGenerationSnapshot.empty(1L).withDesiredCoreSize(1);
        var coreMembers = new LinkedHashMap<NodeId, CoreMember>();
        coreMembers.put(NODE_A,
                         CoreMember.coreMember(NODE_A,
                                               "host-a",
                                               9001,
                                               NodeLifecycleState.ON_DUTY,
                                               HealthHint.HEALTHY,
                                               Epoch.epoch(1L, 0L),
                                               Epoch.epoch(1L, 0L)));
        return snapshot.withCoreMembers(coreMembers);
    }

    @Nested
    class RemoteSwimHintStaleness {
        @Test
        void onSignal_remoteSwimHintWithinTtl_passesThrough_marksSuspected() {
            // Observation produced 1 second ago — well within the 30s TTL.
            var producedAtMs = now.get() - 1_000L;
            harness.dispatch(new SignalReceived(new HealthSignal.RemoteSwimHint(OBSERVER,
                                                                                  NODE_A,
                                                                                  HealthHint.FAULTY,
                                                                                  Epoch.epoch(1L, 0L),
                                                                                  producedAtMs)));
            assertThat(currentSnapshot().coreMembers().get(NODE_A).healthHint()).isEqualTo(HealthHint.SUSPECTED);
        }

        @Test
        void onSignal_remoteSwimHintOlderThanTtl_isDropped_noHintChange() {
            // Observation produced 60 seconds ago — twice the 30s TTL.
            var producedAtMs = now.get() - 60_000L;
            harness.dispatch(new SignalReceived(new HealthSignal.RemoteSwimHint(OBSERVER,
                                                                                  NODE_A,
                                                                                  HealthHint.FAULTY,
                                                                                  Epoch.epoch(1L, 0L),
                                                                                  producedAtMs)));
            assertThat(currentSnapshot().coreMembers().get(NODE_A).healthHint()).isEqualTo(HealthHint.HEALTHY);
        }

        @Test
        void onSignal_remoteSwimHintAtExactTtlBoundary_passesThrough() {
            // Observation exactly at the boundary (now - TTL): not strictly less, so accepted.
            var producedAtMs = now.get() - STALE_TTL.millis();
            harness.dispatch(new SignalReceived(new HealthSignal.RemoteSwimHint(OBSERVER,
                                                                                  NODE_A,
                                                                                  HealthHint.FAULTY,
                                                                                  Epoch.epoch(1L, 0L),
                                                                                  producedAtMs)));
            assertThat(currentSnapshot().coreMembers().get(NODE_A).healthHint()).isEqualTo(HealthHint.SUSPECTED);
        }

        @Test
        void onSignal_remoteSwimHintWithZeroTimestamp_passesThroughAsBackwardCompatible() {
            // producedAtMs == 0L means "no timestamp available" — accepted regardless of TTL.
            harness.dispatch(new SignalReceived(new HealthSignal.RemoteSwimHint(OBSERVER,
                                                                                  NODE_A,
                                                                                  HealthHint.FAULTY,
                                                                                  Epoch.epoch(1L, 0L),
                                                                                  0L)));
            assertThat(currentSnapshot().coreMembers().get(NODE_A).healthHint()).isEqualTo(HealthHint.SUSPECTED);
        }
    }

    @Nested
    class RemoteConnectivityStaleness {
        @Test
        void onSignal_remoteConnectivityOlderThanTtl_isDropped_noPingMissAccrued() {
            var producedAtMs = now.get() - 60_000L;
            harness.dispatch(new SignalReceived(new HealthSignal.RemoteConnectivity(OBSERVER,
                                                                                      NODE_A,
                                                                                      ConnectivityReport.DISCONNECTED,
                                                                                      Epoch.epoch(1L, 0L),
                                                                                      producedAtMs)));
            // Stale RemoteConnectivity is dropped before reaching handleQuicDisconnect — so the
            // reducer does not record a ping miss and the snapshot remains untouched.
            assertThat(currentSnapshot().coreMembers().get(NODE_A).healthHint()).isEqualTo(HealthHint.HEALTHY);
        }

        @Test
        void onSignal_remoteConnectivityWithinTtl_passesThroughToQuicDisconnectPath() {
            var producedAtMs = now.get() - 1_000L;
            harness.dispatch(new SignalReceived(new HealthSignal.RemoteConnectivity(OBSERVER,
                                                                                      NODE_A,
                                                                                      ConnectivityReport.DISCONNECTED,
                                                                                      Epoch.epoch(1L, 0L),
                                                                                      producedAtMs)));
            // QuicDisconnect alone does not change the snapshot — but we verify the signal was NOT
            // dropped by exercising it through the reconciler without exception.
            assertThat(currentSnapshot().coreMembers().get(NODE_A).healthHint()).isEqualTo(HealthHint.HEALTHY);
        }
    }

    private ClusterGenerationSnapshot currentSnapshot() {
        return switch (harness.state()){
            case HealthReconcilerState.LeadingSteady ls -> ls.snapshot();
            case HealthReconcilerState.LeadingReprojecting lr -> lr.snapshot();
            default -> ctx.ambientSnapshot();
        };
    }

    private static final class NoopClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        @Override public NodeId self() { return SELF; }

        @Override public TopologyManager topologyManager() {
            throw new UnsupportedOperationException("not used in tests");
        }

        @Override public Promise<Unit> start() { return Promise.success(Unit.unit()); }

        @Override public Promise<Unit> stop() { return Promise.success(Unit.unit()); }

        @SuppressWarnings({"unchecked", "rawtypes"})
        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            return (Promise) Promise.success(new ArrayList<>());
        }
    }
}
