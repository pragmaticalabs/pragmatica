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


/// Theme A — Fix 2: regression test for `handlePingTimeout` invoking
/// `promoteToFaultyIfThresholdReached` independently of any `QuicDisconnect` signal.
///
/// Before the fix the QUIC promotion path fired only from `handleQuicDisconnect`, which itself
/// is gated on SWIM-driven `DisconnectNode`. Result: zero independent failure detection — a wedged
/// SWIM left the cluster blind. After the fix, N consecutive `PingTimeout` signals (N =
/// `autoHealConfig.quicMissPromotionThreshold()`) flip `swimHints[peer]` to FAULTY directly.
class HealthReconcilerContextPingTimeoutPromotionTest {
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
        harness = FsmTestHarness.harness("health-reconciler-ping-timeout-promotion-test-" + SELF.id(), factory);
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
    void pingTimeout_belowThreshold_doesNotPromoteToFaulty() {
        for (var i = 0; i < PROMOTION_THRESHOLD - 1; i++) {
            dispatchPingTimeout();
        }
        assertThat(peerObservationStore.pingMissCount(NODE_A)).isEqualTo(PROMOTION_THRESHOLD - 1);
        assertThat(ctx.swimHintsView().get(NODE_A)).isNotEqualTo(HealthHint.FAULTY);
    }

    @Test
    void pingTimeout_atThreshold_promotesToFaultyWithoutQuicDisconnect() {
        for (var i = 0; i < PROMOTION_THRESHOLD; i++) {
            dispatchPingTimeout();
        }
        assertThat(peerObservationStore.pingMissCount(NODE_A)).isEqualTo(PROMOTION_THRESHOLD);
        assertThat(ctx.swimHintsView().get(NODE_A)).isEqualTo(HealthHint.FAULTY);
    }

    @Test
    void pingTimeout_pastThreshold_isIdempotent() {
        for (var i = 0; i < PROMOTION_THRESHOLD; i++) {
            dispatchPingTimeout();
        }
        assertThat(ctx.swimHintsView().get(NODE_A)).isEqualTo(HealthHint.FAULTY);
        // Additional misses past threshold — hint must remain FAULTY (no flip, no exception).
        dispatchPingTimeout();
        dispatchPingTimeout();
        assertThat(peerObservationStore.pingMissCount(NODE_A)).isEqualTo(PROMOTION_THRESHOLD + 2);
        assertThat(ctx.swimHintsView().get(NODE_A)).isEqualTo(HealthHint.FAULTY);
    }

    private void dispatchPingTimeout() {
        harness.dispatch(new SignalReceived(new HealthSignal.PingTimeout(NODE_A, 1, Epoch.epoch(1L, 0L))));
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
