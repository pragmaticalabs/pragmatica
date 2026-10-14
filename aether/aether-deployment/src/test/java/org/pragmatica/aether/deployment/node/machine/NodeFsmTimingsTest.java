// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.TimeoutsConfig.DeploymentTimeouts;
import org.pragmatica.cluster.node.ForwardingClusterNode;
import org.pragmatica.consensus.rabia.ProtocolConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// The bounds the node table names (spec R-3.4.3, R-3.4.4, R-1.6.3, N19), as literals. The behaviour tests derive their
/// boundaries from `NodeFsmTimings`, so a changed default moves them with it; this test is what pins the defaults themselves.
class NodeFsmTimingsTest {
    /// Red when: a default drifts from the number the spec names.
    @Test
    @DisplayName("R-3.4.3/R-3.4.4/R-3.4.10/R-1.6.3/N19: the default bounds are the spec's numbers")
    void defaults_areTheSpecNumbers() {
        var timings = NodeFsmTimings.nodeFsmTimings();

        assertThat(timings.loadMs()).as("N1: LocalOp(load, 120 s)").isEqualTo(120_000L);
        assertThat(timings.activateMs()).as("N4: activate (60 s)").isEqualTo(60_000L);
        assertThat(timings.chainBoundMs()).as("R-3.4.10: activation_chain, 90 s").isEqualTo(90_000L);
        assertThat(timings.deactivateMs()).as("N13: deactivate (30 s)").isEqualTo(30_000L);
        assertThat(timings.unloadMs()).as("N16: unload (120 s)").isEqualTo(120_000L);
        assertThat(timings.activatingCheckMs()).as("N7: activatingCheck at 90 s").isEqualTo(90_000L);
        assertThat(timings.tombstoneGraceMs()).as("R-1.6.3: tombstoneGrace 120 s").isEqualTo(120_000L);
        assertThat(timings.writeAttempts()).as("R-3.4.4: 3 attempts").isEqualTo(3);
        assertThat(timings.writeAttemptMs()).as("R-3.4.4: of 30 s + 5 s").isEqualTo(35_000L);
        assertThat(timings.failedRetryMs()).as("N19: every 2 s").isEqualTo(2_000L);
        assertThat(timings.failedCycles()).as("N19 / B-150: 6 cycles [guess]").isEqualTo(6);
        assertThat(timings.parkedBoundMs()).as("B-158: the LOADING timeout").isEqualTo(120_000L);
    }

    /// Red when: a step bound is not read from its `[timeouts.deployment]` key, or a bound with no key moves with the
    /// config (R-3.4.10). Every key carries a value no default has, so a swapped or hard-wired source shows.
    @Test
    @DisplayName("R-3.4.10: every step bound comes from its [timeouts.deployment] key")
    void from_readsEveryStepBound_fromItsConfigKey() {
        var deployment = new DeploymentTimeouts(timeSpan(201).seconds(),
                                                timeSpan(61).seconds(),
                                                timeSpan(31).seconds(),
                                                timeSpan(202).seconds(),
                                                timeSpan(91).seconds(),
                                                timeSpan(3).seconds(),
                                                timeSpan(33).seconds(),
                                                60);

        var timings = NodeFsmTimings.nodeFsmTimings(deployment, timeSpan(30).seconds());

        assertThat(timings.loadMs()).as("N1: loading").isEqualTo(201_000L);
        assertThat(timings.activateMs()).as("N4: activating").isEqualTo(61_000L);
        assertThat(timings.deactivateMs()).as("N13: deactivating").isEqualTo(31_000L);
        assertThat(timings.unloadMs()).as("N16: unloading").isEqualTo(202_000L);
        assertThat(timings.chainBoundMs()).as("N4: activation_chain").isEqualTo(91_000L);
        assertThat(timings.failedRetryMs()).as("N19: transition_retry_delay").isEqualTo(3_000L);
        assertThat(timings.parkedBoundMs()).as("B-158: the LOADING timeout").isEqualTo(201_000L);
        assertThat(timings.activatingCheckMs()).as("N7: no key, SliceState.ACTIVATING").isEqualTo(90_000L);
        assertThat(timings.tombstoneGraceMs()).as("R-1.6.3: no key").isEqualTo(120_000L);
        assertThat(timings.writeAttempts()).as("R-3.4.4: no key").isEqualTo(3);
        assertThat(timings.failedCycles()).as("P3-Q24: max_lifecycle_retries is not mapped").isEqualTo(6);
    }

    /// Red when: a write attempt does not outlast the submitting path's own timeout by the margin (P3-Q27, G-6), on a
    /// core (RE's `applyTimeout`, configured above its default here) or on a worker (the forwarder's timeout).
    @Test
    @DisplayName("R-3.4.4/NT-132: a write attempt is the submitting path's timeout + 5 s, on cores and on workers")
    void from_writeAttempt_outlastsTheSubmittingPathsTimeout() {
        var deployment = DeploymentTimeouts.deploymentTimeouts();
        var core = NodeFsmTimings.nodeFsmTimings(deployment, timeSpan(45).seconds());
        var worker = NodeFsmTimings.nodeFsmTimings(deployment, ForwardingClusterNode.forwardApplyTimeout());

        assertThat(core.writeAttemptMs()).as("core: applyTimeout 45 s + 5 s").isEqualTo(50_000L);
        assertThat(worker.writeAttemptMs()).as("worker: FORWARD_APPLY_TIMEOUT + 5 s")
                                          .isEqualTo(ForwardingClusterNode.forwardApplyTimeout()
                                                                          .millis() + 5_000L);
        assertThat(NodeFsmTimings.nodeFsmTimings()
                                 .writeAttemptMs()).as("the default is the consensus default applyTimeout + 5 s")
                                                   .isEqualTo(ProtocolConfig.DEFAULT_APPLY_TIMEOUT.millis() + 5_000L);
    }
}
