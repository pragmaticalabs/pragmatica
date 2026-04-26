// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.invoke;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.invoke.ScheduledTaskManagerTest.StubSliceInvoker;
import org.pragmatica.aether.slice.ExecutionMode;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderManager;
import org.pragmatica.consensus.topology.QuorumStateNotification;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
import org.pragmatica.lang.Option;

import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/// Verifies ScheduledTaskManager queries `LeaderManager.isLeader()` directly when transitioning
/// out of `Dormant` on `QuorumEstablished` — leader transitions are reflected without depending
/// on a prior `LeaderChange` dispatch.
class ScheduledTaskManagerSsotTest {
    private ScheduledTaskRegistry registry;
    private NodeId self;
    private Artifact artifact;
    private MethodName method;
    private TestLeaderManager leaderManager;
    private ScheduledTaskManager manager;

    @BeforeEach
    void setUp() {
        registry = ScheduledTaskRegistry.scheduledTaskRegistry();
        self = new NodeId("node-self");
        artifact = Artifact.artifact("org.example:my-slice:1.0.0").unwrap();
        method = MethodName.methodName("cleanup").unwrap();
        leaderManager = new TestLeaderManager(self);
        manager = ScheduledTaskManager.scheduledTaskManager(registry,
                                                             new StubSliceInvoker(new CopyOnWriteArrayList<>(), Option.none()),
                                                             self,
                                                             new CopyOnWriteArrayList<KVCommand<AetherKey>>()::add,
                                                             leaderManager);
    }

    @AfterEach
    void tearDown() {
        manager.stop();
    }

    @Test
    void leaderTransition_reflectedWithoutDispatchingState() {
        // Register a SINGLE-mode task (only runs when leader).
        putTask(ExecutionMode.SINGLE);

        // Flip SSOT BEFORE quorum establishes — no LeaderChange dispatch.
        leaderManager.setLeader(true);

        // Establish quorum: Dormant → Leading must be chosen by querying SSOT, not a cached flag.
        manager.onQuorumStateChange(QuorumStateNotification.established());

        assertThat(manager.activeTimerCount()).isEqualTo(1);
    }

    @Test
    void notLeader_quorumEstablished_routesToFollowing() {
        putTask(ExecutionMode.SINGLE);
        // SSOT says not-leader.
        manager.onQuorumStateChange(QuorumStateNotification.established());
        // SINGLE-mode task should NOT start in Following.
        assertThat(manager.activeTimerCount()).isEqualTo(0);
    }

    private void putTask(ExecutionMode mode) {
        var key = ScheduledTaskKey.scheduledTaskKey("cache", artifact, method);
        var value = ScheduledTaskValue.intervalTask(self, "30s", mode);
        var put = new KVCommand.Put<>(key, value);
        registry.onScheduledTaskPut(new ValuePut<>(put, Option.none()));
    }

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
        @Override public void nodeAdded(TopologyChangeNotification.NodeAdded n) {}
        @Override public void nodeRemoved(TopologyChangeNotification.NodeRemoved n) {}
        @Override public void nodeDown(TopologyChangeNotification.NodeDown n) {}
        @Override public void watchQuorumState(QuorumStateNotification q) {}
    }
}
