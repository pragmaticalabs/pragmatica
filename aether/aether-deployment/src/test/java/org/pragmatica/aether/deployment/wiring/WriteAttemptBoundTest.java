// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.wiring;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.TimeoutsConfig.DeploymentTimeouts;
import org.pragmatica.aether.deployment.node.machine.NodeFsmInput.WriteOutcome;
import org.pragmatica.aether.deployment.node.machine.NodeFsmTimings;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.cluster.node.ForwardingClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.rabia.ProtocolConfig;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.wiring.WiringFixtures.*;

/// W-35, core and worker arms (phase-3 design 9.2, P3-Q27, G-6): the node FSM's write-attempt timer must fire AFTER the path's own timeout,
/// so the path's verdict (`Failed(ApplyTimeout)`) always arrives first and the FSM re-derives from a settled write. Measured on an injected
/// clock with the REAL timeout constants: `ProtocolConfig.DEFAULT_APPLY_TIMEOUT` (the engine's `applyTimeout` default) and
/// `ForwardingClusterNode.forwardApplyTimeout()`. Which constant a given node is built with is `DeploymentWiring`'s binding (PR C).
class WriteAttemptBoundTest {
    private final ManualTimerService clock = new ManualTimerService();

    private void assertSettlementPrecedesTheAttemptTimer(TimeSpan pathTimeout) {
        var timings = NodeFsmTimings.nodeFsmTimings(DeploymentTimeouts.deploymentTimeouts(), pathTimeout);
        var cluster = new StubCluster();
        var applyResult = Promise.<List<Object>>promise();
        var settledAt = new AtomicLong(-1);

        cluster.script = _ -> applyResult;
        clock.schedule(() -> {}, timings.writeAttemptMs());
        clock.schedule(() -> applyResult.fail(Causes.cause("apply timeout")), pathTimeout.millis());
        Submitter.submitter(() -> cluster, Submitter.CommittedReader.committedReader(store()))
                 .submitBatch(List.<KVCommand<AetherKey>>of(new KVCommand.Put<>(desiredKey(ORDERS), desired(org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal.ACTIVE))))
                 .onSuccess(outcome -> settledAt.set(outcome instanceof WriteOutcome.Failed ? clock.nowMs : -2));

        clock.advance(pathTimeout.millis());
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() -> settledAt.get() != -1);

        assertThat(settledAt.get()).as("the path's verdict arrived at its own timeout").isEqualTo(pathTimeout.millis());
        assertThat(clock.entries.getFirst().ran).as("the attempt timer has not fired when the verdict arrives").isFalse();
        assertThat(clock.entries.getFirst().dueMs).as("the attempt timer is due strictly after the path's timeout").isGreaterThan(pathTimeout.millis());
    }

    @Test
    void core_writeConfirm_isDueAfterTheEnginesApplyTimeout() {
        assertSettlementPrecedesTheAttemptTimer(ProtocolConfig.DEFAULT_APPLY_TIMEOUT);
    }

    @Test
    void worker_writeConfirm_isDueAfterTheForwardingApplyTimeout() {
        assertSettlementPrecedesTheAttemptTimer(ForwardingClusterNode.forwardApplyTimeout());
    }

    @Test
    void writeConfirm_followsAConfiguredApplyTimeout_notTheDefault() {
        var longer = TimeSpan.timeSpan(45).seconds();

        assertSettlementPrecedesTheAttemptTimer(longer);
    }
}
