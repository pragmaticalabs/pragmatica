// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.slice.delegation;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.TaskAssignmentKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.TaskAssignmentValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.junit.jupiter.api.Assertions;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.delegation.TaskGroup.DEPLOYMENT;
import static org.pragmatica.aether.slice.delegation.TaskGroup.STORAGE;
import static org.pragmatica.aether.slice.delegation.TaskGroup.STRATEGIES;
import static org.pragmatica.aether.slice.delegation.TaskGroupAssignmentRegistry.taskGroupAssignmentRegistry;


class TaskGroupAssignmentRegistryTest {

    private static final NodeId NODE_A = new NodeId("node-a");
    private static final NodeId NODE_B = new NodeId("node-b");

    @Test
    void ownerFor_groupAssigned_returnsNodeId() {
        var registry = taskGroupAssignmentRegistry(emptyKvStore());
        registry.onTaskAssignmentPut(putEvent(STRATEGIES, NODE_A));

        registry.ownerFor(STRATEGIES)
                .onFailure(cause -> Assertions.fail("Expected success, got: " + cause.message()))
                .onSuccess(node -> assertThat(node).isEqualTo(NODE_A));
    }

    @Test
    void ownerFor_groupNotAssigned_returnsFailure() {
        var registry = taskGroupAssignmentRegistry(emptyKvStore());

        var result = registry.ownerFor(DEPLOYMENT);

        result.onSuccess(node -> Assertions.fail("Expected failure, got node: " + node))
              .onFailure(cause -> {
                  assertThat(cause).isInstanceOf(TaskAssignmentError.NotAssigned.class);
                  assertThat(((TaskAssignmentError.NotAssigned) cause).group()).isEqualTo(DEPLOYMENT);
              });
    }

    @Test
    void onTaskAssignmentPut_updatesMap() {
        var registry = taskGroupAssignmentRegistry(emptyKvStore());

        registry.onTaskAssignmentPut(putEvent(STRATEGIES, NODE_A));
        assertOwner(registry.ownerFor(STRATEGIES), NODE_A);

        registry.onTaskAssignmentPut(putEvent(STRATEGIES, NODE_B));
        assertOwner(registry.ownerFor(STRATEGIES), NODE_B);
    }

    @Test
    void onTaskAssignmentRemove_clearsEntry() {
        var registry = taskGroupAssignmentRegistry(emptyKvStore());
        registry.onTaskAssignmentPut(putEvent(STORAGE, NODE_A));
        assertOwner(registry.ownerFor(STORAGE), NODE_A);

        registry.onTaskAssignmentRemove(removeEvent(STORAGE, NODE_A));

        registry.ownerFor(STORAGE)
                .onSuccess(node -> Assertions.fail("Expected failure after remove, got: " + node))
                .onFailure(cause -> assertThat(cause).isInstanceOf(TaskAssignmentError.NotAssigned.class));
    }

    @Test
    void seedFromKVStore_populatesInitialState() {
        var seeded = new HashMap<Object, Object>();
        seeded.put(TaskAssignmentKey.taskAssignmentKey(STRATEGIES), TaskAssignmentValue.taskAssignmentValue(NODE_A));
        seeded.put(TaskAssignmentKey.taskAssignmentKey(DEPLOYMENT), TaskAssignmentValue.taskAssignmentValue(NODE_B));

        var registry = taskGroupAssignmentRegistry(stubKvStore(seeded));

        assertOwner(registry.ownerFor(STRATEGIES), NODE_A);
        assertOwner(registry.ownerFor(DEPLOYMENT), NODE_B);
    }

    /// Theme F drain-and-subscribe race — receiver events fired BEFORE the snapshot
    /// drain completes MUST be buffered and applied exactly once during activation.
    /// We construct an un-activated impl directly, fire a receiver (buffered), then
    /// activate with a snapshot containing different entries — and assert both the seed
    /// entries AND the buffered event are visible after activation, with no duplicates.
    @Test
    void preActivationReceiver_isBufferedAndAppliedExactlyOnce() {
        var assignments = new java.util.concurrent.ConcurrentHashMap<TaskGroup, NodeId>();
        var impl = new TaskGroupAssignmentRegistryImpl(assignments);
        // Buffered: fires before activation. Must be deferred until activate-time replay.
        impl.onTaskAssignmentPut(putEvent(DEPLOYMENT, NODE_B));
        // Now activate with a separate seed entry. Both must be present afterwards.
        impl.activateWithSnapshot(consumer -> consumer.accept(
                TaskAssignmentKey.taskAssignmentKey(STRATEGIES),
                TaskAssignmentValue.taskAssignmentValue(NODE_A)));

        assertOwner(impl.ownerFor(STRATEGIES), NODE_A);
        assertOwner(impl.ownerFor(DEPLOYMENT), NODE_B);

        // After activation, further receiver events apply directly (not double-applied).
        impl.onTaskAssignmentPut(putEvent(STORAGE, NODE_A));
        assertOwner(impl.ownerFor(STORAGE), NODE_A);
    }

    private static void assertOwner(Result<NodeId> result, NodeId expected) {
        result.onFailure(cause -> Assertions.fail("Expected success, got: " + cause.message()))
              .onSuccess(node -> assertThat(node).isEqualTo(expected));
    }

    private static ValuePut<TaskAssignmentKey, TaskAssignmentValue> putEvent(TaskGroup group, NodeId owner) {
        var key = TaskAssignmentKey.taskAssignmentKey(group);
        var value = TaskAssignmentValue.taskAssignmentValue(owner);
        return new ValuePut<>(new KVCommand.Put<>(key, value), Option.none());
    }

    private static ValueRemove<TaskAssignmentKey, TaskAssignmentValue> removeEvent(TaskGroup group, NodeId previousOwner) {
        var key = TaskAssignmentKey.taskAssignmentKey(group);
        var value = TaskAssignmentValue.taskAssignmentValue(previousOwner);
        return new ValueRemove<>(new KVCommand.Remove<>(key), Option.some(value));
    }

    private static KVStore<AetherKey, AetherValue> emptyKvStore() {
        return stubKvStore(Map.of());
    }

    private static KVStore<AetherKey, AetherValue> stubKvStore(Map<Object, Object> seeded) {
        return new StubKVStore(seeded);
    }

    /// Minimal KVStore subclass --- only `forEach(Class, Class, BiConsumer)` is exercised by the seeding path.
    private static final class StubKVStore extends KVStore<AetherKey, AetherValue> {
        private final Map<Object, Object> seeded;

        StubKVStore(Map<Object, Object> seeded) {
            super(null, null, null);
            this.seeded = seeded;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <KK, VV> void forEach(Class<KK> keyClass, Class<VV> valueClass, BiConsumer<KK, VV> consumer) {
            seeded.forEach((key, value) -> {
                if (keyClass.isInstance(key) && valueClass.isInstance(value)) {
                    consumer.accept((KK) key, (VV) value);
                }
            });
        }
    }
}
