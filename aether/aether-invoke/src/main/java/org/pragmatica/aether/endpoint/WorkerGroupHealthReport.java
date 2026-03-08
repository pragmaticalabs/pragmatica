package org.pragmatica.aether.endpoint;

import org.pragmatica.consensus.NodeId;

import java.util.List;

/// Health report from a worker pool governor to a core node.
/// Contains the current set of worker endpoints and group membership.
///
/// @param governorId the NodeId of the governor sending this report
/// @param groupId the worker group identifier (Phase 1: always "main")
/// @param endpoints list of active worker endpoint entries
/// @param members list of worker NodeIds in the group (alive members)
/// @param timestamp when this report was generated
public record WorkerGroupHealthReport(NodeId governorId,
                                      String groupId,
                                      List<WorkerEndpointEntry> endpoints,
                                      List<NodeId> members,
                                      long timestamp) {
    public static WorkerGroupHealthReport workerGroupHealthReport(NodeId governorId,
                                                                  String groupId,
                                                                  List<WorkerEndpointEntry> endpoints,
                                                                  List<NodeId> members) {
        return new WorkerGroupHealthReport(governorId,
                                           groupId,
                                           List.copyOf(endpoints),
                                           List.copyOf(members),
                                           System.currentTimeMillis());
    }
}
