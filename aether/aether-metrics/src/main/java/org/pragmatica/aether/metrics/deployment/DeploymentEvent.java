package org.pragmatica.aether.metrics.deployment;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.messaging.Message;

/// Events emitted during slice deployment lifecycle for metrics collection.
///
///
/// These events are dispatched locally via MessageRouter to track deployment progress.
/// This is a sealed hierarchy validated at route-building time via SealedBuilder.
///
///
/// Lifecycle flow:
/// ```
/// DeploymentStarted → StateTransition* → DeploymentCompleted
///                                      → DeploymentFailed
/// ```
///
/// @see org.pragmatica.messaging.MessageRouter.Entry.SealedBuilder
public sealed interface DeploymentEvent extends Message.Local {
    /// Emitted when a deployment is initiated (blueprint change triggers LOAD command).
    record DeploymentStarted(Artifact artifact, NodeId targetNode, long timestamp) implements DeploymentEvent {
        /// Factory method following JBCT naming convention. */
        public static DeploymentStarted deploymentStarted(Artifact artifact, NodeId targetNode, long timestamp) {
            return new DeploymentStarted(artifact, targetNode, timestamp);
        }
    }

    /// Emitted on each state transition during deployment.
    record StateTransition(Artifact artifact,
                           NodeId nodeId,
                           SliceState from,
                           SliceState to,
                           long timestamp) implements DeploymentEvent {
        /// Factory method following JBCT naming convention. */
        public static StateTransition stateTransition(Artifact artifact,
                                                      NodeId nodeId,
                                                      SliceState from,
                                                      SliceState to,
                                                      long timestamp) {
            return new StateTransition(artifact, nodeId, from, to, timestamp);
        }
    }

    /// Emitted when deployment completes (reaches ACTIVE state).
    record DeploymentCompleted(Artifact artifact, NodeId nodeId, long timestamp) implements DeploymentEvent {
        /// Factory method following JBCT naming convention. */
        public static DeploymentCompleted deploymentCompleted(Artifact artifact, NodeId nodeId, long timestamp) {
            return new DeploymentCompleted(artifact, nodeId, timestamp);
        }
    }

    /// Emitted when deployment fails (reaches FAILED state).
    record DeploymentFailed(Artifact artifact, NodeId nodeId, SliceState failedAt, long timestamp) implements DeploymentEvent {
        /// Factory method following JBCT naming convention. */
        public static DeploymentFailed deploymentFailed(Artifact artifact, NodeId nodeId, SliceState failedAt, long timestamp) {
            return new DeploymentFailed(artifact, nodeId, failedAt, timestamp);
        }
    }
}
