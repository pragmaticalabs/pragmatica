// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.deployment.delegation.TaskAssignmentCoordinator;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.TaskAssignmentValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.pragmatica.http.routing.PathParameter.aString;


/// Routes for task group delegation observability: list assignments and force-reassign.
public final class TaskRoutes implements RouteSource {
    private static final Cause UNKNOWN_TASK_GROUP = Causes.cause("Unknown task group");

    private final Supplier<ManageableNode> nodeSupplier;

    private TaskRoutes(Supplier<ManageableNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static TaskRoutes taskRoutes(Supplier<ManageableNode> nodeSupplier) {
        return new TaskRoutes(nodeSupplier);
    }

    record TaskAssignmentInfo(String group, String assignedTo, String assignedAt, String status, String failureReason){}

    record TaskAssignmentsResponse(List<TaskAssignmentInfo> assignments){}

    record ReassignRequest(String targetNode){}

    record ReassignResponse(String status){}

    @Override public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<TaskAssignmentsResponse>route(ManagementRoute.CLUSTER_TASKS_LIST)
                                         .to(_ -> Promise.success(listAssignments()))
                                         .asJson(),
                         ManagementRoutes.<ReassignResponse>route(ManagementRoute.CLUSTER_TASK_REASSIGN)
                                         .withPath(aString())
                                         .withBody(ReassignRequest.class)
                                         .to(this::reassignTask)
                                         .asJson());
    }

    private TaskAssignmentsResponse listAssignments() {
        var infos = new ArrayList<TaskAssignmentInfo>();
        nodeSupplier.get().kvStore()
                        .forEach(AetherKey.TaskAssignmentKey.class,
                                 TaskAssignmentValue.class,
                                 (key, value) -> infos.add(toAssignmentInfo(key, value)));
        return new TaskAssignmentsResponse(List.copyOf(infos));
    }

    private static TaskAssignmentInfo toAssignmentInfo(AetherKey.TaskAssignmentKey key, TaskAssignmentValue value) {
        return new TaskAssignmentInfo(key.taskGroup().name(),
                                      value.assignedTo().id(),
                                      Instant.ofEpochMilli(value.assignedAtMs()).toString(),
                                      value.status().name(),
                                      value.failureReason());
    }

    private Promise<ReassignResponse> reassignTask(String group, ReassignRequest request) {
        return parseTaskGroup(group).async()
                                    .flatMap(taskGroup -> reassignToNode(taskGroup, request.targetNode()));
    }

    private Promise<ReassignResponse> reassignToNode(TaskGroup taskGroup, String targetNodeId) {
        return NodeId.nodeId(targetNodeId).async()
                     .flatMap(nodeId -> coordinator().reassign(taskGroup, nodeId))
                     .map(_ -> new ReassignResponse("reassigned"));
    }

    private static Result<TaskGroup> parseTaskGroup(String group) {
        return Result.lift(UNKNOWN_TASK_GROUP, () -> TaskGroup.valueOf(group));
    }

    private TaskAssignmentCoordinator coordinator() {
        return nodeSupplier.get().taskAssignmentCoordinator();
    }
}
