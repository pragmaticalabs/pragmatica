// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.pragmatica.aether.deployment.cluster.FenceFailure.ActivationChanged;
import org.pragmatica.aether.deployment.cluster.FenceFailure.AttemptCancelled;
import org.pragmatica.aether.deployment.cluster.FenceFailure.CalledUnderOwnerMonitor;
import org.pragmatica.aether.deployment.cluster.FenceFailure.LeadershipNotWitnessed;
import org.pragmatica.aether.deployment.cluster.FenceFailure.NotLeader;
import org.pragmatica.aether.deployment.cluster.FenceFailure.WitnessExpired;
import org.pragmatica.cluster.state.kvstore.KVCommand.RefusalKind;
import org.pragmatica.consensus.ConsensusError;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// #3754 lane (b2): the cause-to-HTTP-status table of a refused operator drain (design §3, "Cause to HTTP status"), pinned
/// row by row. The table is total: every cause of `DrainPort.request` reaches the operator as an explicit `HttpError`,
/// so the route layer's own default (500 for a cause that is not `HttpStatusAware`) is never reached.
class NodeLifecycleRoutesDrainRefusalTest {
    private static final NodeId TARGET = NodeId.nodeId("node-target").unwrap();
    private static final long APPLY_TIMEOUT_MILLIS = 30_000L;

    private static Stream<Arguments> table() {
        return Stream.of(Arguments.of("NotLeader", NotLeader.notLeader(), HttpStatus.SERVICE_UNAVAILABLE),
                         Arguments.of("LeadershipNotWitnessed(LEADER)",
                                      LeadershipNotWitnessed.leadershipNotWitnessed(RefusalKind.LEADER),
                                      HttpStatus.SERVICE_UNAVAILABLE),
                         Arguments.of("LeadershipNotWitnessed(WITNESS)",
                                      LeadershipNotWitnessed.leadershipNotWitnessed(RefusalKind.WITNESS),
                                      HttpStatus.SERVICE_UNAVAILABLE),
                         Arguments.of("ActivationChanged", ActivationChanged.activationChanged(), HttpStatus.SERVICE_UNAVAILABLE),
                         Arguments.of("WitnessExpired", WitnessExpired.witnessExpired(), HttpStatus.GATEWAY_TIMEOUT),
                         Arguments.of("ApplyTimeout",
                                      new ConsensusError.ApplyTimeout(APPLY_TIMEOUT_MILLIS),
                                      HttpStatus.GATEWAY_TIMEOUT),
                         Arguments.of("AttemptCancelled", AttemptCancelled.attemptCancelled(), HttpStatus.CONFLICT),
                         Arguments.of("CalledUnderOwnerMonitor",
                                      CalledUnderOwnerMonitor.calledUnderOwnerMonitor(TARGET),
                                      HttpStatus.INTERNAL_SERVER_ERROR),
                         Arguments.of("any other cause (default row)",
                                      Causes.cause("submit failed"),
                                      HttpStatus.SERVICE_UNAVAILABLE));
    }

    @ParameterizedTest(name = "{0} -> {2}")
    @MethodSource("table")
    void drainRefusal_answersTheTableStatus(String label, Cause cause, HttpStatus expected) {
        assertThat(NodeLifecycleRoutes.drainRefusal(cause).status()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} reaches the route layer as HttpStatusAware")
    @MethodSource("table")
    void drainRefusal_isExplicitForTheRouteLayer(String label, Cause cause, HttpStatus expected) {
        var refusal = NodeLifecycleRoutes.drainRefusal(cause);

        assertThat(refusal).isInstanceOf(HttpStatusAware.class);
        assertThat(((HttpStatusAware) refusal).httpStatus()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} keeps its message for the audit and the operator")
    @MethodSource("table")
    void drainRefusal_keepsTheCausesMessage(String label, Cause cause, HttpStatus expected) {
        assertThat(NodeLifecycleRoutes.drainRefusal(cause).message()).contains(cause.message());
    }

    /// The default row must not be read as "unknown means 500": an unlisted cause, nothing queued, is a retryable 503.
    @Test
    void drainRefusal_unlistedCause_isRetryableNotAnInternalError() {
        var status = NodeLifecycleRoutes.drainRefusal(Causes.cause("unlisted")).status();

        assertThat(status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE).isNotEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
