// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.http.HttpClientError;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

/// The one definition of "this node's drain has completed", shared by every CLI drain wait.
///
/// Built only from what the server produces. `NodeReportedState` has three values (SYNCING, READY,
/// DRAINING) and no terminal one: a drained node runs `DrainProcedure`, `Runtime.halt(2)`s, and simply
/// stops reporting, and the leader sweeps its readiness entry after three missed pings. So completion is
/// an ABSENCE, and the only observables of it are:
///
/// - `GET /api/v1/nodes/lifecycle/{id}` answering 404 (`NodeLifecycleRoutes.LIFECYCLE_NOT_FOUND`: the
///   serving node's readiness view no longer holds the id);
/// - the polled node itself refusing or resetting the connection (`HttpClientError.ConnectionFailed`),
///   because the process halted.
///
/// A 200 in ANY state (READY before the leader's ping carries the drain, SYNCING, DRAINING) means the node
/// is still reporting, so the drain is not complete.
///
/// An absence proves nothing unless a drain actually started — a pre-first-pong 404, or a node that was never
/// reachable, looks identical. That precondition is not checked here; it is structural: every caller reaches
/// this predicate only after the drain command was ACCEPTED (admission requires the node to be READY, see
/// `NodeLifecycleRoutes.checkDrainReadiness`).
///
/// A timeout is deliberately NOT completion: a slow or partitioned node and a halted one both time out.
public sealed interface DrainCompletion {
    record unused() implements DrainCompletion {}

    /// What the poll was addressed to. A connection failure is evidence about the polled process only, so
    /// it can speak for the drain target only when the target was the one polled.
    enum Polled {
        /// The poll went to the node being drained: its halt is observable as a connection failure.
        TARGET_NODE,
        /// The poll went to the cluster's management endpoint, which may be any member (or the target's
        /// peer that itself halts): a connection failure says nothing about the target.
        CLUSTER_ENDPOINT
    }

    static boolean isComplete(Result<String> lifecycleResponse, Polled polled) {
        return lifecycleResponse.fold(cause -> isAbsence(cause, polled), _ -> false);
    }

    private static boolean isAbsence(Cause cause, Polled polled) {
        return switch (cause) {
            case ClusterHttpClient.HttpError.ApiError apiError -> apiError.statusCode() == 404;
            case HttpClientError.ConnectionFailed _ -> polled == Polled.TARGET_NODE;
            default -> false;
        };
    }
}
