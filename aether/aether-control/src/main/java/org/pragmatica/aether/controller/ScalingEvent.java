// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.controller;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.messaging.Message;


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

    /// Emitted when the autoscaler's requested instance count is reduced by a cap before it is
    /// applied (#425). `reason` is `"max-instances"` (the blueprint's per-slice `maxInstances` bound
    /// bit first) or `"cluster-cap"` (the cluster-size bound bit). Emitted ONLY when a real reduction
    /// happened (`requestedInstances > cappedAtInstances`) — a pure observability signal that the
    /// slice wants more capacity than policy or the cluster currently allows.
    record ScaleCapped(Artifact artifact, int requestedInstances, int cappedAtInstances, String reason) implements ScalingEvent {
        public static ScaleCapped scaleCapped(Artifact artifact,
                                              int requestedInstances,
                                              int cappedAtInstances,
                                              String reason) {
            return new ScaleCapped(artifact, requestedInstances, cappedAtInstances, reason);
        }
    }
}
