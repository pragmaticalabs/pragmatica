package org.pragmatica.aether.controller;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.messaging.Message;

/// Events emitted when the control loop applies scaling decisions.
/// Dispatched locally via MessageRouter to the ClusterEventAggregator.
public sealed interface ScalingEvent extends Message.Local {
    record ScaledUp(Artifact artifact, int previousInstances, int newInstances) implements ScalingEvent {
        public static ScaledUp scaledUp(Artifact artifact, int previousInstances, int newInstances) {
            return new ScaledUp(artifact, previousInstances, newInstances);
        }
    }

    record ScaledDown(Artifact artifact, int previousInstances, int newInstances) implements ScalingEvent {
        public static ScaledDown scaledDown(Artifact artifact, int previousInstances, int newInstances) {
            return new ScaledDown(artifact, previousInstances, newInstances);
        }
    }
}
