// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandler;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.http.HttpClientError;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;

/// A scripted management endpoint for the drain wait, speaking the shapes the REAL routes emit.
///
/// The lifecycle script is consumed one step per `GET /api/v1/nodes/lifecycle/{id}`; the last step repeats.
/// Every request is recorded as `METHOD /path`, so a test can state that a wait was (or was never) entered.
final class ScriptedDrainHttp implements HttpOperations {
    /// `NodeLifecycleRoutes.LifecycleEntry(nodeId, state, updatedAt)` — NodeLifecycleRoutes.java:98, built by
    /// `getNodeLifecycle` at :210 with `updatedAt = 0L`. The state is one of `NodeReportedState`'s three values
    /// (aether-metrics NodeReportedState.java:23-26).
    static Step lifecycle(String nodeId, String state) {
        return new Step.Reply(200, "{\"nodeId\":\"" + nodeId + "\",\"state\":\"" + state + "\",\"updatedAt\":0}");
    }

    /// `NodeLifecycleRoutes.LIFECYCLE_NOT_FOUND` (:42-46) rendered by `ProblemResponses.renderProblemBytes`
    /// (ProblemResponses.java:81) as a problem document with status 404 — the routing layer's wire shape.
    static Step notFound(String nodeId) {
        return new Step.Reply(404,
                              "{\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404,"
                              + "\"detail\":\"Node lifecycle not found\","
                              + "\"instance\":\"/api/v1/nodes/lifecycle/" + nodeId + "\",\"requestId\":\"r-1\"}");
    }

    /// What `JdkHttpOperations` produces when the polled process is gone (HttpClientError.fromException maps
    /// `ConnectException` / any `IOException` here).
    static Step connectionRefused() {
        return new Step.Fail(HttpClientError.ConnectionFailed.connectionFailed("Connection refused"));
    }

    /// A request that exceeded its timeout: a slow or partitioned node, NOT a halted one.
    static Step timedOut() {
        return new Step.Fail(HttpClientError.Timeout.timeout("request timed out"));
    }

    /// `TransitionResult(success, nodeId, state, message)` — NodeLifecycleRoutes.java:100, as returned by an
    /// accepted `POST /api/v1/nodes/drain/{id}` (`drainInitiatedResult`).
    static Step drainAccepted(String nodeId) {
        return new Step.Reply(200,
                              "{\"success\":true,\"nodeId\":\"" + nodeId + "\",\"state\":\"DRAINING\","
                              + "\"message\":\"Drain initiated\"}");
    }

    sealed interface Step {
        record Reply(int status, String body) implements Step {}

        record Fail(Cause cause) implements Step {}
    }

    private final Step drainResponse;
    private final List<Step> lifecycleScript;
    private final AtomicInteger lifecycleCalls = new AtomicInteger();
    private final List<String> requests = new CopyOnWriteArrayList<>();

    ScriptedDrainHttp(Step drainResponse, Step... lifecycleScript) {
        this.drainResponse = drainResponse;
        this.lifecycleScript = List.of(lifecycleScript);
    }

    List<String> requests() {
        return List.copyOf(requests);
    }

    long lifecycleGets() {
        return requests.stream().filter(r -> r.startsWith("GET /api/v1/nodes/lifecycle/")).count();
    }

    long drainPosts() {
        return requests.stream().filter(r -> r.startsWith("POST /api/v1/nodes/drain/")).count();
    }

    @Override
    public <T> Promise<HttpResult<T>> send(HttpRequest request, BodyHandler<T> handler) {
        requests.add(request.method() + " " + request.uri().getPath());

        var step = request.method().equals("POST")
                   ? drainResponse
                   : nextLifecycleStep();

        return respond(step);
    }

    private Step nextLifecycleStep() {
        var index = Math.min(lifecycleCalls.getAndIncrement(), lifecycleScript.size() - 1);

        return lifecycleScript.get(index);
    }

    @SuppressWarnings("unchecked")
    private static <T> Promise<HttpResult<T>> respond(Step step) {
        return switch (step) {
            case Step.Reply reply -> Promise.success(new HttpResult<>(reply.status(),
                                                                       HttpHeaders.of(Map.of(), (a, b) -> true),
                                                                       (T) reply.body()));
            case Step.Fail fail -> fail.cause().promise();
        };
    }
}
