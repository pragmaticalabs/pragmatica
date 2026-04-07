/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
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
        var map = new ConcurrentHashMap<TaskGroup, NodeId>();
        kvStore.forEach(TaskAssignmentKey.class,
                        TaskAssignmentValue.class,
                        (key, value) -> map.put(key.taskGroup(), value.assignedTo()));
        return new TaskGroupAssignmentRegistryImpl(map);
    }
}
