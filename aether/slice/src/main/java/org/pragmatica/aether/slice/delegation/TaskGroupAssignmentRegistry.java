// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.delegation;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.TaskAssignmentKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.TaskAssignmentValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Result;
import org.pragmatica.messaging.MessageReceiver;

import java.util.concurrent.ConcurrentHashMap;


/// Read-side mirror of task-group → owner-node assignments held in the
/// consensus KV-Store.
///
/// Used by both core nodes and the passive LB to resolve which node currently
/// owns a given task group when forwarding management API requests. The
/// registry seeds itself from the current KV-Store contents at construction
/// time and stays in sync via KV notifications wired through `KVNotificationRouter`.
///
/// `ownerFor` fails fast (no waiting/blocking) with [TaskAssignmentError.NotAssigned]
/// if the requested task group has no current assignment. Callers translate
/// this into a forwarding-layer error as appropriate.
@SuppressWarnings("JBCT-RET-01")
// MessageReceiver callbacks --- void required by messaging framework
public sealed interface TaskGroupAssignmentRegistry permits TaskGroupAssignmentRegistryImpl {
    Result<NodeId> ownerFor(TaskGroup group);
    @MessageReceiver void onTaskAssignmentPut(ValuePut<TaskAssignmentKey, TaskAssignmentValue> put);
    @MessageReceiver void onTaskAssignmentRemove(ValueRemove<TaskAssignmentKey, TaskAssignmentValue> remove);

    static TaskGroupAssignmentRegistry taskGroupAssignmentRegistry(KVStore<AetherKey, AetherValue> kvStore) {
        var registry = new TaskGroupAssignmentRegistryImpl(new ConcurrentHashMap<>());
        registry.activateWithSnapshot(consumer -> kvStore.forEach(TaskAssignmentKey.class,
                                                                  TaskAssignmentValue.class,
                                                                  consumer));
        return registry;
    }
}
