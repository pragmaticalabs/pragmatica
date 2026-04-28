// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics.deployment;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.messaging.Message;


public sealed interface DeploymentEvent extends Message.Local {
    record unused() implements DeploymentEvent{}

    record DeploymentStarted(Artifact artifact, NodeId targetNode, long timestamp) implements DeploymentEvent {
        public static DeploymentStarted deploymentStarted(Artifact artifact, NodeId targetNode, long timestamp) {
            return new DeploymentStarted(artifact, targetNode, timestamp);
        }
    }

    record StateTransition(Artifact artifact, NodeId nodeId, SliceState from, SliceState to, long timestamp) implements DeploymentEvent {
        public static StateTransition stateTransition(Artifact artifact,
                                                      NodeId nodeId,
                                                      SliceState from,
                                                      SliceState to,
                                                      long timestamp) {
            return new StateTransition(artifact, nodeId, from, to, timestamp);
        }
    }

    record DeploymentCompleted(Artifact artifact, NodeId nodeId, long timestamp) implements DeploymentEvent {
        public static DeploymentCompleted deploymentCompleted(Artifact artifact, NodeId nodeId, long timestamp) {
            return new DeploymentCompleted(artifact, nodeId, timestamp);
        }
    }

    record DeploymentFailed(Artifact artifact,
                            NodeId nodeId,
                            SliceState failedAt,
                            String errorMessage,
                            long timestamp) implements DeploymentEvent {
        public static DeploymentFailed deploymentFailed(Artifact artifact,
                                                        NodeId nodeId,
                                                        SliceState failedAt,
                                                        String errorMessage,
                                                        long timestamp) {
            return new DeploymentFailed(artifact, nodeId, failedAt, errorMessage, timestamp);
        }
    }
}
