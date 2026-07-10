// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.controller.fsm;

import org.pragmatica.aether.artifact.Artifact;


/// Per-slice decision snapshot (#425). One record captures what the leader control loop decided for
/// a single artifact during one evaluation cycle — the terminal outcome, the guard that shaped it,
/// the composite load factor that drove it, and the instance-count arithmetic (current, requested,
/// capped). Snapshot-read only: the control loop keeps the latest record per artifact in a bounded
/// map (pruned to the registered blueprint set) and exposes copies for the management API. No
/// hot-path cost beyond a map put per evaluation.
public record ScalingDecisionRecord(Artifact artifact,
                                    Outcome outcome,
                                    Guard guard,
                                    double loadFactor,
                                    int currentInstances,
                                    int requestedInstances,
                                    int cappedInstances,
                                    long atMs) {
    /// Terminal outcome of an evaluation for one artifact.
    public enum Outcome {
        SCALED_UP,
        SCALED_DOWN,
        HELD,
        BLOCKED,
        CAPPED
    }

    /// The guard that shaped the outcome. `NONE` means no guard intervened (a clean scale or a
    /// neutral-band hold); the remaining values name the specific gate that fired.
    public enum Guard {
        NONE,
        WINDOW_NOT_FULL,
        SLICE_IN_PROGRESS,
        COOLDOWN,
        MAX_INSTANCES,
        CLUSTER_CAP,
        ERROR_BLOCK
    }

    public static ScalingDecisionRecord scalingDecisionRecord(Artifact artifact,
                                                              Outcome outcome,
                                                              Guard guard,
                                                              double loadFactor,
                                                              int currentInstances,
                                                              int requestedInstances,
                                                              int cappedInstances,
                                                              long atMs) {
        return new ScalingDecisionRecord(artifact,
                                         outcome,
                                         guard,
                                         loadFactor,
                                         currentInstances,
                                         requestedInstances,
                                         cappedInstances,
                                         atMs);
    }
}
