// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.config.RollbackConfig;
import org.pragmatica.aether.invoke.SliceFailureEvent;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderManager;
import org.pragmatica.consensus.topology.QuorumStateNotification;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/// Verifies RollbackManager queries `LeaderManager.isLeader()` directly — leader transitions are
/// reflected on the next operation without any explicit dispatch into the manager.
class RollbackManagerSsotTest {
    private static final NodeId SELF = NodeId.nodeId("node-1").unwrap();

    private RollbackConfig config;
    private TestClusterNode clusterNode;
    private TestKVStore kvStore;
    private TestLeaderManager leaderManager;
    private RollbackManager rollbackManager;

    @BeforeEach
    void setup() {
        config = RollbackConfig.rollbackConfig();
        clusterNode = new TestClusterNode(SELF);
        kvStore = new TestKVStore();
        leaderManager = new TestLeaderManager();
        rollbackManager = RollbackManager.rollbackManager(SELF, config, clusterNode, kvStore, leaderManager);
    }

    @Test
    void leaderTransition_reflectedWithoutDispatchingState() {
        var v1 = Artifact.artifact("org.example:test:1.0.0").unwrap();
        var v2 = Artifact.artifact("org.example:test:2.0.0").unwrap();

        // Initial: not leader — version-change tracking must be a no-op.
        simulateSliceTargetChange(v1);
        simulateSliceTargetChange(v2);
        assertThat(clusterNode.appliedCommands).isEmpty();

        // Flip SSOT only. No call into rollbackManager state.
        leaderManager.setLeader(true);

        // Establish initial deployment state (under leader), then a follow-up version change
        // must take effect (storePreviousVersion is invoked on transitions, not initial puts).
        var v3 = Artifact.artifact("org.example:test:3.0.0").unwrap();
        var v4 = Artifact.artifact("org.example:test:4.0.0").unwrap();
        simulateSliceTargetChange(v3);
        simulateSliceTargetChange(v4);
        assertThat(clusterNode.appliedCommands).hasSize(1);

        // Flip SSOT back to false. Subsequent failure event must NOT trigger rollback.
        leaderManager.setLeader(false);
        clusterNode.appliedCommands.clear();
        rollbackManager.onAllInstancesFailed(failureEvent(v4));
        assertThat(clusterNode.appliedCommands).isEmpty();
    }

    @Test
    void isActive_reflectsLeaderManagerState() {
        assertThat(rollbackManager.isActive()).isFalse();

        leaderManager.setLeader(true);
        assertThat(rollbackManager.isActive()).isTrue();

        leaderManager.setLeader(false);
        assertThat(rollbackManager.isActive()).isFalse();
    }

    private void simulateSliceTargetChange(Artifact artifact) {
        var key = SliceTargetKey.sliceTargetKey(artifact.base());
        var value = SliceTargetValue.sliceTargetValue(artifact.version(), 1);
        var put = new KVCommand.Put<SliceTargetKey, SliceTargetValue>(key, value);
        rollbackManager.onSliceTargetPut(new ValuePut<>(put, Option.none()));
    }

    private SliceFailureEvent.AllInstancesFailed failureEvent(Artifact artifact) {
        return SliceFailureEvent.AllInstancesFailed.allInstancesFailed("req-1",
                                                                        artifact,
                                                                        MethodName.methodName("doSomething").unwrap(),
                                                                        Option.some(Causes.cause("test")),
                                                                        List.of(NodeId.nodeId("node-2").unwrap()));
    }

    static final class TestClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private final NodeId self;
        final List<KVCommand<AetherKey>> appliedCommands = new CopyOnWriteArrayList<>();

        TestClusterNode(NodeId self) {
            this.self = self;
        }

        @Override public NodeId self() {
            return self;
        }

        @Override public TopologyManager topologyManager() {
            return null;
        }

        @Override public Promise<Unit> start() {
            return Promise.unitPromise();
        }

        @Override public Promise<Unit> stop() {
            return Promise.unitPromise();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            appliedCommands.addAll(commands);
            return Promise.success((List<R>) commands.stream().map(_ -> Unit.unit()).toList());
        }
    }

    static final class TestKVStore extends KVStore<AetherKey, AetherValue> {
        private final ConcurrentHashMap<AetherKey, AetherValue> data = new ConcurrentHashMap<>();

        TestKVStore() {
            super(MessageRouter.mutable(), null, null);
        }

        @Override public Map<AetherKey, AetherValue> snapshot() {
            return new ConcurrentHashMap<>(data);
        }
    }

    static final class TestLeaderManager implements LeaderManager {
        private volatile boolean leader = false;

        void setLeader(boolean value) {
            this.leader = value;
        }

        @Override public Option<NodeId> leader() {
            return leader ? Option.some(SELF) : Option.none();
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
        @Override public void nodeAdded(TopologyChangeNotification.NodeAdded n) {}
        @Override public void nodeRemoved(TopologyChangeNotification.NodeRemoved n) {}
        @Override public void nodeDown(TopologyChangeNotification.NodeDown n) {}
        @Override public void watchQuorumState(QuorumStateNotification q) {}
    }
}
