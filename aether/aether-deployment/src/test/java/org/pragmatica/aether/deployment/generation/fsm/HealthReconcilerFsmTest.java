// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation.fsm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.generation.PeerObservationReducer;
import org.pragmatica.aether.metrics.observation.PeerObservationStore;
import org.pragmatica.aether.deployment.generation.fsm.HealthReconcilerEvents.ReprojectionCompleted;
import org.pragmatica.aether.deployment.generation.fsm.HealthReconcilerEvents.ReprojectionRequested;
import org.pragmatica.aether.deployment.generation.fsm.HealthReconcilerEvents.SnapshotSeeded;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.GenerationChangedSink;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.fsm.ClusterFsmEvent.LeaderChange;
import org.pragmatica.consensus.fsm.ClusterFsmEvent.QuorumDisappeared;
import org.pragmatica.consensus.fsm.ClusterFsmEvent.QuorumEstablished;
import org.pragmatica.consensus.fsm.ClusterFsmEvent.Shutdown;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.statemachine.FsmTestHarness;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.pragmatica.statemachine.Fsm;

import static org.assertj.core.api.Assertions.assertThat;

/// FSM-level tests for the HealthReconciler state machine. Exercises the explicit state
/// transitions via [`FsmTestHarness`], independent from the public `HealthReconciler` surface.
class HealthReconcilerFsmTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final Epoch LEADER_EPOCH = Epoch.epoch(1L, 0L);

    private AtomicBoolean externalLeaderGate;
    private AtomicLong rabiaTerm;
    private HealthReconcilerContext ctx;
    private FsmTestHarness<HealthReconcilerState, ClusterFsmEvent> harness;

    @BeforeEach
    void setUp() {
        externalLeaderGate = new AtomicBoolean(true);
        rabiaTerm = new AtomicLong(1L);
        var hlcClock = HlcClock.hlcClock(SELF.id()).unwrap();
        ClusterNode<KVCommand<AetherKey>> cluster = new NoopClusterNode();
        var ctxHolder = new AtomicReference<HealthReconcilerContext>();
        Function<Fsm<HealthReconcilerState, ClusterFsmEvent>, HealthReconcilerState> factory =
                fsm -> buildContext(fsm, ctxHolder, cluster, hlcClock);
        harness = FsmTestHarness.harness("health-reconciler-fsm-test-" + SELF.id(), factory);
        ctx = ctxHolder.get();
    }

    private HealthReconcilerState buildContext(Fsm<HealthReconcilerState, ClusterFsmEvent> fsm,
                                                 AtomicReference<HealthReconcilerContext> ctxHolder,
                                                 ClusterNode<KVCommand<AetherKey>> cluster,
                                                 HlcClock hlcClock) {
        var context = new HealthReconcilerContext(fsm,
                                                    SELF,
                                                    cluster,
                                                    hlcClock,
                                                    rabiaTerm::get,
                                                    externalLeaderGate::get,
                                                    AutoHealConfig.DEFAULT,
                                                    GenerationChangedSink.noop(),
                                                    PeerObservationReducer.peerObservationReducer(),
                                                    PeerObservationStore.peerObservationStore());
        ctxHolder.set(context);
        return context.dormant();
    }

    @Nested
    class HappyPath {
        @Test
        void dormant_QuorumEstablished_becomesQuorumWaiting() {
            harness.dispatch(new QuorumEstablished());
            assertThat(harness.state()).isInstanceOf(HealthReconcilerState.QuorumWaiting.class);
        }

        @Test
        void quorumWaiting_LeaderChangeLocalIsLeader_becomesLeadingSteadyWithDefaultLeaderEpoch() {
            harness.dispatch(new QuorumEstablished());
            harness.dispatch(new LeaderChange(Option.some(SELF), true));
            assertThat(harness.state()).isInstanceOf(HealthReconcilerState.LeadingSteady.class);
            var leading = (HealthReconcilerState.LeadingSteady) harness.state();
            assertThat(leading.startEpoch()).isEqualTo(LEADER_EPOCH);
        }

        @Test
        void leadingSteady_ReprojectionRequested_entersLeadingReprojecting() {
            harness.dispatch(new QuorumEstablished());
            harness.dispatch(new LeaderChange(Option.some(SELF), true));
            var baseline = ctx.ambientSnapshot();
            Supplier<ClusterGenerationSnapshot> supplier = () -> baseline;
            harness.dispatch(new ReprojectionRequested(supplier, "test"));
            assertThat(harness.state()).isInstanceOf(HealthReconcilerState.LeadingReprojecting.class);
            var lr = (HealthReconcilerState.LeadingReprojecting) harness.state();
            assertThat(lr.supplier()).isSameAs(supplier);
        }

        @Test
        void leadingReprojecting_ReprojectionCompleted_returnsToLeadingSteadyWithNewSnapshot() {
            harness.dispatch(new QuorumEstablished());
            harness.dispatch(new LeaderChange(Option.some(SELF), true));
            var freshSnapshot = ClusterGenerationSnapshot.empty(1L).withDesiredCoreSize(5);
            Supplier<ClusterGenerationSnapshot> supplier = () -> freshSnapshot;
            harness.dispatch(new ReprojectionRequested(supplier, "test"));
            // `ReprojectionCompleted` would normally be dispatched by the executor. The test fires
            // it directly to avoid scheduling non-determinism.
            harness.dispatch(new ReprojectionCompleted(LEADER_EPOCH, freshSnapshot));
            assertThat(harness.state()).isInstanceOf(HealthReconcilerState.LeadingSteady.class);
        }

        @Test
        void leadingSteady_leaderLoss_viaLeaderChange_returnsToDormant() {
            harness.dispatch(new QuorumEstablished());
            harness.dispatch(new LeaderChange(Option.some(SELF), true));
            harness.dispatch(new LeaderChange(Option.none(), false));
            assertThat(harness.state()).isInstanceOf(HealthReconcilerState.Dormant.class);
        }

        @Test
        void dormant_toStopped_viaShutdown() {
            harness.dispatch(new QuorumEstablished());
            harness.dispatch(new LeaderChange(Option.some(SELF), true));
            harness.dispatch(new LeaderChange(Option.none(), false));
            // Dormant → Stopped
            harness.dispatch(new Shutdown());
            assertThat(harness.state()).isInstanceOf(HealthReconcilerState.Stopped.class);
        }

        @Test
        void full_lifecycle_traversesAllStates() {
            // Dormant → QuorumWaiting → LeadingSteady → LeadingReprojecting → LeadingSteady
            // → Dormant (via QuorumDisappeared) → Stopped (via Shutdown)
            harness.dispatch(new QuorumEstablished());
            assertThat(harness.state()).isInstanceOf(HealthReconcilerState.QuorumWaiting.class);

            harness.dispatch(new LeaderChange(Option.some(SELF), true));
            assertThat(harness.state()).isInstanceOf(HealthReconcilerState.LeadingSteady.class);

            var projected = ClusterGenerationSnapshot.empty(1L).withDesiredCoreSize(3);
            harness.dispatch(new ReprojectionRequested(() -> projected, "lifecycle"));
            assertThat(harness.state()).isInstanceOf(HealthReconcilerState.LeadingReprojecting.class);

            harness.dispatch(new ReprojectionCompleted(LEADER_EPOCH, projected));
            assertThat(harness.state()).isInstanceOf(HealthReconcilerState.LeadingSteady.class);

            harness.dispatch(new QuorumDisappeared());
            assertThat(harness.state()).isInstanceOf(HealthReconcilerState.Dormant.class);

            harness.dispatch(new Shutdown());
            assertThat(harness.state()).isInstanceOf(HealthReconcilerState.Stopped.class);
        }
    }

    @Nested
    class CoalesceReprojection {
        @Test
        void twoReprojectionRequestedInQuickSuccession_secondSupplierReplacesFirst() {
            harness.dispatch(new QuorumEstablished());
            harness.dispatch(new LeaderChange(Option.some(SELF), true));
            Supplier<ClusterGenerationSnapshot> first = () -> ClusterGenerationSnapshot.empty(1L).withDesiredCoreSize(1);
            Supplier<ClusterGenerationSnapshot> second = () -> ClusterGenerationSnapshot.empty(1L).withDesiredCoreSize(2);

            harness.dispatch(new ReprojectionRequested(first, "first"));
            assertThat(((HealthReconcilerState.LeadingReprojecting) harness.state()).supplier()).isSameAs(first);

            harness.dispatch(new ReprojectionRequested(second, "second"));
            assertThat(harness.state()).isInstanceOf(HealthReconcilerState.LeadingReprojecting.class);
            assertThat(((HealthReconcilerState.LeadingReprojecting) harness.state()).supplier()).isSameAs(second);
        }
    }

    @Nested
    class CasContention {
        @Test
        void eightConcurrentLeaderChange_singleWinnerAndLeadingStateInitializedOnce() throws Exception {
            harness.dispatch(new QuorumEstablished());
            var transitionsBefore = harness.transitions().size();
            var events = new ArrayList<ClusterFsmEvent>();
            for (int i = 0; i < 8; i++) {
                events.add(new LeaderChange(Option.some(SELF), true));
            }
            harness.dispatchConcurrently(events);
            assertThat(harness.state()).isInstanceOf(HealthReconcilerState.LeadingSteady.class);
            // Exactly one transition from QuorumWaiting → LeadingSteady should be recorded.
            var newTransitions = harness.transitions().subList(transitionsBefore, harness.transitions().size());
            var quorumWaitingToLeading = newTransitions.stream()
                    .filter(t -> t.from() instanceof HealthReconcilerState.QuorumWaiting
                                 && t.to() instanceof HealthReconcilerState.LeadingSteady)
                    .count();
            assertThat(quorumWaitingToLeading)
                    .as("exactly one winner advances QuorumWaiting → LeadingSteady")
                    .isEqualTo(1);
        }
    }

    @Nested
    class IgnoredEvents {
        @Test
        void reprojectionRequested_inDormant_isIgnored() {
            Supplier<ClusterGenerationSnapshot> supplier = () -> ClusterGenerationSnapshot.empty(1L);
            harness.dispatch(new ReprojectionRequested(supplier, "dormant-test"));
            assertThat(harness.state()).isInstanceOf(HealthReconcilerState.Dormant.class);
            assertThat(harness.ignored()).isNotEmpty();
        }

        @Test
        void snapshotSeeded_inDormant_recordedAsIgnoredButAmbientUpdated() {
            var seed = ClusterGenerationSnapshot.empty(7L);
            harness.dispatch(new SnapshotSeeded(seed));
            assertThat(harness.state()).isInstanceOf(HealthReconcilerState.Dormant.class);
            assertThat(ctx.ambientSnapshot()).isSameAs(seed);
        }
    }

    @Nested
    class EpochStaleGuard {
        @Test
        void reprojectionCompleted_withOlderStartEpoch_rejectedNoStateAdvance() throws InterruptedException {
            harness.dispatch(new QuorumEstablished());
            harness.dispatch(new LeaderChange(Option.some(SELF), true));
            // Use a latch-blocked supplier so the real `ReprojectionCompleted` never lands during
            // the test — the executor is still running when we manually dispatch the stale event.
            var release = new CountDownLatch(1);
            Supplier<ClusterGenerationSnapshot> blocker = () -> awaitThenReturn(release,
                                                                                 ClusterGenerationSnapshot.empty(1L));
            harness.dispatch(new ReprojectionRequested(blocker, "epoch-test"));
            assertThat(harness.state()).isInstanceOf(HealthReconcilerState.LeadingReprojecting.class);
            var stateBefore = harness.state();

            var stale = Epoch.epoch(0L, 0L); // older than LEADER_EPOCH
            var projected = ClusterGenerationSnapshot.empty(1L).withDesiredCoreSize(99);
            harness.dispatch(new ReprojectionCompleted(stale, projected));
            assertThat(harness.state()).isSameAs(stateBefore);
            var lr = (HealthReconcilerState.LeadingReprojecting) harness.state();
            assertThat(lr.snapshot().desiredCoreSize()).isNotEqualTo(99);
            release.countDown();
        }

        @SuppressWarnings("SameParameterValue")
        private static <T> T awaitThenReturn(CountDownLatch latch, T value) {
            try {
                if (!latch.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("blocker supplier timed out");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return value;
        }
    }

    /// Null-op ClusterNode; the Context never commits through it during these FSM-only tests
    /// because no SignalReceived events are dispatched.
    private static final class NoopClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        @Override public NodeId self() { return SELF; }

        @Override public TopologyManager topologyManager() {
            throw new UnsupportedOperationException("not used in FSM tests");
        }

        @Override public Promise<Unit> start() { return Promise.success(Unit.unit()); }

        @Override public Promise<Unit> stop() { return Promise.success(Unit.unit()); }

        @SuppressWarnings({"unchecked", "rawtypes"})
        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            return (Promise) Promise.success(List.of());
        }
    }
}
