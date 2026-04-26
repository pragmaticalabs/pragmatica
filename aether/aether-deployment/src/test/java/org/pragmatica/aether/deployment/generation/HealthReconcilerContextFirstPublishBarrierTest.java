// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.generation.fsm.HealthReconcilerContext;
import org.pragmatica.aether.deployment.generation.fsm.HealthReconcilerState;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.metrics.observation.PeerObservationStore;
import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.generation.GenerationChangedSink;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmTestHarness;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;


/// Issue 2 regression — verifies the first-publish barrier defers external visibility of
/// the leader-projected snapshot beyond the original 1-second timer (now 3 seconds) and
/// that publishes after the grace expiry are immediate. The 3-second grace is a defensive
/// stopgap until rc2 introduces a real consensus-drain barrier (see TODO in
/// `publishLeadingSnapshotWithBarrier`).
class HealthReconcilerContextFirstPublishBarrierTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();

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
                                                      TimeSpan.timeSpan(30).seconds(),
                                                      10).unwrap();
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
                                                         org.pragmatica.aether.deployment.generation.PeerObservationReducer.peerObservationReducer(),
                                                         peerObservationStore,
                                                         now::get);
                    ctxHolder.set(c);
                    return c.dormant();
                };
        harness = FsmTestHarness.harness("health-reconciler-first-publish-barrier-test-" + SELF.id(), factory);
        ctx = ctxHolder.get();
    }

    @Test
    void firstPublish_isHeldBack_pastFormerOneSecondWindow() throws InterruptedException {
        var seeded = ctx.ambientSnapshot();
        var fresh = ClusterGenerationSnapshot.empty(2L).withDesiredCoreSize(5);
        ctx.publishLeadingSnapshotWithBarrier(fresh);

        // Sleep past the former 1-second window. Under the old timer the snapshot would have
        // already been released; under the new 3-second grace it must still be held back.
        Thread.sleep(1_500L);
        assertThat(ctx.ambientSnapshot())
                .as("first publish must remain hidden past the former 1-second window")
                .isSameAs(seeded);
    }

    @Test
    void firstPublish_releasesAfterGraceExpires_andSubsequentPublishesAreImmediate() throws InterruptedException {
        var fresh = ClusterGenerationSnapshot.empty(2L).withDesiredCoreSize(5);
        ctx.publishLeadingSnapshotWithBarrier(fresh);

        // Wait long enough for the 3-second grace to expire with margin.
        Thread.sleep(3_500L);
        assertThat(ctx.ambientSnapshot())
                .as("first publish must be visible after the grace timer fires")
                .isSameAs(fresh);

        // Subsequent publishes within the same tenure are immediate (barrier flag is set).
        var newer = ClusterGenerationSnapshot.empty(3L).withDesiredCoreSize(5);
        ctx.publishLeadingSnapshotWithBarrier(newer);
        assertThat(ctx.ambientSnapshot())
                .as("post-grace publishes are immediate")
                .isSameAs(newer);
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
