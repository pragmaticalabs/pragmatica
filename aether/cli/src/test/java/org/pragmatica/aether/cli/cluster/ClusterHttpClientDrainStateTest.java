// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.cli.cluster.ClusterHttpClient.HttpError;
import org.pragmatica.http.HttpClientError;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.JdkHttpOperations;
import org.pragmatica.lang.Result;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.pragmatica.aether.cli.cluster.ScriptedDrainHttp.connectionRefused;
import static org.pragmatica.aether.cli.cluster.ScriptedDrainHttp.connectionReset;
import static org.pragmatica.aether.cli.cluster.ScriptedDrainHttp.drainAccepted;
import static org.pragmatica.aether.cli.cluster.ScriptedDrainHttp.lifecycle;
import static org.pragmatica.aether.cli.cluster.ScriptedDrainHttp.notFound;
import static org.pragmatica.aether.cli.cluster.ScriptedDrainHttp.timedOut;

/// #1868 — the CLI's drain wait polled for `state == "DECOMMISSIONED"`, a value the server never emits
/// (`NodeReportedState` is SYNCING / READY / DRAINING). The previous version of this class hand-fed that
/// literal and asserted on it: the fixture SPECIFIED the defect instead of probing it, so it stayed green
/// while every wait timed out. This one drives the wait with the shapes `NodeLifecycleRoutes` really emits
/// (see [ScriptedDrainHttp]) and states completion as what the server actually does. Polling the drained node
/// itself, only a refused connection is completion: any answer, 404 included, was served by its live process.
/// Through the cluster endpoint, the leader's 404 is the only signal (with the soft-state limits in
/// [DrainCompletion]). The two `await_real*` tests run the REAL `JdkHttpOperations` against real sockets, so the
/// scripted shapes cannot drift from what the client produces.
class ClusterHttpClientDrainStateTest {
    private static final String NODE = "core-2";
    private static final String HOST = "10.0.0.2";
    private static final long POLL_MS = 1;
    private static final long SHORT_TIMEOUT_MS = 150;
    private static final long GENEROUS_TIMEOUT_MS = 20_000;

    private HttpOperations original;

    @BeforeEach
    void captureHttp() {
        original = ClusterHttpClient.HTTP_OPS_REF.get();
    }

    @AfterEach
    void restoreHttp() {
        ClusterHttpClient.HTTP_OPS_REF.set(original);
    }

    private Result<org.pragmatica.lang.Unit> await(ScriptedDrainHttp http, long timeoutMs) {
        ClusterHttpClient.HTTP_OPS_REF.set(http);

        return ClusterHttpClient.awaitDrainComplete("http", HOST, 8080, NODE, timeoutMs, POLL_MS);
    }

    // ---- the predicate, over the real response shapes ----

    @Test
    void isComplete_stillReportingInAnyRealState_isNotComplete() {
        for (var state : new String[]{"READY", "SYNCING", "DRAINING"}) {
            var reply = ((ScriptedDrainHttp.Step.Reply) lifecycle(NODE, state)).body();

            assertFalse(DrainCompletion.isComplete(Result.success(reply)),
                        state + " is a node that still reports; it must never read as drained");
        }
    }

    /// The leader's 404 is soft state (dropped on a transient QUIC evict, after three missed pongs, empty on a new
    /// leader) and, polled on the target, was served by the target's own live process. Never completion.
    @Test
    void isComplete_notFound_isNeverComplete() {
        var body = ((ScriptedDrainHttp.Step.Reply) notFound(NODE)).body();

        assertFalse(DrainCompletion.isComplete(new HttpError.ApiError(404, body).result()));
    }

    @Test
    void isComplete_otherHttpErrors_areNotComplete() {
        for (var status : new int[]{400, 401, 403, 409, 500, 503}) {
            assertFalse(DrainCompletion.isComplete(new HttpError.ApiError(status, "{}").result()),
                        "HTTP " + status + " says nothing about the node having gone");
        }
    }

    @Test
    void isComplete_refusedConnection_isComplete() {
        var refused = HttpClientError.ConnectionFailed.connectionFailed("refused", new ConnectException("refused")).<String>result();

        assertTrue(DrainCompletion.isComplete(refused), "the drained node halted, so nothing listens on its port");
    }

    @Test
    void isComplete_connectionFailureThatIsNotARefusal_isNotComplete() {
        var reset = HttpClientError.ConnectionFailed.connectionFailed("reset", new IOException("Connection reset")).<String>result();
        var dns = HttpClientError.ConnectionFailed.connectionFailed("dns", new UnknownHostException("core-2")).<String>result();
        var bare = HttpClientError.ConnectionFailed.connectionFailed("no cause").<String>result();

        for (var failure : java.util.List.of(reset, dns, bare)) {
            assertFalse(DrainCompletion.isComplete(failure),
                        "a reset or a DNS failure is produced by live nodes too: " + failure);
        }
    }

    @Test
    void isComplete_timeout_isNeverComplete() {
        var timeout = org.pragmatica.http.HttpClientError.Timeout.timeout("slow").<String>result();

        assertFalse(DrainCompletion.isComplete(timeout),
                    "a slow node and a halted node both time out only one of them is gone");
    }

    @Test
    void isComplete_malformedBody_isNotComplete() {
        assertFalse(DrainCompletion.isComplete(Result.success("not json")));
    }

    // ---- the wait, over scripted sequences ----

    @Test
    void await_notFoundRelayedByTheLiveTarget_isNotCompletion_untilItsPortRefuses() {
        var http = new ScriptedDrainHttp(drainAccepted(NODE),
                                         lifecycle(NODE, "READY"),
                                         lifecycle(NODE, "DRAINING"),
                                         notFound(NODE),
                                         connectionReset(),
                                         connectionRefused());

        assertTrue(await(http, GENEROUS_TIMEOUT_MS).isSuccess());
        assertThat(http.lifecycleGets()).as("it polled past the 404 and the reset, to the refusal").isEqualTo(5);
    }

    @Test
    void await_notFoundForeverFromTheTarget_timesOut() {
        var http = new ScriptedDrainHttp(drainAccepted(NODE), notFound(NODE));

        var result = await(http, SHORT_TIMEOUT_MS);

        assertTrue(result.isFailure(), "the leader's soft-state 404, relayed by a live target, is not a halt");
        result.onFailure(cause -> assertThat(cause).isInstanceOf(HttpError.DrainTimeout.class));
    }

    /// Red-first for the #1868 verifier finding: through the real client, a refused connection used to reach the
    /// predicate as a generic `Failure` (a CompletionException wrapper), so the target's halt never completed a wait.
    @Test
    void await_realClosedPort_completes() throws IOException {
        int port;

        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }

        ClusterHttpClient.HTTP_OPS_REF.set(JdkHttpOperations.jdkHttpOperations());

        var result = ClusterHttpClient.awaitDrainComplete("http", "127.0.0.1", port, NODE, 5_000, POLL_MS);

        assertTrue(result.isSuccess(), () -> "a halted target's refused port must complete the wait: " + result);
    }

    @Test
    void await_realLiveTargetAnswering404_isNotCompletion() throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var body = ((ScriptedDrainHttp.Step.Reply) notFound(NODE)).body().getBytes(StandardCharsets.UTF_8);

        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(404, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            ClusterHttpClient.HTTP_OPS_REF.set(JdkHttpOperations.jdkHttpOperations());

            var result = ClusterHttpClient.awaitDrainComplete("http", "127.0.0.1", server.getAddress().getPort(), NODE,
                                                              SHORT_TIMEOUT_MS, POLL_MS);

            assertTrue(result.isFailure(), "a process that answers is alive, whatever it answers");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void await_readyThenDrainingThenConnectionRefused_completes() {
        var http = new ScriptedDrainHttp(drainAccepted(NODE),
                                         lifecycle(NODE, "READY"),
                                         lifecycle(NODE, "DRAINING"),
                                         connectionRefused());

        assertTrue(await(http, GENEROUS_TIMEOUT_MS).isSuccess());
        assertThat(http.lifecycleGets()).isEqualTo(3);
    }

    @Test
    void await_drainingForever_timesOut() {
        var http = new ScriptedDrainHttp(drainAccepted(NODE), lifecycle(NODE, "DRAINING"));

        var result = await(http, SHORT_TIMEOUT_MS);

        assertTrue(result.isFailure());
        result.onFailure(cause -> assertThat(cause).isInstanceOf(HttpError.DrainTimeout.class));
    }

    @Test
    void await_timeoutsForever_timesOutRatherThanReadingAsGone() {
        var http = new ScriptedDrainHttp(drainAccepted(NODE), timedOut());

        var result = await(http, SHORT_TIMEOUT_MS);

        assertTrue(result.isFailure(), "a node that merely stops answering in time has not been shown to be gone");
        result.onFailure(cause -> assertThat(cause).isInstanceOf(HttpError.DrainTimeout.class));
    }

    @Test
    void await_serverErrorsForever_timesOut() {
        var http = new ScriptedDrainHttp(drainAccepted(NODE), new ScriptedDrainHttp.Step.Reply(503, "{}"));

        assertTrue(await(http, SHORT_TIMEOUT_MS).isFailure());
    }

    // ---- the drain and its wait are one operation ----

    @Test
    void drainNodeAndAwait_acceptedDrainThenRefused_completes() {
        var http = new ScriptedDrainHttp(drainAccepted(NODE), connectionRefused());
        ClusterHttpClient.HTTP_OPS_REF.set(http);

        var result = ClusterHttpClient.drainNodeAndAwait("http", HOST, 8080, NODE, GENEROUS_TIMEOUT_MS);

        assertTrue(result.isSuccess());
        assertThat(http.requests().getFirst()).as("the drain is requested before anything is polled")
                                              .isEqualTo("POST /api/v1/nodes/drain/" + NODE);
    }

    /// A 404 with no drain behind it — the node had not reported yet, or never existed — looks identical
    /// to a drained node. The wait must therefore never be entered unless the drain was accepted. The
    /// drain POST itself refuses a node with no reported state with the same 404
    /// (`NodeLifecycleRoutes.checkDrainReadiness` -> `LIFECYCLE_NOT_FOUND`), which is what ends the operation.
    @Test
    void drainNodeAndAwait_refusedDrain_neverPollsAndNeverReadsAbsenceAsCompletion() {
        var http = new ScriptedDrainHttp(new ScriptedDrainHttp.Step.Reply(404, "{\"detail\":\"Node lifecycle not found\"}"),
                                         notFound(NODE));
        ClusterHttpClient.HTTP_OPS_REF.set(http);

        var result = ClusterHttpClient.drainNodeAndAwait("http", HOST, 8080, NODE, GENEROUS_TIMEOUT_MS);

        assertTrue(result.isFailure(), "an unaccepted drain is not a completed one, whatever the poll would say");
        assertThat(http.lifecycleGets()).as("no poll may run for a drain nobody accepted").isZero();
    }

    @Test
    void drainNodeAndAwait_notReadyRefusal409_isNotACompletion() {
        var http = new ScriptedDrainHttp(new ScriptedDrainHttp.Step.Reply(409, "{\"detail\":\"must be READY\"}"),
                                         notFound(NODE));
        ClusterHttpClient.HTTP_OPS_REF.set(http);

        assertTrue(ClusterHttpClient.drainNodeAndAwait("http", HOST, 8080, NODE, GENEROUS_TIMEOUT_MS).isFailure());
        assertThat(http.lifecycleGets()).isZero();
    }
}
