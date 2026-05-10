// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.delegation;

import org.pragmatica.aether.slice.delegation.DelegatedComponent;
import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.TaskAssignmentKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.TaskAssignmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.TaskAssignmentValue.AssignmentStatus;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;

/// When the leader's reconcile re-issues `status=ASSIGNED` for a task group whose
/// components are already active locally (e.g. the noOp `DelegatedStorageAdapter`
/// after a transient SUSPECTED health blip on the assigned node), the activator
/// must still re-publish ACTIVE so KV converges. The pre-fix code returned
/// silently when `inactive.isEmpty()`, leaving KV stuck at ASSIGNED indefinitely.
class TaskGroupActivatorReassignmentTest {
    private static final NodeId SELF = nodeId("node-self").unwrap();
    private static final NodeId PEER = nodeId("node-peer").unwrap();

    @Test
    void reassignmentToSelf_whenComponentAlreadyActive_republishesActive() {
        var clusterNode = new RecordingClusterNode();
        var activator = TaskGroupActivator.taskGroupActivator(SELF, clusterNode);

        var group = TaskGroup.values()[0];
        var component = new AlwaysActiveComponent(group);
        activator.register(component);

        // Activator gates ACTIVE/FAILED at the entry point, so a duplicate ASSIGNED
        // (re-issued by the leader after a transient SUSPECTED health blip) is the
        // only path that reaches handleLocalAssignment with all components already
        // active.
        var key = TaskAssignmentKey.taskAssignmentKey(group);
        var value = new TaskAssignmentValue(SELF, 1L, AssignmentStatus.ASSIGNED, "");
        activator.onTaskAssignmentPut(new ValuePut<>(new KVCommand.Put<>(key, value), Option.empty()));

        var puts = clusterNode.activePutsForGroup(group);
        assertThat(puts)
            .as("re-issued ASSIGNED on already-active component must trigger an idempotent ACTIVE re-write to KV")
            .hasSize(1);
        assertThat(puts.getFirst().status())
            .isEqualTo(AssignmentStatus.ACTIVE);
        assertThat(puts.getFirst().assignedTo())
            .isEqualTo(SELF);
        // Components were not deactivated/reactivated — only the KV status was refreshed.
        assertThat(component.activations()).isZero();
    }

    @Test
    void firstAssignmentToSelf_activatesComponent_andPublishesActive() {
        var clusterNode = new RecordingClusterNode();
        var activator = TaskGroupActivator.taskGroupActivator(SELF, clusterNode);

        var group = TaskGroup.values()[0];
        var component = new TogglingComponent(group);
        activator.register(component);

        var key = TaskAssignmentKey.taskAssignmentKey(group);
        var value = new TaskAssignmentValue(SELF, 1L, AssignmentStatus.ASSIGNED, "");
        activator.onTaskAssignmentPut(new ValuePut<>(new KVCommand.Put<>(key, value), Option.empty()));

        // Sanity: pre-fix path is unaffected — first assignment activates the component
        // and reports ACTIVE.
        assertThat(component.activations()).isEqualTo(1);
        var puts = clusterNode.activePutsForGroup(group);
        assertThat(puts).hasSize(1);
        assertThat(puts.getFirst().status()).isEqualTo(AssignmentStatus.ACTIVE);
    }

    @Test
    void assignmentToPeer_whenLocalComponentActive_deactivates_doesNotPublishActive() {
        var clusterNode = new RecordingClusterNode();
        var activator = TaskGroupActivator.taskGroupActivator(SELF, clusterNode);

        var group = TaskGroup.values()[0];
        var component = new AlwaysActiveComponent(group);
        activator.register(component);

        // Reassignment to a peer must NOT re-publish ACTIVE from this node — that's
        // the peer's responsibility. The handleRemoteAssignment branch deactivates
        // the local component and writes nothing.
        var key = TaskAssignmentKey.taskAssignmentKey(group);
        var value = new TaskAssignmentValue(PEER, 1L, AssignmentStatus.ASSIGNED, "");
        activator.onTaskAssignmentPut(new ValuePut<>(new KVCommand.Put<>(key, value), Option.empty()));

        assertThat(clusterNode.activePutsForGroup(group))
            .as("reassignment to peer must not produce a KV write from the prior owner")
            .isEmpty();
    }

    /// Stub component that is permanently active — mirrors `DelegatedStorageAdapter.noOp()`
    /// behaviour after first activation.
    private static final class AlwaysActiveComponent implements DelegatedComponent {
        private final TaskGroup taskGroup;
        private int activationCount;

        AlwaysActiveComponent(TaskGroup taskGroup) {this.taskGroup = taskGroup;}

        int activations() {return activationCount;}

        @Override public Promise<Unit> activate() {
            activationCount++;
            return Promise.unitPromise();
        }

        @Override public Promise<Unit> deactivate() {return Promise.unitPromise();}

        @Override public TaskGroup taskGroup() {return taskGroup;}

        @Override public boolean isActive() {return true;}
    }

    /// Stub component that toggles isActive() on activate/deactivate — exercises
    /// the activation path the pre-fix code already handled correctly.
    private static final class TogglingComponent implements DelegatedComponent {
        private final TaskGroup taskGroup;
        private boolean active;
        private int activationCount;

        TogglingComponent(TaskGroup taskGroup) {this.taskGroup = taskGroup;}

        int activations() {return activationCount;}

        @Override public Promise<Unit> activate() {
            activationCount++;
            active = true;
            return Promise.unitPromise();
        }

        @Override public Promise<Unit> deactivate() {
            active = false;
            return Promise.unitPromise();
        }

        @Override public TaskGroup taskGroup() {return taskGroup;}

        @Override public boolean isActive() {return active;}
    }

    /// Captures every `apply()` so the test can inspect issued KV Puts.
    private static final class RecordingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private final List<KVCommand<AetherKey>> applied = Collections.synchronizedList(new ArrayList<>());
        private final AtomicReference<Integer> applyCount = new AtomicReference<>(0);

        List<TaskAssignmentValue> activePutsForGroup(TaskGroup group) {
            synchronized (applied) {
                return applied.stream()
                              .filter(cmd -> cmd instanceof KVCommand.Put<?, ?> put
                                             && put.key() instanceof TaskAssignmentKey k
                                             && k.taskGroup() == group)
                              .map(cmd -> (TaskAssignmentValue) ((KVCommand.Put<?, ?>) cmd).value())
                              .toList();
            }
        }

        @Override public NodeId self() {return SELF;}

        @Override public TopologyManager topologyManager() {throw new UnsupportedOperationException();}

        @Override public Promise<Unit> start() {return Promise.unitPromise();}

        @Override public Promise<Unit> stop() {return Promise.unitPromise();}

        @SuppressWarnings("unchecked")
        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            applied.addAll(commands);
            applyCount.updateAndGet(v -> v + 1);
            return (Promise<List<R>>) (Promise<?>) Promise.success(List.of());
        }
    }
}
