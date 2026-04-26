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


/// Tests for the per-entry TTL on the leader-projection `swimHints` map.
///
/// Defense-in-depth against sticky SUSPECTED state: SWIM may transiently mark a peer
/// SUSPECTED during boot (probes fire before the peer's SWIM transport is ready). Without a
/// TTL, that hint persists forever unless something explicitly clears it (a fresh
/// HEALTHY emission or QUIC liveness path). The TTL ensures any orphaned entry self-heals
/// after `swimHintsTtl` without re-emission — at which point the projector defaults to
/// `HealthHint.HEALTHY`.
class HealthReconcilerSwimHintsTtlTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();

    private static final NodeId NODE_A = NodeId.nodeId("node-a").unwrap();

    private static final TimeSpan SWIM_HINTS_TTL = TimeSpan.timeSpan(60).seconds();

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
        var peerObservationStore = PeerObservationStore.peerObservationStore();
        var hlcClock = HlcClock.hlcClock(SELF.id()).unwrap();
        ClusterNode<KVCommand<AetherKey>> cluster = new NoopClusterNode();
        var autoHeal = AutoHealConfig.autoHealConfig(TimeSpan.timeSpan(10).seconds(),
                                                      TimeSpan.timeSpan(15).seconds(),
                                                      TimeSpan.timeSpan(30).seconds(),
                                                      AutoHealConfig.DEFAULT_QUIC_MISS_PROMOTION_THRESHOLD,
                                                      AutoHealConfig.DEFAULT_PROVISIONING_TIMEOUT,
                                                      AutoHealConfig.DEFAULT_PROVISION_STABILITY_WINDOW,
                                                      AutoHealConfig.DEFAULT_DECOMMISSIONED_RETENTION,
                                                      SWIM_HINTS_TTL).unwrap();
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
        harness = FsmTestHarness.harness("health-reconciler-swim-hints-ttl-test-" + SELF.id(), factory);
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
    void swimHints_freshEntry_visibleBeforeTtlElapses() {
        // SwimHint(SUSPECTED) at t=0 → swimHintsView() reports SUSPECTED for 59s.
        dispatchSwimHint(HealthHint.SUSPECTED);
        assertThat(ctx.swimHintsView().get(NODE_A))
            .as("fresh hint must be visible immediately after emission")
            .isEqualTo(HealthHint.SUSPECTED);

        // Advance clock to t=59s — still under 60s TTL, hint remains visible.
        now.addAndGet(59_000L);
        assertThat(ctx.swimHintsView().get(NODE_A))
            .as("hint must remain visible at t=59s (under 60s TTL)")
            .isEqualTo(HealthHint.SUSPECTED);
    }

    @Test
    void swimHints_expiredEntry_treatedAsAbsent() {
        // SwimHint(SUSPECTED) at t=0; advance to t=61s → hint must be absent (TTL elapsed,
        // projector defaults to HEALTHY when key is missing).
        dispatchSwimHint(HealthHint.SUSPECTED);
        now.addAndGet(61_000L);
        assertThat(ctx.swimHintsView().get(NODE_A))
            .as("expired hint must be treated as absent — projector defaults to HEALTHY")
            .isNull();
        assertThat(ctx.swimHintsView())
            .as("expired entries must not appear in the view at all")
            .isEmpty();
    }

    @Test
    void swimHints_reEmittedHint_renewsTtl() {
        // SwimHint(SUSPECTED) at t=0; re-emit at t=30s; check at t=85s — t=30+60=90s, so
        // visible. At t=91s — expired again.
        dispatchSwimHint(HealthHint.SUSPECTED);
        now.addAndGet(30_000L);
        dispatchSwimHint(HealthHint.SUSPECTED);

        // t=30+55=85s. Window from re-emission is 0..60s → visible.
        now.addAndGet(55_000L);
        assertThat(ctx.swimHintsView().get(NODE_A))
            .as("re-emission renews TTL — hint visible 55s after second emit (well within 60s)")
            .isEqualTo(HealthHint.SUSPECTED);

        // Advance past t=30+61=91s. From re-emission: 61s → expired.
        now.addAndGet(6_000L);
        assertThat(ctx.swimHintsView().get(NODE_A))
            .as("hint must expire 61s after the most recent re-emission")
            .isNull();
    }

    @Test
    void swimHints_swimReportingHealthy_clearsHintImmediately() {
        // A fresh SwimHint(HEALTHY) writes the entry as HEALTHY. The projector treats
        // HEALTHY same as absent (default). Verify the entry is recorded and self-expires
        // even without explicit removal.
        dispatchSwimHint(HealthHint.SUSPECTED);
        assertThat(ctx.swimHintsView().get(NODE_A)).isEqualTo(HealthHint.SUSPECTED);

        dispatchSwimHint(HealthHint.HEALTHY);
        assertThat(ctx.swimHintsView().get(NODE_A))
            .as("explicit HEALTHY emission overrides SUSPECTED in the projection")
            .isEqualTo(HealthHint.HEALTHY);

        // After TTL, even the HEALTHY entry expires (default-when-absent is HEALTHY anyway).
        now.addAndGet(61_000L);
        assertThat(ctx.swimHintsView().get(NODE_A))
            .as("HEALTHY entry must also expire — convergent with absent-default")
            .isNull();
    }

    private void dispatchSwimHint(HealthHint state) {
        harness.dispatch(new SignalReceived(new HealthSignal.SwimHint(NODE_A, state, Epoch.epoch(1L, 0L))));
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
