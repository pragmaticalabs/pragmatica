// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import org.pragmatica.aether.config.TimeoutsConfig.DeploymentTimeouts;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.consensus.rabia.ProtocolConfig;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Every bound the node FSM applies, in milliseconds. The step bounds come from `[timeouts.deployment]` (spec R-3.4.10),
/// so operator overrides apply; the bounds with no config key are the spec's own numbers.
public record NodeFsmTimings(long loadMs,
                             long activateMs,
                             long deactivateMs,
                             long unloadMs,
                             long chainBoundMs,
                             long activatingCheckMs,
                             long tombstoneGraceMs,
                             long parkedBoundMs,
                             long writeAttemptMs,
                             int writeAttempts,
                             long failedRetryMs,
                             int failedCycles) {
    private static final long ACTIVATING_CHECK_MS = 90_000L;
    private static final long TOMBSTONE_GRACE_MS = 120_000L;
    /// How long after the submitting path's own timeout a write attempt is given up (P3-Q27, G-6) [guess: the margin].
    private static final TimeSpan WRITE_ATTEMPT_MARGIN = timeSpan(5).seconds();
    private static final int WRITE_ATTEMPTS = 3;
    private static final int FAILED_CYCLES = 6;

    /// Config defaults with the consensus default apply timeout as the submitting path's: load 2 min, activate 60 s,
    /// chain 90 s, deactivate 30 s, unload 2 min, ACTIVATING check 90 s, tombstone grace 120 s, parked-start escalation
    /// at the LOADING bound, 3 attempts of 35 s per write, 6 FAILED cycles 2 s apart.
    public static NodeFsmTimings nodeFsmTimings() {
        return nodeFsmTimings(DeploymentTimeouts.deploymentTimeouts(), ProtocolConfig.DEFAULT_APPLY_TIMEOUT);
    }

    /// The bounds for a node whose writes go through a path that gives up after `submitTimeout`: RE's `applyTimeout` on
    /// a core, `ForwardingClusterNode.forwardApplyTimeout()` on a worker (spec R-3.4.4, R-3.4.10). A write attempt
    /// outlasts that timeout by a margin, so the path's own verdict always arrives first.
    public static NodeFsmTimings nodeFsmTimings(DeploymentTimeouts deployment, TimeSpan submitTimeout) {
        var load = deployment.loading().millis();

        return new NodeFsmTimings(load,
                                  deployment.activating().millis(),
                                  deployment.deactivating().millis(),
                                  deployment.unloading().millis(),
                                  deployment.activationChain().millis(),
                                  of(SliceState.ACTIVATING),
                                  TOMBSTONE_GRACE_MS,
                                  load,
                                  submitTimeout.plus(WRITE_ATTEMPT_MARGIN).millis(),
                                  WRITE_ATTEMPTS,
                                  deployment.transitionRetryDelay().millis(),
                                  FAILED_CYCLES);
    }

    /// The bound of a local step.
    public long boundOf(LocalStep step) {
        return switch (step) {
            case LOAD -> loadMs;
            case ACTIVATE -> activateMs;
            case DEACTIVATE -> deactivateMs;
            case UNLOAD, FORCED_CLEANUP -> unloadMs;
            default -> chainBoundMs;
        };
    }

    private static long of(SliceState state) {
        return state.timeout()
                    .map(TimeSpan::millis)
                    .or(ACTIVATING_CHECK_MS);
    }
}
