// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.cli.cluster.ClusterHttpClient.HttpError;
import org.pragmatica.aether.cli.cluster.DrainCompletion.Polled;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.pragmatica.aether.cli.cluster.ScriptedDrainHttp.connectionRefused;
import static org.pragmatica.aether.cli.cluster.ScriptedDrainHttp.drainAccepted;
import static org.pragmatica.aether.cli.cluster.ScriptedDrainHttp.lifecycle;
import static org.pragmatica.aether.cli.cluster.ScriptedDrainHttp.notFound;
import static org.pragmatica.aether.cli.cluster.ScriptedDrainHttp.timedOut;

/// #1868 — the CLI's drain wait polled for `state == "DECOMMISSIONED"`, a value the server never emits
/// (`NodeReportedState` is SYNCING / READY / DRAINING). The previous version of this class hand-fed that
/// literal and asserted on it: the fixture SPECIFIED the defect instead of probing it, so it stayed green
/// while every wait timed out. This one drives the wait with the shapes `NodeLifecycleRoutes` really emits
/// (see [ScriptedDrainHttp]) and states completion as what the server actually does: the entry disappears
/// (404) or the halted process stops answering.
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
            for (var polled : Polled.values()) {
                var reply = ((ScriptedDrainHttp.Step.Reply) lifecycle(NODE, state)).body();

                assertFalse(DrainCompletion.isComplete(Result.success(reply), polled),
                            state + " is a node that still reports; it must never read as drained");
            }
        }
    }

    @Test
    void isComplete_notFound_isComplete() {
        var body = ((ScriptedDrainHttp.Step.Reply) notFound(NODE)).body();

        assertTrue(DrainCompletion.isComplete(new HttpError.ApiError(404, body).result(), Polled.TARGET_NODE));
        assertTrue(DrainCompletion.isComplete(new HttpError.ApiError(404, body).result(), Polled.CLUSTER_ENDPOINT));
    }

    @Test
    void isComplete_otherHttpErrors_areNotComplete() {
        for (var status : new int[]{400, 401, 403, 409, 500, 503}) {
            assertFalse(DrainCompletion.isComplete(new HttpError.ApiError(status, "{}").result(), Polled.TARGET_NODE),
                        "HTTP " + status + " says nothing about the node having gone");
        }
    }

    @Test
    void isComplete_connectionFailure_speaksForTheTargetOnlyWhenTheTargetWasPolled() {
        var refused = org.pragmatica.http.HttpClientError.ConnectionFailed.connectionFailed("refused").<String>result();

        assertTrue(DrainCompletion.isComplete(refused, Polled.TARGET_NODE),
                   "the drained node halted, so its own port stops answering");
        assertFalse(DrainCompletion.isComplete(refused, Polled.CLUSTER_ENDPOINT),
                    "an unreachable cluster endpoint says nothing about whether the target drained");
    }

    @Test
    void isComplete_timeout_isNeverComplete() {
        var timeout = org.pragmatica.http.HttpClientError.Timeout.timeout("slow").<String>result();

        assertFalse(DrainCompletion.isComplete(timeout, Polled.TARGET_NODE),
                    "a slow node and a halted node both time out only one of them is gone");
    }

    @Test
    void isComplete_malformedBody_isNotComplete() {
        assertFalse(DrainCompletion.isComplete(Result.success("not json"), Polled.TARGET_NODE));
    }

    // ---- the wait, over scripted sequences ----

    @Test
    void await_readyThenDrainingThenNotFound_completes() {
        var http = new ScriptedDrainHttp(drainAccepted(NODE),
                                         lifecycle(NODE, "READY"),
                                         lifecycle(NODE, "DRAINING"),
                                         notFound(NODE));

        assertTrue(await(http, GENEROUS_TIMEOUT_MS).isSuccess());
        assertThat(http.lifecycleGets()).as("it kept polling through READY and DRAINING").isEqualTo(3);
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
    void drainNodeAndAwait_acceptedDrainThenNotFound_completes() {
        var http = new ScriptedDrainHttp(drainAccepted(NODE), notFound(NODE));
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
