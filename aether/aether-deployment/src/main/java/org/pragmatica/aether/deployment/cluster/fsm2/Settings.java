// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Set;
import java.util.function.ToDoubleFunction;

import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.consensus.NodeId;


/// What the cluster FSM is configured with, fixed for the life of one FSM instance: who it is, the declared
/// atomicity, the community sizing, the absence bounds and the time constants.
///
/// `jitter` maps a subject (an artifact-node key) to a number in `[0, 1)` for the retry delays (R-3.3.7). It is
/// injected so that `step` stays a function of its arguments: the interpreter supplies a random source, a test a
/// constant.
public record Settings(NodeId self,
                       DeploymentAtomicity atomicity,
                       int communityTargetSize,
                       int communityViabilityFloor,
                       Set<NodeId> seedNodes,
                       AbsenceBounds bounds,
                       Timing timing,
                       ToDoubleFunction<String> jitter) {
    public Settings {
        seedNodes = Set.copyOf(seedNodes);
    }

    public static Settings settings(NodeId self,
                                    DeploymentAtomicity atomicity,
                                    Set<NodeId> seedNodes,
                                    AbsenceBounds bounds) {
        return new Settings(self, atomicity, 100, 3, seedNodes, bounds, Timing.DEFAULT, _ -> 0.5d);
    }

    public Settings withTiming(Timing value) {
        return new Settings(self,
                            atomicity,
                            communityTargetSize,
                            communityViabilityFloor,
                            seedNodes,
                            bounds,
                            value,
                            jitter);
    }

    public Settings withCommunitySizing(int targetSize, int viabilityFloor) {
        return new Settings(self, atomicity, targetSize, viabilityFloor, seedNodes, bounds, timing, jitter);
    }

    public Settings withJitter(ToDoubleFunction<String> value) {
        return new Settings(self,
                            atomicity,
                            communityTargetSize,
                            communityViabilityFloor,
                            seedNodes,
                            bounds,
                            timing,
                            value);
    }
}
