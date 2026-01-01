package org.pragmatica.aether.metrics.deployment;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.messaging.Message;

/**
 * Events emitted during slice deployment lifecycle for metrics collection.
 * These events are dispatched locally via MessageRouter.
 */
public sealed interface DeploymentEvent extends Message.Local {
    /**
     * Emitted when a deployment is initiated (blueprint change triggers LOAD command).
     */
    record DeploymentStarted(Artifact artifact, NodeId targetNode, long timestamp) implements DeploymentEvent {}

    /**
     * Emitted on each state transition during deployment.
     */
    record StateTransition(
    Artifact artifact,
    NodeId nodeId,
    SliceState from,
    SliceState to,
    long timestamp) implements DeploymentEvent {}

    /**
     * Emitted when deployment completes (reaches ACTIVE state).
     */
    record DeploymentCompleted(Artifact artifact, NodeId nodeId, long timestamp) implements DeploymentEvent {}

    /**
     * Emitted when deployment fails (reaches FAILED state).
     */
    record DeploymentFailed(Artifact artifact, NodeId nodeId, SliceState failedAt, long timestamp) implements DeploymentEvent {}
}
