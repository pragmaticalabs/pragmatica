// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.generation.fsm.HealthReconcilerContext;
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
import org.pragmatica.lang.Option;
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


/// Tests for the defense-in-depth promotion of sustained QUIC ping-misses to
/// `swimHints[peer] = FAULTY`.
///
/// SWIM is the primary signal that flips a peer to FAULTY, but if SWIM is delayed or wedged
/// the auto-heal path silently no-ops because `shouldEvict` requires
/// `swimHints[node] == FAULTY`. To keep auto-heal viable, the leader-side `HealthReconciler`
/// promotes the peer to FAULTY once `peerObservationStore.recordPingMiss` reaches the
/// configured threshold. Promotions are idempotent. The miss counter is reset on a fresh
/// CONNECTED report (or SWIM HEALTHY).
class HealthReconcilerQuicMissPromotionTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();

    private static final NodeId NODE_A = NodeId.nodeId("node-a").unwrap();

    private static final TimeSpan STALE_TTL = TimeSpan.timeSpan(30).seconds();

    private static final int PROMOTION_THRESHOLD = 10;

    private AtomicBoolean externalLeaderGate;

    private AtomicLong rabiaTerm;

    private AtomicLong now;

    private FsmTestHarness<HealthReconcilerState, ClusterFsmEvent> harness;

    private HealthReconcilerContext ctx;

    private PeerObservationStore peerObservationStore;

    @BeforeEach
    void setUp() {
        externalLeaderGate = new AtomicBoolean(true);
        rabiaTerm = new AtomicLong(1L);
        now = new AtomicLong(1_000_000_000L);
        peerObservationStore = PeerObservationStore.peerObservationStore();
        var hlcClock = HlcClock.hlcClock(SELF.id()).unwrap();
        ClusterNode<KVCommand<AetherKey>> cluster = new NoopClusterNode();
        var autoHeal = AutoHealConfig.autoHealConfig(TimeSpan.timeSpan(10).seconds(),
                                                      TimeSpan.timeSpan(15).seconds(),
                                                      STALE_TTL,
                                                      PROMOTION_THRESHOLD).unwrap();
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
                                                        peerObservationStore,
                                                        now::get);
                    ctxHolder.set(c);
                    return c.dormant();
                };
        harness = FsmTestHarness.harness("health-reconciler-quic-miss-promotion-test-" + SELF.id(), factory);
        ctx = ctxHolder.get();
        harness.dispatch(new SnapshotSeeded(seedSnapshotWithCoreA()));
        harness.dispatch(new QuorumEstablished());
        harness.dispatch(new ClusterFsmEvent.LeaderChange(Option.some(SELF), true));
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

    @Test
    void quicDisconnect_belowThreshold_doesNotPromoteToFaulty() {
        for (var i = 0; i < PROMOTION_THRESHOLD - 1; i++) {
            dispatchQuicDisconnect();
        }
        assertThat(peerObservationStore.pingMissCount(NODE_A)).isEqualTo(PROMOTION_THRESHOLD - 1);
        assertThat(ctx.swimHintsView().get(NODE_A)).isNotEqualTo(HealthHint.FAULTY);
    }

    @Test
    void quicDisconnect_atThreshold_promotesToFaulty() {
        for (var i = 0; i < PROMOTION_THRESHOLD; i++) {
            dispatchQuicDisconnect();
        }
        assertThat(peerObservationStore.pingMissCount(NODE_A)).isEqualTo(PROMOTION_THRESHOLD);
        assertThat(ctx.swimHintsView().get(NODE_A)).isEqualTo(HealthHint.FAULTY);
    }

    @Test
    void quicDisconnect_alreadyFaulty_isIdempotent_noStateRegression() {
        for (var i = 0; i < PROMOTION_THRESHOLD; i++) {
            dispatchQuicDisconnect();
        }
        assertThat(ctx.swimHintsView().get(NODE_A)).isEqualTo(HealthHint.FAULTY);
        // Another miss past the threshold — peer must remain FAULTY (no flip back, no exception).
        // The promote helper short-circuits before re-logging when the hint is already FAULTY.
        dispatchQuicDisconnect();
        assertThat(peerObservationStore.pingMissCount(NODE_A)).isEqualTo(PROMOTION_THRESHOLD + 1);
        assertThat(ctx.swimHintsView().get(NODE_A)).isEqualTo(HealthHint.FAULTY);
    }

    @Test
    void successfulPing_resetsMissCounter_preventsPromotion() {
        for (var i = 0; i < 5; i++) {
            dispatchQuicDisconnect();
        }
        assertThat(peerObservationStore.pingMissCount(NODE_A)).isEqualTo(5);
        // RemoteConnectivity(CONNECTED) signals a fresh successful round-trip — counter resets.
        dispatchRemoteConnectivity(ConnectivityReport.CONNECTED);
        assertThat(peerObservationStore.pingMissCount(NODE_A)).isZero();
        // 9 more misses — still below threshold (10) because counter restarted.
        for (var i = 0; i < PROMOTION_THRESHOLD - 1; i++) {
            dispatchQuicDisconnect();
        }
        assertThat(peerObservationStore.pingMissCount(NODE_A)).isEqualTo(PROMOTION_THRESHOLD - 1);
        assertThat(ctx.swimHintsView().get(NODE_A)).isNotEqualTo(HealthHint.FAULTY);
    }

    private void dispatchQuicDisconnect() {
        harness.dispatch(new SignalReceived(new HealthSignal.QuicDisconnect(NODE_A, Epoch.epoch(1L, 0L))));
    }

    private void dispatchRemoteConnectivity(ConnectivityReport state) {
        harness.dispatch(new SignalReceived(new HealthSignal.RemoteConnectivity(SELF,
                                                                                  NODE_A,
                                                                                  state,
                                                                                  Epoch.epoch(1L, 0L),
                                                                                  now.get())));
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
