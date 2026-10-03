// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.net.ConnectException;

import org.pragmatica.http.HttpClientError;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;


/// The one definition of "the drained node has gone", shared by every CLI drain wait.
///
/// **Complete means exactly one thing: the TARGET's own management address refuses the connection**
/// (`java.net.ConnectException`, nothing listens on the port), observed after the drain was accepted. A
/// drained node runs `DrainProcedure`, `Runtime.halt(2)`s, and its listener goes away; `NodeReportedState`
/// (SYNCING / READY / DRAINING) has no terminal value to read instead.
///
/// **No HTTP answer is ever completion, a 404 included.** The lifecycle GET is `LEADER`-routed, so the target
/// forwards it and relays the leader's answer; a 404 therefore proves the target is alive (it served it), and
/// the leader's 404 itself is soft state, not a death verdict: its readiness view drops a LIVE node on a
/// transient QUIC evict (`AetherNode` routes `PeerDisconnected` to `pongSignalFan.evict`), after three silent
/// ping intervals (`sweepStale`), and is empty on a newly elected leader until the first pongs arrive. A 200 in
/// any state (READY, SYNCING, DRAINING), a 503, a timeout, and a connection failure that is not a refusal (DNS,
/// a reset mid-request: live nodes produce those too) are likewise not completion.
///
/// **Only a poll of the target's own address can complete.** A poll through the cluster endpoint reaches some
/// other member, whose connection failure says nothing about the target and whose 404 is the soft-state answer
/// above, so there is no sound completion signal there; those callers report [NotObservable] instead.
///
/// An absence proves nothing unless a drain actually started. That is structural: every caller reaches this
/// predicate only after the drain command was ACCEPTED (admission requires the node to be READY, see
/// `NodeLifecycleRoutes.checkDrainReadiness`).
public sealed interface DrainCompletion {
    /// A drain the server accepted whose completion this CLI invocation has no sound way to observe: it holds
    /// the node id but not the node's own management address, and polling through the cluster endpoint cannot
    /// tell a halted node from a live one.
    record NotObservable(String nodeId) implements DrainCompletion, Cause {
        @Override
        public String message() {
            return "drain of " + nodeId + " was accepted, but its completion cannot be observed through the cluster"
                   + " endpoint: only the node's own address refusing connections proves it halted, and the CLI does"
                   + " not know that address";
        }
    }

    static boolean isComplete(Result<String> pollOfTarget) {
        return pollOfTarget.fold(DrainCompletion::isRefusedConnection, _ -> false);
    }

    private static boolean isRefusedConnection(Cause cause) {
        return switch (cause) {
            case HttpClientError.ConnectionFailed failed -> failed.cause().map(ConnectException.class::isInstance).or(false);
            default -> false;
        };
    }
}
