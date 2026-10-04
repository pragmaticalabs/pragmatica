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
    /// (ProblemResponses.java:81) as a problem document with status 404 — the routing layer's wire shape. The
    /// route is `LEADER`-targeted, so this is the leader's soft-state readiness view, relayed by whichever live
    /// node was polled.
    static Step notFound(String nodeId) {
        return new Step.Reply(404,
                              "{\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404,"
                              + "\"detail\":\"Node lifecycle not found\","
                              + "\"instance\":\"/api/v1/nodes/lifecycle/" + nodeId + "\",\"requestId\":\"r-1\"}");
    }

    /// Transport failures are scripted as the RAW exception the JDK client throws, and reach the caller only
    /// through `HttpClientError.fromException` wrapped in a `CompletionException` exactly as
    /// `JdkHttpOperations.send` delivers it — so a step can only produce what the real client produces. (An
    /// earlier version scripted a ready-made `ConnectionFailed`, which the real client never delivered; the
    /// fixture specified the defect.)
    static Step connectionRefused() {
        return new Step.Fail(new java.net.ConnectException());
    }

    /// A mid-request transport failure (`IOException`, e.g. a reset) that a LIVE node produces too.
    static Step connectionReset() {
        return new Step.Fail(new java.io.IOException("Connection reset"));
    }

    /// A request that exceeded its timeout: a slow or partitioned node, NOT a halted one.
    static Step timedOut() {
        return new Step.Fail(new java.net.http.HttpTimeoutException("request timed out"));
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

        record Fail(Throwable error) implements Step {}
    }

    private final Step drainResponse;
    private final List<Step> lifecycleScript;
    private final AtomicInteger lifecycleCalls = new AtomicInteger();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> requestsWithQuery = new CopyOnWriteArrayList<>();

    ScriptedDrainHttp(Step drainResponse, Step... lifecycleScript) {
        this.drainResponse = drainResponse;
        this.lifecycleScript = List.of(lifecycleScript);
    }

    private final Map<String, String> transportAddresses = new java.util.concurrent.ConcurrentHashMap<>();

    /// `GET /api/v1/nodes/endpoint/{id}` answers `NodeEndpointResponse(nodeId, address, reachable)`
    /// (ManagementApiResponses.java:47-60) — the node's cluster-transport `host:port`.
    ScriptedDrainHttp withTransportAddress(String nodeId, String hostPort) {
        transportAddresses.put(nodeId, hostPort);

        return this;
    }

    /// Node ids in the order their drain was requested.
    List<String> drainOrder() {
        return requests.stream()
                       .filter(r -> r.startsWith("POST /api/v1/nodes/drain/"))
                       .map(r -> r.substring("POST /api/v1/nodes/drain/".length()))
                       .toList();
    }

    List<String> requests() {
        return List.copyOf(requests);
    }

    /// Every request as `METHOD path?query` — `requests()` strips the query, which the #1720 `force` flag lives in.
    List<String> requestsWithQuery() {
        return List.copyOf(requestsWithQuery);
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
        requestsWithQuery.add(request.method() + " " + request.uri().getPath()
                              + (request.uri().getRawQuery() == null ? "" : "?" + request.uri().getRawQuery()));

        var path = request.uri().getPath();
        var step = request.method().equals("POST")
                   ? postStep(path)
                   : path.startsWith("/api/v1/nodes/endpoint/")
                     ? endpointStep(path.substring("/api/v1/nodes/endpoint/".length()))
                     : nextLifecycleStep();

        return respond(step);
    }

    private Step postStep(String path) {
        return path.startsWith("/api/v1/nodes/drain/")
               ? drainResponse
               : new Step.Reply(200, "{\"success\":true,\"message\":\"ok\"}");
    }

    private Step endpointStep(String nodeId) {
        var address = transportAddresses.get(nodeId);

        return address == null
               ? new Step.Reply(503, "{}")
               : new Step.Reply(200, "{\"nodeId\":\"" + nodeId + "\",\"address\":\"" + address + "\",\"reachable\":true}");
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
            case Step.Fail fail -> HttpClientError.fromException(new java.util.concurrent.CompletionException(fail.error())).<HttpResult<T>> promise();
        };
    }
}
