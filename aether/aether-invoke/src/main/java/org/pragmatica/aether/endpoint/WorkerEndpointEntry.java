package org.pragmatica.aether.endpoint;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.consensus.NodeId;

/// An endpoint entry for a slice method hosted on a worker node.
///
/// @param artifact the deployed slice artifact
/// @param methodName the method exposed by the slice
/// @param workerNodeId the worker node hosting this endpoint
/// @param instanceNumber the instance number on that worker
public record WorkerEndpointEntry(Artifact artifact,
                                  MethodName methodName,
                                  NodeId workerNodeId,
                                  int instanceNumber) {
    public static WorkerEndpointEntry workerEndpointEntry(Artifact artifact,
                                                          MethodName methodName,
                                                          NodeId workerNodeId,
                                                          int instanceNumber) {
        return new WorkerEndpointEntry(artifact, methodName, workerNodeId, instanceNumber);
    }
}
