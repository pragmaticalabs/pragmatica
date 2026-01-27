package org.pragmatica.aether.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.config.RollbackConfig;
import org.pragmatica.aether.invoke.SliceFailureEvent;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.BlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.PreviousVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.BlueprintValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class RollbackManagerTest {
    private static final NodeId SELF = NodeId.nodeId("node-1").unwrap();
    private static final NodeId LEADER = NodeId.nodeId("node-1").unwrap();

    private RollbackConfig config;
    private TestClusterNode clusterNode;
    private TestKVStore kvStore;
    private RollbackManager rollbackManager;

    @BeforeEach
    void setup() {
        config = RollbackConfig.defaults();
        clusterNode = new TestClusterNode(SELF);
        kvStore = new TestKVStore();
        rollbackManager = RollbackManager.rollbackManager(SELF, config, clusterNode, kvStore);
    }

    @Nested
    class DisabledRollback {
        @Test
        void disabled_ignoresAllEvents() {
            var disabled = RollbackManager.disabled();
            var artifact = Artifact.artifact("org.example:test:1.0.0").unwrap();

            disabled.onAllInstancesFailed(createFailureEvent(artifact));

            assertThat(disabled.getStats(artifact.base()).isEmpty()).isTrue();
        }
    }

    @Nested
    class LeaderAwareness {
        @Test
        void nonLeader_doesNotTrackVersionChanges() {
            var v1 = Artifact.artifact("org.example:test:1.0.0").unwrap();
            var v2 = Artifact.artifact("org.example:test:2.0.0").unwrap();

            simulateBlueprintChange(v1);
            simulateBlueprintChange(v2);

            assertThat(clusterNode.appliedCommands).isEmpty();
        }

        @Test
        void leader_tracksVersionChanges() {
            rollbackManager.onLeaderChange(new LeaderChange(Option.some(LEADER), true));
            var v1 = Artifact.artifact("org.example:test:1.0.0").unwrap();
            var v2 = Artifact.artifact("org.example:test:2.0.0").unwrap();

            simulateBlueprintChange(v1);
            simulateBlueprintChange(v2);

            assertThat(clusterNode.appliedCommands).hasSize(1);
            var command = clusterNode.appliedCommands.getFirst();
            assertThat(command).isInstanceOf(KVCommand.Put.class);
            @SuppressWarnings("unchecked")
            var put = (KVCommand.Put<AetherKey, AetherValue>) command;
            assertThat(put.key()).isInstanceOf(PreviousVersionKey.class);
        }
    }

    @Nested
    class RollbackTriggering {
        @BeforeEach
        void becomeLeader() {
            rollbackManager.onLeaderChange(new LeaderChange(Option.some(LEADER), true));
        }

        @Test
        void noPreviousVersion_doesNotRollback() {
            var artifact = Artifact.artifact("org.example:test:1.0.0").unwrap();

            simulateBlueprintChange(artifact);
            rollbackManager.onAllInstancesFailed(createFailureEvent(artifact));

            assertThat(clusterNode.appliedCommands).isEmpty();
        }

        @Test
        void withPreviousVersion_triggersRollback() {
            var v1 = Artifact.artifact("org.example:test:1.0.0").unwrap();
            var v2 = Artifact.artifact("org.example:test:2.0.0").unwrap();

            simulateBlueprintChange(v1);
            simulateBlueprintChange(v2);
            clusterNode.appliedCommands.clear();

            rollbackManager.onAllInstancesFailed(createFailureEvent(v2));

            assertThat(clusterNode.appliedCommands).hasSize(1);
            var command = clusterNode.appliedCommands.getFirst();
            assertThat(command).isInstanceOf(KVCommand.Put.class);
            @SuppressWarnings("unchecked")
            var put = (KVCommand.Put<AetherKey, AetherValue>) command;
            assertThat(put.key()).isInstanceOf(BlueprintKey.class);
            var blueprintKey = (BlueprintKey) put.key();
            assertThat(blueprintKey.artifact().version().withQualifier()).isEqualTo("1.0.0");
        }
    }

    @Nested
    class StatsTracking {
        @BeforeEach
        void becomeLeader() {
            rollbackManager.onLeaderChange(new LeaderChange(Option.some(LEADER), true));
        }

        @Test
        void afterRollback_statsAreUpdated() {
            var v1 = Artifact.artifact("org.example:test:1.0.0").unwrap();
            var v2 = Artifact.artifact("org.example:test:2.0.0").unwrap();

            simulateBlueprintChange(v1);
            simulateBlueprintChange(v2);
            clusterNode.appliedCommands.clear();

            rollbackManager.onAllInstancesFailed(createFailureEvent(v2));

            var stats = rollbackManager.getStats(v2.base());
            assertThat(stats.isPresent()).isTrue();

            stats.onPresent(s -> {
                assertThat(s.rollbackCount()).isEqualTo(1);
                assertThat(s.lastRolledBackFrom().isPresent()).isTrue();
                assertThat(s.lastRolledBackFrom().unwrap().withQualifier()).isEqualTo("2.0.0");
                assertThat(s.lastRolledBackTo().isPresent()).isTrue();
                assertThat(s.lastRolledBackTo().unwrap().withQualifier()).isEqualTo("1.0.0");
            });
        }

        @Test
        void resetRollbackCount_clearsStats() {
            var v1 = Artifact.artifact("org.example:test:1.0.0").unwrap();
            var v2 = Artifact.artifact("org.example:test:2.0.0").unwrap();

            simulateBlueprintChange(v1);
            simulateBlueprintChange(v2);
            clusterNode.appliedCommands.clear();

            rollbackManager.onAllInstancesFailed(createFailureEvent(v2));
            rollbackManager.resetRollbackCount(v2.base());

            var stats = rollbackManager.getStats(v2.base());
            assertThat(stats.isPresent()).isTrue();
            stats.onPresent(s -> assertThat(s.rollbackCount()).isZero());
        }
    }

    // Helper methods
    private void simulateBlueprintChange(Artifact artifact) {
        var key = new BlueprintKey(artifact);
        var value = new BlueprintValue(1);
        var put = new KVCommand.Put<AetherKey, AetherValue>(key, value);
        var notification = new ValuePut<>(put, Option.none());
        rollbackManager.onValuePut(notification);
    }

    private SliceFailureEvent.AllInstancesFailed createFailureEvent(Artifact artifact) {
        return SliceFailureEvent.AllInstancesFailed.allInstancesFailed(
            "request-123",
            artifact,
            MethodName.methodName("doSomething").unwrap(),
            Causes.cause("Test failure"),
            List.of(NodeId.nodeId("node-2").unwrap(), NodeId.nodeId("node-3").unwrap())
        );
    }

    // Test doubles
    static class TestClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private final NodeId self;
        final List<KVCommand<AetherKey>> appliedCommands = new CopyOnWriteArrayList<>();

        TestClusterNode(NodeId self) {
            this.self = self;
        }

        @Override
        public NodeId self() {
            return self;
        }

        @Override
        public Promise<Unit> start() {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.unitPromise();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            appliedCommands.addAll(commands);
            return Promise.success((List<R>) commands.stream().map(_ -> Unit.unit()).toList());
        }
    }

    static class TestKVStore extends KVStore<AetherKey, AetherValue> {
        private final ConcurrentHashMap<AetherKey, AetherValue> data = new ConcurrentHashMap<>();

        TestKVStore() {
            super(MessageRouter.mutable(), null, null);
        }

        @Override
        public Map<AetherKey, AetherValue> snapshot() {
            return new ConcurrentHashMap<>(data);
        }

        void put(AetherKey key, AetherValue value) {
            data.put(key, value);
        }
    }
}
