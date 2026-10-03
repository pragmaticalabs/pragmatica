// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.net.ConnectException;

import org.pragmatica.http.HttpClientError;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;


/// The definitions of "the drained node has gone", shared by every CLI drain wait. Which one applies depends on
/// WHAT WAS POLLED, because a signal that is sound for one poll is unsound for the other.
///
/// **Polling the drained TARGET's own management address ([#isComplete]):** complete means exactly one thing: that
/// address refuses the connection (`java.net.ConnectException`, nothing listens on the port), observed after the
/// drain was accepted. A drained node runs `DrainProcedure`, `Runtime.halt(2)`s, and its listener goes away. No HTTP
/// answer is completion, a 404 included: the lifecycle GET is `LEADER`-routed, so the target forwards it and
/// relays the leader's answer; a 404 on the target's port proves the target is alive. A 200 in any state, a 503, a
/// timeout, and a connection failure that is not a refusal (DNS, a reset mid-request) are not completion either.
///
/// **Polling through the cluster endpoint ([#isDeparted]):** the endpoint is some other live member, whose connection
/// failure says nothing about the target, but whose lifecycle GET answers 404 only once MEMBERSHIP has committed the
/// node's departure (`MembershipFsm` state Dead; `NodeLifecycleRoutes.getNodeLifecycle`). A node merely missing from
/// the soft readiness view (transient QUIC evict, three missed pongs, a new leader's empty map) answers 503
/// "readiness unknown", so a 404 here carries a committed fact. Everything else is not departure.
///
/// An absence proves nothing unless a drain actually started. That is structural: every caller reaches these
/// predicates only after the drain command was ACCEPTED (admission requires the node to be READY, see
/// `NodeLifecycleRoutes.checkDrainReadiness`).
public sealed interface DrainCompletion {
    record unused() implements DrainCompletion {}

    static boolean isComplete(Result<String> pollOfTarget) {
        return pollOfTarget.fold(DrainCompletion::isRefusedConnection, _ -> false);
    }

    static boolean isDeparted(Result<String> clusterEndpointPoll) {
        return clusterEndpointPoll.fold(DrainCompletion::isNotFound, _ -> false);
    }

    private static boolean isNotFound(Cause cause) {
        return switch (cause) {
            case ClusterHttpClient.HttpError.ApiError apiError -> apiError.statusCode() == 404;
            default -> false;
        };
    }

    private static boolean isRefusedConnection(Cause cause) {
        return switch (cause) {
            case HttpClientError.ConnectionFailed failed -> failed.cause().map(ConnectException.class::isInstance).or(false);
            default -> false;
        };
    }
}
