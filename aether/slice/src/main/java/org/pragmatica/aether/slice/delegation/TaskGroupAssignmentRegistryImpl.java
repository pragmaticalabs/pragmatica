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

import org.pragmatica.aether.slice.kvstore.AetherKey.TaskAssignmentKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.TaskAssignmentValue;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Implementation of [TaskGroupAssignmentRegistry] backed by a concurrent map.
@SuppressWarnings("JBCT-RET-01") record TaskGroupAssignmentRegistryImpl(Map<TaskGroup, NodeId> assignments) implements TaskGroupAssignmentRegistry {
    private static final Logger log = LoggerFactory.getLogger(TaskGroupAssignmentRegistryImpl.class);

    @Override public Result<NodeId> ownerFor(TaskGroup group) {
        return Option.option(assignments.get(group)).toResult(TaskAssignmentError.notAssigned(group));
    }

    @Override public void onTaskAssignmentPut(ValuePut<TaskAssignmentKey, TaskAssignmentValue> put) {
        var key = put.cause().key();
        var value = put.cause().value();
        var taskGroup = key.taskGroup();
        var owner = value.assignedTo();
        var previous = assignments.put(taskGroup, owner);
        if (previous == null) {log.info("Task group {} assigned to {}", taskGroup, owner);} else if (!previous.equals(owner)) {log.info("Task group {} reassigned from {} to {}",
                                                                                                                                        taskGroup,
                                                                                                                                        previous,
                                                                                                                                        owner);}
    }

    @Override public void onTaskAssignmentRemove(ValueRemove<TaskAssignmentKey, TaskAssignmentValue> remove) {
        var taskGroup = remove.cause().key()
                                    .taskGroup();
        var previous = assignments.remove(taskGroup);
        if (previous != null) {log.info("Task group {} assignment removed (was {})", taskGroup, previous);}
    }
}
