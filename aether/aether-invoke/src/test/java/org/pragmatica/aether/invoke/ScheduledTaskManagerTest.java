// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.invoke;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.ExecutionMode;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskStateKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskStateValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderManager;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.SharedScheduler;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduledTaskManagerTest {
    private ScheduledTaskRegistry registry;
    private StubSliceInvoker stubInvoker;
    private CopyOnWriteArrayList<InvocationRecord> invocations;
    private ScheduledTaskManager manager;
    private NodeId self;
    private Artifact artifact;
    private MethodName method;
    private CopyOnWriteArrayList<KVCommand<AetherKey>> stateWrites;
    private ConcurrentHashMap<ScheduledTaskStateKey, ScheduledTaskStateValue> stateMap;
    private TestLeaderManager leaderManager;

    record InvocationRecord(Artifact artifact, MethodName method, Object message) {}

    @BeforeEach
    void setUp() {
        registry = ScheduledTaskRegistry.scheduledTaskRegistry();
        invocations = new CopyOnWriteArrayList<>();
        stubInvoker = new StubSliceInvoker(invocations, Option.none());
        self = new NodeId("node-self");
        artifact = Artifact.artifact("org.example:my-slice:1.0.0").unwrap();
        method = MethodName.methodName("cleanup").unwrap();
        stateWrites = new CopyOnWriteArrayList<>();
        stateMap = new ConcurrentHashMap<>();
        leaderManager = new TestLeaderManager(self);

        Consumer<KVCommand<AetherKey>> stateWriter = command -> {
            stateWrites.add(command);
            if (command instanceof KVCommand.Put<AetherKey, ?> put
                && put.key() instanceof ScheduledTaskStateKey stateKey
                && put.value() instanceof ScheduledTaskStateValue stateValue) {
                stateMap.put(stateKey, stateValue);
            }
        };

        manager = ScheduledTaskManager.scheduledTaskManager(registry,
                                                            stubInvoker,
                                                            self,
                                                            stateWriter,
                                                            key -> Option.option(stateMap.get(key)),
                                                            leaderManager);
    }

    @AfterEach
    void tearDown() {
        manager.stop();
    }

    private void putTask(String configSection, Artifact artifact, MethodName method,
                         NodeId node, String interval, ExecutionMode executionMode) {
        var key = ScheduledTaskKey.scheduledTaskKey(configSection, artifact, method);
        var value = ScheduledTaskValue.intervalTask(node, interval, executionMode);
        var put = new KVCommand.Put<>(key, value);
        registry.onScheduledTaskPut(new ValuePut<>(put, Option.none()));
    }

    private void becomeLeader() {
        leaderManager.setLeader(true);
        manager.onLeaderChange(LeaderNotification.leaderChange(Option.some(self), true));
    }

    private void loseLeadership() {
        leaderManager.setLeader(false);
        manager.onLeaderChange(LeaderNotification.leaderChange(Option.none(), false));
    }

    private void establishQuorum() {
        manager.onQuorumStateChange(ClusterStateNotification.active());
    }

    private void loseQuorum() {
        manager.onQuorumStateChange(ClusterStateNotification.passive());
    }

    @Nested
    class ExecutionModeTests {
        @Test
        void allMode_startsOnNonLeaderNode() {
            putTask("cache", artifact, method, self, "30s", ExecutionMode.ALL);
            establishQuorum();

            // Not a leader, but ALL mode should start
            assertThat(manager.activeTimerCount()).isEqualTo(1);
        }

        @Test
        void singleMode_onlyStartsOnLeader() {
            putTask("cache", artifact, method, self, "30s", ExecutionMode.SINGLE);
            establishQuorum();

            // Not a leader — SINGLE mode should NOT start
            assertThat(manager.activeTimerCount()).isEqualTo(0);

            becomeLeader();

            assertThat(manager.activeTimerCount()).isEqualTo(1);
        }
    }

    @Nested
    class LeaderChange {
        @Test
        void onLeaderChange_becomesLeader_startsSingleModeTasks() {
            putTask("cache", artifact, method, self, "30s", ExecutionMode.SINGLE);
            establishQuorum();

            becomeLeader();

            assertThat(manager.activeTimerCount()).isEqualTo(1);
        }

        @Test
        void onLeaderChange_losesLeadership_cancelsSingleModeTimers() {
            putTask("cache", artifact, method, self, "30s", ExecutionMode.SINGLE);
            establishQuorum();
            becomeLeader();
            assertThat(manager.activeTimerCount()).isEqualTo(1);

            loseLeadership();

            assertThat(manager.activeTimerCount()).isEqualTo(0);
        }
    }

    @Nested
    class QuorumState {
        @Test
        void onQuorumStateChange_established_enablesExecution() {
            putTask("cache", artifact, method, self, "30s", ExecutionMode.ALL);

            establishQuorum();

            assertThat(manager.activeTimerCount()).isEqualTo(1);
        }

        @Test
        void onQuorumStateChange_disappeared_cancelsAllTimers() {
            putTask("cache", artifact, method, self, "30s", ExecutionMode.ALL);
            establishQuorum();
            assertThat(manager.activeTimerCount()).isEqualTo(1);

            loseQuorum();

            assertThat(manager.activeTimerCount()).isEqualTo(0);
        }
    }

    @Nested
    class TimerManagement {
        @Test
        void activeTimerCount_reflectsRunningTimers() {
            var method2 = MethodName.methodName("refresh").unwrap();
            putTask("cache", artifact, method, self, "30s", ExecutionMode.ALL);
            putTask("metrics", artifact, method2, self, "1m", ExecutionMode.ALL);
            establishQuorum();

            assertThat(manager.activeTimerCount()).isEqualTo(2);
        }

        @Test
        void stop_cancelsAllTimers() {
            putTask("cache", artifact, method, self, "30s", ExecutionMode.ALL);
            establishQuorum();
            assertThat(manager.activeTimerCount()).isEqualTo(1);

            manager.stop();

            assertThat(manager.activeTimerCount()).isEqualTo(0);
        }
    }

    @Nested
    class RegistryChange {
        @Test
        void registryChange_taskAdded_startsTimer() {
            establishQuorum();

            putTask("cache", artifact, method, self, "30s", ExecutionMode.ALL);

            assertThat(manager.activeTimerCount()).isEqualTo(1);
        }

        @Test
        void registryChange_taskRemoved_cancelsTimer() {
            putTask("cache", artifact, method, self, "30s", ExecutionMode.ALL);
            establishQuorum();
            assertThat(manager.activeTimerCount()).isEqualTo(1);

            var key = ScheduledTaskKey.scheduledTaskKey("cache", artifact, method);
            var remove = new KVCommand.Remove<ScheduledTaskKey>(key);
            registry.onScheduledTaskRemove(new org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove<>(remove, Option.none()));

            assertThat(manager.activeTimerCount()).isEqualTo(0);
        }
    }

    @Nested
    class IntervalParserTests {
        @Test
        void parse_validFormats_parsed() {
            assertParsedInterval("30s", TimeSpan.timeSpan(30).seconds());
            assertParsedInterval("5m", TimeSpan.timeSpan(5).minutes());
            assertParsedInterval("1h", TimeSpan.timeSpan(1).hours());
            assertParsedInterval("2d", TimeSpan.timeSpan(2).days());
        }

        @Test
        void parse_weeks_parsed() {
            assertParsedInterval("1w", TimeSpan.timeSpan(7).days());
            assertParsedInterval("2w", TimeSpan.timeSpan(14).days());
        }

        @Test
        void parse_invalidFormats_rejected() {
            assertParseFailure("");
            assertParseFailure("x");
            assertParseFailure("abc");
            assertParseFailure("30x");
            assertParseFailure("30");
        }

        private void assertParsedInterval(String input, TimeSpan expected) {
            var result = ScheduledTaskManager.IntervalParser.parse(input);
            result.onFailure(cause -> org.junit.jupiter.api.Assertions.fail("Expected success for '" + input + "': " + cause.message()))
                  .onSuccess(ts -> assertThat(ts.nanos()).isEqualTo(expected.nanos()));
        }

        private void assertParseFailure(String input) {
            var result = ScheduledTaskManager.IntervalParser.parse(input);
            result.onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure for '" + input + "'"));
        }
    }

    @Nested
    class PauseResume {
        @Test
        void pausedTask_preventsTimerCreation() {
            putPausedTask("cache", artifact, method, self, "30s", ExecutionMode.ALL);
            establishQuorum();

            assertThat(manager.activeTimerCount()).isEqualTo(0);
        }

        @Test
        void resumeTask_restartsTimer() {
            putPausedTask("cache", artifact, method, self, "30s", ExecutionMode.ALL);
            establishQuorum();
            assertThat(manager.activeTimerCount()).isEqualTo(0);

            // Resume by putting non-paused task
            putTask("cache", artifact, method, self, "30s", ExecutionMode.ALL);

            assertThat(manager.activeTimerCount()).isEqualTo(1);
        }
    }

    @Nested
    class CronScheduling {
        @Test
        void cronTask_registersActiveTimer() {
            putCronTask("cleanup", artifact, method, self, "0 * * * *", ExecutionMode.ALL);
            establishQuorum();

            assertThat(manager.activeTimerCount()).isEqualTo(1);
        }

        @Test
        void cronTask_invalidCron_skipsTimer() {
            putCronTask("cleanup", artifact, method, self, "invalid cron", ExecutionMode.ALL);
            establishQuorum();

            assertThat(manager.activeTimerCount()).isEqualTo(0);
        }

        @Test
        void cronTask_cancelledOnQuorumLoss() {
            putCronTask("cleanup", artifact, method, self, "0 * * * *", ExecutionMode.ALL);
            establishQuorum();
            assertThat(manager.activeTimerCount()).isEqualTo(1);

            loseQuorum();

            assertThat(manager.activeTimerCount()).isEqualTo(0);
        }
    }

    @Nested
    class StateTracking {
        @Test
        void stateWriter_wiredCorrectly() {
            assertThat(manager).isNotNull();
            assertThat(stateWrites).isEmpty();
        }
    }

    /// Exercises the REAL fixed-rate timer through [SharedScheduler] end to end — no
    /// internal `TaskOps` hook is used. `"1s"` is [IntervalParser]'s minimum granularity, so
    /// these tests run on real wall-clock ticks; [#awaitTrue] polls the SAME `stateMap` that
    /// production code writes to, giving a genuine happens-before edge (`ConcurrentHashMap`'s
    /// documented put-then-get guarantee) instead of racing on an unrelated field.
    @Nested
    class FireBehavior {
        @Test
        void fixedRate_twoFires_accumulatesTotalExecutions() {
            putTask("cache", artifact, method, self, "1s", ExecutionMode.ALL);
            establishQuorum();

            var key = ScheduledTaskStateKey.scheduledTaskStateKey("cache", artifact, method);
            awaitTrue(() -> stateFor(key).map(v -> v.totalExecutions() >= 2).or(false), 4000);
            manager.stop(); // freeze — no further tick can land between detection and assertion

            var state = stateFor(key).unwrap();
            assertThat(state.totalExecutions()).isEqualTo(2);
            assertThat(state.consecutiveFailures()).isZero();
            assertThat(state.nextFireAt()).isGreaterThan(0L);
        }

        @Test
        void fixedRate_failThenSucceed_consecutiveFailuresTracksThenResets() {
            Cause boom = () -> "boom";
            stubInvoker.setFailureCause(Option.some(boom));

            putTask("cache", artifact, method, self, "1s", ExecutionMode.ALL);
            establishQuorum();

            var key = ScheduledTaskStateKey.scheduledTaskStateKey("cache", artifact, method);
            awaitTrue(() -> stateFor(key).map(v -> v.consecutiveFailures() >= 1).or(false), 4000);

            var afterFailure = stateFor(key).unwrap();
            assertThat(afterFailure.consecutiveFailures()).isEqualTo(1);
            assertThat(afterFailure.totalExecutions()).isZero();
            assertThat(afterFailure.lastFailureMessage()).isEqualTo("boom");

            stubInvoker.setFailureCause(Option.none());

            awaitTrue(() -> stateFor(key).map(v -> v.consecutiveFailures() == 0).or(false), 4000);
            manager.stop();

            var afterSuccess = stateFor(key).unwrap();
            assertThat(afterSuccess.consecutiveFailures()).isZero();
            assertThat(afterSuccess.totalExecutions()).isEqualTo(1);
        }

        @Test
        void fixedRate_overlappingFire_recordsSkipInsteadOfDoubleExecution() {
            var gate = Promise.<Unit>promise();
            stubInvoker.holdNextInvocation(gate);

            putTask("cache", artifact, method, self, "1s", ExecutionMode.ALL);
            establishQuorum();

            // First tick (~1s) blocks on `gate`: invoked, but never settles until released —
            // this keeps ctx.inFlight claimed for the key past the second tick.
            awaitTrue(() -> invocations.size() >= 1, 3000);

            var key = ScheduledTaskStateKey.scheduledTaskStateKey("cache", artifact, method);

            // Second tick (~2s) must find the key still in-flight: recorded as a skip, no
            // second invoke.
            awaitTrue(() -> stateFor(key).map(v -> v.skippedOverlaps() >= 1).or(false), 3000);

            assertThat(invocations).as("overlap must be skipped, not executed").hasSize(1);
            assertThat(stateFor(key).unwrap().skippedOverlaps()).isEqualTo(1);
            assertThat(stateFor(key).unwrap().totalExecutions())
                    .as("the blocked invocation has not settled yet")
                    .isZero();

            gate.succeed(Unit.unit());

            awaitTrue(() -> stateFor(key).map(v -> v.totalExecutions() >= 1).or(false), 3000);
            manager.stop();

            var finalState = stateFor(key).unwrap();
            assertThat(finalState.totalExecutions()).isEqualTo(1);
            assertThat(finalState.skippedOverlaps()).isEqualTo(1);
            assertThat(invocations).hasSize(1);
        }

        private Option<ScheduledTaskStateValue> stateFor(ScheduledTaskStateKey key) {
            return Option.option(stateMap.get(key));
        }

        private void awaitTrue(BooleanSupplier condition, long timeoutMs) {
            var deadline = System.currentTimeMillis() + timeoutMs;

            while (!condition.getAsBoolean()) {
                if (System.currentTimeMillis() > deadline) {
                    throw new AssertionError("Condition not satisfied within " + timeoutMs + "ms");
                }

                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private void putPausedTask(String configSection, Artifact artifact, MethodName method,
                               NodeId node, String interval, ExecutionMode executionMode) {
        var key = ScheduledTaskKey.scheduledTaskKey(configSection, artifact, method);
        var value = ScheduledTaskValue.intervalTask(node, interval, executionMode).withPaused(true);
        var put = new KVCommand.Put<>(key, value);
        registry.onScheduledTaskPut(new ValuePut<>(put, Option.none()));
    }

    private void putCronTask(String configSection, Artifact artifact, MethodName method,
                             NodeId node, String cron, ExecutionMode executionMode) {
        var key = ScheduledTaskKey.scheduledTaskKey(configSection, artifact, method);
        var value = ScheduledTaskValue.cronTask(node, cron, executionMode);
        var put = new KVCommand.Put<>(key, value);
        registry.onScheduledTaskPut(new ValuePut<>(put, Option.none()));
    }

    /// Minimal stub implementing only the invoke methods used by ScheduledTaskManager.
    /// `failureCause` is mutable (fail-then-succeed sequencing) and `pendingGate` is a
    /// one-shot hook: when set, the NEXT `invoke()` call returns that exact (unresolved)
    /// `Promise` instead of an already-resolved one, letting a test hold an invocation
    /// "in flight" across a subsequent timer tick to exercise overlap detection.
    static final class StubSliceInvoker implements SliceInvoker {
        private final CopyOnWriteArrayList<InvocationRecord> invocations;
        private final AtomicReference<Option<Cause>> failureCause;
        private final AtomicReference<Promise<Unit>> pendingGate = new AtomicReference<>();

        public StubSliceInvoker(CopyOnWriteArrayList<InvocationRecord> invocations, Option<Cause> failureCause) {
            this.invocations = invocations;
            this.failureCause = new AtomicReference<>(failureCause);
        }

        void setFailureCause(Option<Cause> cause) {
            failureCause.set(cause);
        }

        void holdNextInvocation(Promise<Unit> gate) {
            pendingGate.set(gate);
        }

        @Override
        public Promise<Unit> invoke(Artifact slice, MethodName method, Object request) {
            invocations.add(new InvocationRecord(slice, method, request));

            var gate = pendingGate.getAndSet(null);

            if (gate != null) {
                return gate;
            }

            return failureCause.get().fold(Promise::unitPromise, Cause::promise);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <R> Promise<R> invoke(Artifact slice, MethodName method, Object request, TypeToken<R> responseType) {
            invocations.add(new InvocationRecord(slice, method, request));
            return failureCause.get().fold(() -> (Promise<R>) Promise.unitPromise(), Cause::promise);
        }

        // --- Unused methods — minimal stubs for compilation ---

        @Override
        public Result<Unit> verifyEndpointExists(Artifact artifact, MethodName method) {
            return Result.unitResult();
        }

        @Override
        public <R> Promise<R> invokeWithRetry(Artifact slice, MethodName method, Object request,
                                               TypeToken<R> responseType, int maxRetries) {
            return invoke(slice, method, request, responseType);
        }

        @Override
        public <R> Promise<R> invokeLocal(Artifact slice, MethodName method, Object request,
                                           TypeToken<R> responseType) {
            return invoke(slice, method, request, responseType);
        }

        @Override
        public void onInvokeResponse(org.pragmatica.aether.invoke.InvocationMessage.InvokeResponse response) {}

        @Override
        public void onNodeRemoved(org.pragmatica.consensus.topology.MembershipDecision.NodeRemoved event) {}

        @Override
        public void onNodeDecommissioned(org.pragmatica.consensus.topology.MembershipDecision.NodeDecommissioned event) {}

        @Override
        public void onSelfShutdown(org.pragmatica.consensus.topology.TransportObservation.SelfShutdown event) {}

        @Override
        public Promise<Unit> stop() {
            return Promise.unitPromise();
        }

        @Override
        public int pendingCount() {
            return 0;
        }

        @Override
        public Unit setFailureListener(SliceFailureListener listener) {
            return Unit.unit();
        }

        @Override
        public Unit registerAffinityResolver(Artifact artifact, MethodName method,
                                              CacheAffinityResolver resolver) {
            return Unit.unit();
        }

        @Override
        public Unit unregisterAffinityResolver(Artifact artifact, MethodName method) {
            return Unit.unit();
        }
    }

    /// Controllable LeaderManager stub for SSOT testing.
    static final class TestLeaderManager implements LeaderManager {
        private final NodeId self;
        private volatile boolean leader = false;

        TestLeaderManager(NodeId self) {
            this.self = self;
        }

        void setLeader(boolean value) {
            this.leader = value;
        }

        @Override public Option<NodeId> leader() {
            return leader ? Option.some(self) : Option.none();
        }

        @Override public boolean isLeader() {
            return leader;
        }

        @Override public Option<Long> currentLeaderEpoch() {
            return Option.none();
        }

        @Override public void onLeaderCommitted(NodeId leader) {}
        @Override public void triggerElection() {}
        @Override public void stop() {}
        @Override public void peerJoined(org.pragmatica.consensus.topology.TransportObservation.PeerJoined p) {}
        @Override public void peerDisconnected(org.pragmatica.consensus.topology.TransportObservation.PeerDisconnected p) {}
        @Override public void peerObservedFaulty(org.pragmatica.consensus.topology.TransportObservation.PeerObservedFaulty p) {}
        @Override public void peerReconnected(org.pragmatica.consensus.topology.TransportObservation.PeerReconnected p) {}
        @Override public void selfShutdown(org.pragmatica.consensus.topology.TransportObservation.SelfShutdown s) {}
        @Override public void watchClusterState(ClusterStateNotification q) {}
    }
}
