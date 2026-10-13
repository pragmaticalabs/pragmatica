// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Set;
import java.util.function.ToDoubleFunction;

import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.consensus.NodeId;


/// What the cluster FSM is configured with, fixed for the life of one FSM instance: who it is, the declared
/// atomicity, the community sizing, the absence bounds and the time constants.
///
/// - `divergence`: the transition timeouts `T` of the divergence clock (R-6.1.1), from `[timeouts.deployment]` (P3-Q12).
/// - `submissionExpiry` (ms): how long a submission may stay unsettled before it counts as lost (#2143, MQ-7). It must exceed the
///   consensus `applyTimeout`, so the FSM never releases a batch the consensus engine still owns: `applyTimeout + 5 s`
///   `[guess: the margin, settled by the timeout jitter of E-04]`.
/// - `process`: this JVM's process identity; it qualifies every correlation id (P3-Q16), so ids of two processes never collide.
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
                       DivergenceBounds divergence,
                       long submissionExpiry,
                       ProcessIncarnation process,
                       ToDoubleFunction<String> jitter) {
    /// The consensus `applyTimeout` default (30 s, `ProtocolConfig`) plus the 5 s margin.
    public static final long DEFAULT_SUBMISSION_EXPIRY = 35_000L;
    public static final ProcessIncarnation UNKNOWN_PROCESS = new ProcessIncarnation(0L, 0L);

    public Settings {
        seedNodes = Set.copyOf(seedNodes);
    }

    public static Settings settings(NodeId self,
                                    DeploymentAtomicity atomicity,
                                    Set<NodeId> seedNodes,
                                    AbsenceBounds bounds) {
        return new Settings(self,
                            atomicity,
                            100,
                            3,
                            seedNodes,
                            bounds,
                            Timing.DEFAULT,
                            DivergenceBounds.DEFAULT,
                            DEFAULT_SUBMISSION_EXPIRY,
                            UNKNOWN_PROCESS,
                            _ -> 0.5d);
    }

    public Settings withTiming(Timing value) {
        return new Settings(self,
                            atomicity,
                            communityTargetSize,
                            communityViabilityFloor,
                            seedNodes,
                            bounds,
                            value,
                            divergence,
                            submissionExpiry,
                            process,
                            jitter);
    }

    public Settings withCommunitySizing(int targetSize, int viabilityFloor) {
        return new Settings(self,
                            atomicity,
                            targetSize,
                            viabilityFloor,
                            seedNodes,
                            bounds,
                            timing,
                            divergence,
                            submissionExpiry,
                            process,
                            jitter);
    }

    public Settings withDivergence(DivergenceBounds value) {
        return new Settings(self,
                            atomicity,
                            communityTargetSize,
                            communityViabilityFloor,
                            seedNodes,
                            bounds,
                            timing,
                            value,
                            submissionExpiry,
                            process,
                            jitter);
    }

    public Settings withSubmissionExpiry(long value) {
        return new Settings(self,
                            atomicity,
                            communityTargetSize,
                            communityViabilityFloor,
                            seedNodes,
                            bounds,
                            timing,
                            divergence,
                            value,
                            process,
                            jitter);
    }

    public Settings withProcess(ProcessIncarnation value) {
        return new Settings(self,
                            atomicity,
                            communityTargetSize,
                            communityViabilityFloor,
                            seedNodes,
                            bounds,
                            timing,
                            divergence,
                            submissionExpiry,
                            value,
                            jitter);
    }

    public Settings withJitter(ToDoubleFunction<String> value) {
        return new Settings(self,
                            atomicity,
                            communityTargetSize,
                            communityViabilityFloor,
                            seedNodes,
                            bounds,
                            timing,
                            divergence,
                            submissionExpiry,
                            process,
                            value);
    }
}
