// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
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
