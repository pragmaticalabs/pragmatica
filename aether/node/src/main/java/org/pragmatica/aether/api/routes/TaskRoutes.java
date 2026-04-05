package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.deployment.delegation.TaskAssignmentCoordinator;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.aether.slice.kvstore.AetherValue.TaskAssignmentValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.pragmatica.http.routing.PathParameter.aString;
import static org.pragmatica.http.routing.PathParameter.spacer;


/// Routes for task group delegation observability: list assignments and force-reassign.
public final class TaskRoutes implements RouteSource {
    private static final Cause UNKNOWN_TASK_GROUP = Causes.cause("Unknown task group");

    private final Supplier<AetherNode> nodeSupplier;

    private TaskRoutes(Supplier<AetherNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static TaskRoutes taskRoutes(Supplier<AetherNode> nodeSupplier) {
        return new TaskRoutes(nodeSupplier);
    }

    record TaskAssignmentInfo(String group, String assignedTo, String assignedAt, String status, String failureReason){}

    record TaskAssignmentsResponse(List<TaskAssignmentInfo> assignments){}

    record ReassignRequest(String targetNode){}

    record ReassignResponse(String status){}

    @Override public Stream<Route<?>> routes() {
        return Stream.of(Route.<TaskAssignmentsResponse>get("/api/cluster/tasks").toJson(this::listAssignments),
                         Route.<ReassignResponse>put("/api/cluster/tasks")
                              .withPath(aString(),
                                        spacer("reassign"))
                              .withBody(ReassignRequest.class)
                              .toResult(this::reassignTask)
                              .asJson());
    }

    private TaskAssignmentsResponse listAssignments() {
        var assignments = coordinator().assignments();
        var infos = assignments.entrySet().stream()
                                        .map(TaskRoutes::toAssignmentInfo)
                                        .toList();
        return new TaskAssignmentsResponse(infos);
    }

    private static TaskAssignmentInfo toAssignmentInfo(Map.Entry<TaskGroup, TaskAssignmentValue> entry) {
        var value = entry.getValue();
        return new TaskAssignmentInfo(entry.getKey().name(),
                                      value.assignedTo().id(),
                                      Instant.ofEpochMilli(value.assignedAtMs()).toString(),
                                      value.status().name(),
                                      value.failureReason());
    }

    private Result<ReassignResponse> reassignTask(String group, String ignored, ReassignRequest request) {
        return parseTaskGroup(group).flatMap(taskGroup -> reassignToNode(taskGroup, request.targetNode()));
    }

    private Result<ReassignResponse> reassignToNode(TaskGroup taskGroup, String targetNodeId) {
        return NodeId.nodeId(targetNodeId).flatMap(nodeId -> coordinator().reassign(taskGroup, nodeId))
                            .map(_ -> new ReassignResponse("reassigned"));
    }

    private static Result<TaskGroup> parseTaskGroup(String group) {
        return Result.lift(UNKNOWN_TASK_GROUP, () -> TaskGroup.valueOf(group));
    }

    private TaskAssignmentCoordinator coordinator() {
        return nodeSupplier.get().taskAssignmentCoordinator();
    }
}
