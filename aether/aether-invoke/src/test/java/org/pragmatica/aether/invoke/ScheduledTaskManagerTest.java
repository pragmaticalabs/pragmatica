package org.pragmatica.aether.invoke;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.consensus.topology.QuorumStateNotification;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.type.TypeToken;

import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduledTaskManagerTest {
    private ScheduledTaskRegistry registry;
    private StubSliceInvoker stubInvoker;
    private ScheduledTaskManager manager;
    private NodeId self;
    private Artifact artifact;
    private MethodName method;

    record InvocationRecord(Artifact artifact, MethodName method, Object message) {}

    @BeforeEach
    void setUp() {
        registry = ScheduledTaskRegistry.scheduledTaskRegistry();
        stubInvoker = new StubSliceInvoker(new CopyOnWriteArrayList<>(), Option.none());
        self = new NodeId("node-self");
        artifact = Artifact.artifact("org.example:my-slice:1.0.0").unwrap();
        method = MethodName.methodName("cleanup").unwrap();
        manager = ScheduledTaskManager.scheduledTaskManager(registry, stubInvoker, self);
    }

    @AfterEach
    void tearDown() {
        manager.stop();
    }

    private void putTask(String configSection, Artifact artifact, MethodName method,
                         NodeId node, String interval, boolean leaderOnly) {
        var key = ScheduledTaskKey.scheduledTaskKey(configSection, artifact, method);
        var value = ScheduledTaskValue.intervalTask(node, interval, leaderOnly);
        var put = new KVCommand.Put<>(key, value);
        registry.onScheduledTaskPut(new ValuePut<>(put, Option.none()));
    }

    private void becomeLeader() {
        manager.onLeaderChange(LeaderNotification.leaderChange(Option.some(self), true));
    }

    private void loseLeadership() {
        manager.onLeaderChange(LeaderNotification.leaderChange(Option.none(), false));
    }

    private void establishQuorum() {
        manager.onQuorumStateChange(QuorumStateNotification.established());
    }

    private void loseQuorum() {
        manager.onQuorumStateChange(QuorumStateNotification.disappeared());
    }

    @Nested
    class LeaderChange {
        @Test
        void onLeaderChange_becomesLeader_startsLeaderOnlyTasks() {
            putTask("cache", artifact, method, self, "30s", true);
            establishQuorum();

            becomeLeader();

            assertThat(manager.activeTimerCount()).isEqualTo(1);
        }

        @Test
        void onLeaderChange_losesLeadership_cancelsLeaderOnlyTimers() {
            putTask("cache", artifact, method, self, "30s", true);
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
            putTask("cache", artifact, method, self, "30s", false);

            establishQuorum();

            assertThat(manager.activeTimerCount()).isEqualTo(1);
        }

        @Test
        void onQuorumStateChange_disappeared_cancelsAllTimers() {
            putTask("cache", artifact, method, self, "30s", false);
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
            putTask("cache", artifact, method, self, "30s", false);
            putTask("metrics", artifact, method2, self, "1m", false);
            establishQuorum();

            assertThat(manager.activeTimerCount()).isEqualTo(2);
        }

        @Test
        void stop_cancelsAllTimers() {
            putTask("cache", artifact, method, self, "30s", false);
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

            putTask("cache", artifact, method, self, "30s", false);

            assertThat(manager.activeTimerCount()).isEqualTo(1);
        }

        @Test
        void registryChange_taskRemoved_cancelsTimer() {
            putTask("cache", artifact, method, self, "30s", false);
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

    /// Minimal stub implementing only the invoke methods used by ScheduledTaskManager.
    private static final class StubSliceInvoker implements SliceInvoker {
        private final CopyOnWriteArrayList<InvocationRecord> invocations;
        private final Option<Cause> failureCause;

        StubSliceInvoker(CopyOnWriteArrayList<InvocationRecord> invocations, Option<Cause> failureCause) {
            this.invocations = invocations;
            this.failureCause = failureCause;
        }

        @Override
        public Promise<Unit> invoke(Artifact slice, MethodName method, Object request) {
            invocations.add(new InvocationRecord(slice, method, request));
            return failureCause.fold(Promise::unitPromise, Cause::promise);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <R> Promise<R> invoke(Artifact slice, MethodName method, Object request, TypeToken<R> responseType) {
            invocations.add(new InvocationRecord(slice, method, request));
            return failureCause.fold(() -> (Promise<R>) Promise.unitPromise(), Cause::promise);
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
        public void onNodeRemoved(org.pragmatica.consensus.topology.TopologyChangeNotification.NodeRemoved event) {}

        @Override
        public void onNodeDown(org.pragmatica.consensus.topology.TopologyChangeNotification.NodeDown event) {}

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
}
