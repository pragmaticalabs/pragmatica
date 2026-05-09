// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.controller.fsm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.controller.ClusterController;
import org.pragmatica.aether.controller.ClusterController.ControlDecisions;
import org.pragmatica.aether.controller.ControllerConfig;
import org.pragmatica.aether.controller.ScalingEvent;
import org.pragmatica.aether.controller.fsm.ControlLoopEvents.Activate;
import org.pragmatica.aether.controller.fsm.ControlLoopEvents.ActivationTimeReached;
import org.pragmatica.aether.controller.fsm.ControlLoopEvents.CooldownExpired;
import org.pragmatica.aether.controller.fsm.ControlLoopEvents.CooldownRequested;
import org.pragmatica.aether.controller.fsm.ControlLoopEvents.Deactivate;
import org.pragmatica.aether.metrics.ClusterSyncCollector;
import org.pragmatica.aether.metrics.ClusterSyncCollector.MetricsSnapshot;
import org.pragmatica.aether.metrics.ClusterSyncPongSignalFan;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPing;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPong;
import org.pragmatica.cluster.metrics.CommunityReport;
import org.pragmatica.cluster.metrics.PeerObservationBuffer;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmTestHarness;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/// Single-threaded and CAS-contention tests for the [`ControlLoop`] FSM. All tests use stubbed
/// collaborators and drive transitions via direct event dispatch — no wall-clock waits for timer
/// fires.
class ControlLoopFsmTest {

    private static final NodeId SELF = NodeId.nodeId("ctl-loop-test").unwrap();

    /// Helper: build an `FsmTestHarness` wrapping a fresh `ControlLoopContext` with stubbed
    /// collaborators. Warm-up / interval / cooldown values are zero / tiny so any real timer fire
    /// would be cheap, but tests never rely on them — they dispatch events directly.
    private static FsmTestHarness<ControlLoopState, ClusterFsmEvent> harness() {
        var ctxHolder = new AtomicReference<ControlLoopContext>();
        var harness = FsmTestHarness.<ControlLoopState, ClusterFsmEvent>harness(
                "control-loop-test",
                fsm -> buildContext(ctxHolder, fsm));
        return harness;
    }

    private static ControlLoopState buildContext(AtomicReference<ControlLoopContext> ctxHolder,
                                                 Fsm<ControlLoopState, ClusterFsmEvent> fsm) {
        Consumer<ScalingEvent> sink = _ -> {};
        var ctx = new ControlLoopContext(fsm,
                                         SELF,
                                         StubController.INSTANCE,
                                         new StubMetricsCollector(),
                                         Option.none(),
                                         new StubClusterNode(),
                                         new StubKVStore(),
                                         TimeSpan.timeSpan(60_000).millis(),
                                         ControllerConfig.DEFAULT
                                                         .withScalingConfig(ControllerConfig.DEFAULT.scalingConfig()),
                                         sink);
        ctxHolder.set(ctx);
        return ctx.dormant();
    }

    @Nested
    class HappyPath {
        @Test
        void dormant_activate_movesToWarmup() {
            var h = harness();

            h.dispatch(new Activate());

            assertThat(h.state()).isInstanceOf(ControlLoopState.Warmup.class);
            assertThat(h.transitions()).hasSize(1);
        }

        @Test
        void fullLifecycle_dormantWarmupEvaluatingCooldownEvaluatingDormant() {
            var h = harness();

            h.dispatch(new Activate());
            assertThat(h.state()).isInstanceOf(ControlLoopState.Warmup.class);

            h.dispatch(new ActivationTimeReached());
            assertThat(h.state()).isInstanceOf(ControlLoopState.Evaluating.class);

            var artifact = Artifact.artifact("org.test:demo:1.0.0").unwrap();
            h.dispatch(new CooldownRequested(artifact, System.currentTimeMillis()));
            assertThat(h.state()).isInstanceOf(ControlLoopState.Cooldown.class);

            // Populate a cooldown for this artifact in the context, then expire it so the state
            // handler observes "all cooldowns expired" on CooldownExpired.
            var cooldownState = (ControlLoopState.Cooldown) h.state();
            cooldownState.ctx().cleanupExpiredCooldowns(System.currentTimeMillis() + 10 * 60_000L);

            h.dispatch(new CooldownExpired());
            assertThat(h.state()).isInstanceOf(ControlLoopState.Evaluating.class);

            h.dispatch(new Deactivate());
            assertThat(h.state()).isInstanceOf(ControlLoopState.Dormant.class);
        }

        @Test
        void leaderChange_localIsLeader_movesDormantToWarmup() {
            var h = harness();

            h.dispatch(new ClusterFsmEvent.LeaderChange(Option.some(SELF), true));

            assertThat(h.state()).isInstanceOf(ControlLoopState.Warmup.class);
        }

        @Test
        void shutdown_fromAnyActiveState_movesToStopped() {
            var h = harness();
            h.dispatch(new Activate());
            h.dispatch(new ActivationTimeReached());
            assertThat(h.state()).isInstanceOf(ControlLoopState.Evaluating.class);

            h.dispatch(new ClusterFsmEvent.Shutdown());

            assertThat(h.state()).isInstanceOf(ControlLoopState.Stopped.class);
        }
    }

    @Nested
    class IgnoredEvents {
        @Test
        void cooldownExpired_inDormant_isIgnored() {
            var h = harness();

            h.dispatch(new CooldownExpired());

            assertThat(h.state()).isInstanceOf(ControlLoopState.Dormant.class);
            assertThat(h.transitions()).isEmpty();
            assertThat(h.ignored()).hasSize(1);
            assertThat(h.ignored().getFirst().state()).isInstanceOf(ControlLoopState.Dormant.class);
            assertThat(h.ignored().getFirst().event()).isInstanceOf(CooldownExpired.class);
        }

        @Test
        void activationTimeReached_inDormant_isIgnored() {
            var h = harness();

            h.dispatch(new ActivationTimeReached());

            assertThat(h.state()).isInstanceOf(ControlLoopState.Dormant.class);
            assertThat(h.transitions()).isEmpty();
            assertThat(h.ignored()).hasSize(1);
        }

        @Test
        void stopped_isTerminal_ignoresEverything() {
            var h = harness();
            h.dispatch(new ClusterFsmEvent.Shutdown());
            assertThat(h.state()).isInstanceOf(ControlLoopState.Stopped.class);

            h.dispatch(new Activate());
            h.dispatch(new ActivationTimeReached());
            h.dispatch(new ClusterFsmEvent.QuorumEstablished());

            assertThat(h.state()).isInstanceOf(ControlLoopState.Stopped.class);
            assertThat(h.transitions()).hasSize(1); // only the Shutdown transition
        }
    }

    @Nested
    class HandledObservability {
        @Test
        void cooldownRequestedInCooldown_recordedAsHandledNotIgnored() {
            var h = harness();
            h.dispatch(new Activate());
            h.dispatch(new ActivationTimeReached());
            var artifact = Artifact.artifact("org.test:demo:1.0.0").unwrap();
            h.dispatch(new CooldownRequested(artifact, System.currentTimeMillis()));
            assertThat(h.state()).isInstanceOf(ControlLoopState.Cooldown.class);

            // Re-arm cooldown timer — arm performs a side effect (rearmExpiryTick) without
            // changing state. MUST be observed as `handled` for dashboards to count it.
            h.dispatch(new CooldownRequested(artifact, System.currentTimeMillis()));

            var cooldownHandled = h.handled().stream()
                                   .filter(rec -> rec.event() instanceof CooldownRequested
                                                  && rec.state() instanceof ControlLoopState.Cooldown)
                                   .count();
            assertThat(cooldownHandled).isEqualTo(1L);
            // No ignored CooldownRequested in Cooldown.
            var cooldownIgnored = h.ignored().stream()
                                   .filter(rec -> rec.event() instanceof CooldownRequested
                                                  && rec.state() instanceof ControlLoopState.Cooldown)
                                   .count();
            assertThat(cooldownIgnored).isZero();
        }

    }

    @Nested
    class CasContention {
        @Test
        void concurrentActivate_fromDormant_exactlyOneWins() throws InterruptedException {
            var h = harness();

            var events = List.<ClusterFsmEvent>of(new Activate(), new Activate(),
                                                  new Activate(), new Activate(),
                                                  new Activate(), new Activate(),
                                                  new Activate(), new Activate());
            h.dispatchConcurrently(events);

            assertThat(h.state()).isInstanceOf(ControlLoopState.Warmup.class);
            // Exactly ONE CAS winner: exactly one transition Dormant → Warmup.
            assertThat(h.transitions()).hasSize(1);
            // Each non-winner's Activate is either forwarded post-CAS-loss (landing in Warmup
            // which ignores it) or dispatched directly into the post-winner Warmup (same result).
            // Either way each non-winner contributes one Ignored record in Warmup.
            assertThat(h.ignored()).hasSize(events.size() - 1);
            h.ignored().forEach(entry -> {
                assertThat(entry.state()).isInstanceOf(ControlLoopState.Warmup.class);
                assertThat(entry.event()).isInstanceOf(Activate.class);
            });
            // CAS-loss count varies with interleaving (0..threadCount-1); each CAS-loss is
            // observed in addition to the forward-reaching Ignored.
            assertThat(h.casLosses().size()).isBetween(0, events.size() - 1);
        }
    }

    // --- Minimal stub collaborators ---

    enum StubController implements ClusterController {
        INSTANCE;

        @Override
        public Promise<ControlDecisions> evaluate(ClusterController.ControlContext context) {
            return Promise.success(new ControlDecisions(List.of()));
        }
    }

    static final class StubClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        @Override public NodeId self() { return SELF; }
        @Override public TopologyManager topologyManager() { throw new UnsupportedOperationException("unused"); }
        @Override public Promise<Unit> start() { return Promise.unitPromise(); }
        @Override public Promise<Unit> stop() { return Promise.unitPromise(); }

        @Override
        @SuppressWarnings("unchecked")
        public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            return (Promise<List<R>>) (Promise<?>) Promise.success(List.of());
        }
    }

    static final class StubKVStore extends KVStore<AetherKey, AetherValue> {
        StubKVStore() {
            super(org.pragmatica.messaging.MessageRouter.mutable(),
                  StubSerializer.INSTANCE,
                  StubDeserializer.INSTANCE);
        }
    }

    enum StubSerializer implements org.pragmatica.serialization.Serializer {
        INSTANCE;

        @Override
        public <T> void write(io.netty.buffer.ByteBuf byteBuf, T object) {
            throw new UnsupportedOperationException("unused");
        }
    }

    enum StubDeserializer implements org.pragmatica.serialization.Deserializer {
        INSTANCE;

        @Override
        public <T> T read(io.netty.buffer.ByteBuf byteBuf) {
            throw new UnsupportedOperationException("unused");
        }
    }

    static final class StubMetricsCollector implements ClusterSyncCollector {
        @Override public Map<String, Double> collectLocal() { return Map.of(); }
        @Override public void recordCall(MethodName method, long durationMs) {}
        @Override public void recordCustom(String name, double value) {}
        @Override public void setInvocationMetricsProvider(InvocationMetricsCollector provider) {}
        @Override public Map<NodeId, Map<String, Double>> allMetrics() { return Map.of(); }
        @Override public Map<String, Double> metricsFor(NodeId nodeId) { return Map.of(); }
        @Override public Map<NodeId, List<MetricsSnapshot>> historicalMetrics() { return Map.of(); }
        @Override public void removeNode(NodeId nodeId) {}
        @Override public void onMembershipDecision(MembershipDecision decision) {}
        @Override public void onClusterSyncPing(ClusterSyncPing ping) {}
        @Override public void onClusterSyncPong(ClusterSyncPong pong) {}
        @Override public long observedRabiaTerm() { return 0L; }
        @Override public Epoch observedEpoch() { throw new UnsupportedOperationException("unused"); }
        @Override public String currentLifecycleState() { return "test"; }
        @Override public List<CommunityReport> collectCommunityReports() { return List.of(); }
        @Override public void setLifecycleStateSupplier(Supplier<String> supplier) {}
        @Override public void setCommunityReportSupplier(Supplier<List<CommunityReport>> supplier) {}
        @Override public void addPongListener(Consumer<ClusterSyncPong> listener) {}
        @Override public void setPongSignalFan(ClusterSyncPongSignalFan fan) {}
        @Override public void setPeerObservationBuffer(PeerObservationBuffer buffer) {}
    }
}
