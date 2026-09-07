// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

/// #727 — the cluster state a bounded wait prints when it expires.
///
/// Extracted from `SliceInvocationTest` in review round 1 so the two cases that matter can be pinned
/// by a test instead of asserted in a comment: a cluster whose live registry has been cleared, and a
/// node whose health endpoint cannot be reached. Every value rendered here is either something this
/// renderer actually read or a NAMED absence. Nothing is defaulted, because the defect this exists to
/// close (review B1) was exactly a default that read as an observation — `EmberCluster` passed the
/// string literal `"healthy"` into every `NodeStatus`, so every dump reported three healthy nodes
/// however broken the cluster was, and the reader of a formation stall looked in the wrong place.
final class ClusterSnapshot {
    /// The named absence for "there is nothing to report", used when the cluster holds no running node
    /// AND no start of it has failed. Distinguishing this from a start failure is review B2: a failed
    /// start clears `nodes`/`nodeInfos` before the caller sees the failure, so an un-named empty
    /// snapshot on a start failure is indistinguishable from a cluster that was never asked to start.
    static final String NO_STATE =
        "  no cluster state: no node is registered and no start of this cluster has failed";

    private static final TimeSpan HEALTH_BOUND = TimeSpan.timeSpan(30).seconds();
    private static final Duration HEALTH_REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private ClusterSnapshot() {}

    static String render(EmberCluster cluster, HttpOperations http) {
        return render(cluster.status(), cluster.lastStartFailure(), http);
    }

    /// Split from the accessor above so the branch — live registry, retained failure, or neither —
    /// is drivable from a test without starting and then breaking a real cluster.
    static String render(EmberCluster.ClusterStatus live,
                         Option<EmberCluster.StartFailure> startFailure,
                         HttpOperations http) {
        return render(live, startFailure, node -> liveNodeLine(node, http));
    }

    /// #915 — the same three branches, for a caller that holds no [HttpOperations] to probe with.
    ///
    /// The lifecycle awaits this serves are in `@BeforeAll`/`@AfterAll` across 36 classes, most of
    /// which never build an http client; and a health probe on a `stop` that failed would answer
    /// about the aftermath. What the endpoint would have told us — whether the node is
    /// consensus-active — is already in [EmberCluster.NodeStatus#state] since #913.
    static String render(EmberCluster.ClusterStatus live, Option<EmberCluster.StartFailure> startFailure) {
        return render(live, startFailure, ClusterSnapshot::nodeLine);
    }

    private static String render(EmberCluster.ClusterStatus live,
                                 Option<EmberCluster.StartFailure> startFailure,
                                 Function<EmberCluster.NodeStatus, String> lineOf) {
        if (!live.nodes().isEmpty()) {
            return "  leader=" + live.leaderId() + "\n"
                   + live.nodes()
                         .stream()
                         .map(lineOf)
                         .collect(Collectors.joining("\n"));
        }
        return startFailure.map(ClusterSnapshot::renderCaptured)
                           .or(NO_STATE);
    }

    /// The captured snapshot is rendered WITHOUT probing any health endpoint: the nodes it names were
    /// stopped by the abort that produced it, so a probe now would answer about the aftermath and read
    /// as if it described the failure. The label says which of the two this is.
    private static String renderCaptured(EmberCluster.StartFailure failure) {
        var failures = failure.nodeFailures().isEmpty()
                       ? "no node reported a cause"
                       : failure.nodeFailures().toString();

        return "  leader=" + failure.status().leaderId()
               + " (captured when the start failed; the abort has since cleared the live cluster"
               + " state, so no health endpoint is probed here)\n"
               + failure.status()
                        .nodes()
                        .stream()
                        .map(ClusterSnapshot::nodeLine)
                        .collect(Collectors.joining("\n"))
               + "\n  start failures: " + failures;
    }

    static String liveNodeLine(EmberCluster.NodeStatus node, HttpOperations http) {
        return nodeLine(node) + " health=" + healthBody(node.mgmtPort(), http);
    }

    /// #915 added the two ports. The canonical failure this dump is read for is a port that could
    /// not be bound, and the id alone does not say which port the node was asked to take.
    private static String nodeLine(EmberCluster.NodeStatus node) {
        return "  " + node.id() + " state=" + node.state() + " leader=" + node.isLeader()
               + " port=" + node.port() + " mgmt=" + node.mgmtPort();
    }

    /// Bounded, unlike the `await()` this replaced (review N1): the failure path is the one that runs
    /// when things are already wrong, and an unbounded await there re-parks past JUnit's interrupt.
    /// The bound is far above the request's own 5s timeout, so it fires only if the promise never
    /// settles at all — and when it does, the reason is named rather than defaulted.
    private static String healthBody(int port, HttpOperations http) {
        return http.sendString(healthRequest(port))
                   .await(HEALTH_BOUND)
                   .fold(cause -> "unavailable (" + cause.message() + ")",
                         response -> response.statusCode() + " " + response.body());
    }

    static HttpRequest healthRequest(int port) {
        return HttpRequest.newBuilder()
                          .uri(URI.create("http://localhost:" + port + "/api/v1/health"))
                          .GET()
                          .timeout(HEALTH_REQUEST_TIMEOUT)
                          .build();
    }
}
