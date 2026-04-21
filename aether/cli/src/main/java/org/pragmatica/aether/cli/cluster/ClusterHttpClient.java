// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.http.JdkHttpOperations;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;

import static org.pragmatica.lang.Option.option;


/// Shared HTTP client for cluster CLI commands that call the management API.
public sealed interface ClusterHttpClient {
    record unused() implements ClusterHttpClient{}

    HttpOperations HTTP_OPS = JdkHttpOperations.jdkHttpOperations();

    java.util.concurrent.atomic.AtomicReference<String> ENDPOINT_OVERRIDE = new java.util.concurrent.atomic.AtomicReference<>();

    java.util.concurrent.atomic.AtomicReference<String> API_KEY_OVERRIDE = new java.util.concurrent.atomic.AtomicReference<>();

    @Contract static void setEndpointOverride(String endpointUrl) {
        ENDPOINT_OVERRIDE.set(endpointUrl);
    }

    @Contract static void setApiKeyOverride(String apiKey) {
        API_KEY_OVERRIDE.set(apiKey);
    }

    static Result<String> fetch(ManagementRoute route, List<String> params) {
        return route.assemble(params).flatMap(ClusterHttpClient::fetchPath);
    }

    static Result<String> fetch(ManagementRoute route) {
        return fetch(route, List.of());
    }

    static Result<String> fetch(ManagementRoute route, List<String> params, String queryString) {
        return route.assemble(params).map(path -> queryString == null || queryString.isEmpty()
                                                 ? path
                                                 : path + "?" + queryString)
                             .flatMap(ClusterHttpClient::fetchPath);
    }

    static Result<String> post(ManagementRoute route, List<String> params, String jsonBody) {
        return route.assemble(params).flatMap(path -> postPath(path, jsonBody));
    }

    static Result<String> post(ManagementRoute route, String jsonBody) {
        return post(route, List.of(), jsonBody);
    }

    static Result<String> post(ManagementRoute route, List<String> params, String queryString, String jsonBody) {
        return route.assemble(params).map(path -> queryString == null || queryString.isEmpty()
                                                 ? path
                                                 : path + "?" + queryString)
                             .flatMap(path -> postPath(path, jsonBody));
    }

    static Result<String> put(ManagementRoute route, List<String> params, String jsonBody) {
        return route.assemble(params).flatMap(path -> putPath(path, jsonBody));
    }

    @SuppressWarnings({"JBCT-UTIL-01", "JBCT-SEQ-01"}) private static Result<String> fetchPath(String path) {
        return resolveEndpoint().flatMap(endpoint -> doGet(endpoint, path));
    }

    @SuppressWarnings({"JBCT-UTIL-01", "JBCT-SEQ-01"}) private static Result<String> postPath(String path,
                                                                                              String jsonBody) {
        return resolveEndpoint().flatMap(endpoint -> doPost(endpoint, path, jsonBody));
    }

    @SuppressWarnings({"JBCT-UTIL-01", "JBCT-SEQ-01"}) private static Result<String> putPath(String path,
                                                                                             String jsonBody) {
        return resolveEndpoint().flatMap(endpoint -> doPut(endpoint, path, jsonBody));
    }

    static Result<String> resolveEndpoint() {
        var override = ENDPOINT_OVERRIDE.get();
        if (override != null && !override.isBlank()) {return Result.success(override);}
        return ClusterRegistry.load().flatMap(ClusterHttpClient::extractEndpoint);
    }

    private static Result<String> extractEndpoint(ClusterRegistry registry) {
        return registry.current().map(ClusterRegistry.ClusterEntry::endpoint)
                               .toResult(HttpError.NO_ACTIVE_CLUSTER);
    }

    @SuppressWarnings({"JBCT-UTIL-01", "JBCT-SEQ-01"}) private static Result<String> doGet(String endpoint,
                                                                                           String path) {
        var uri = URI.create(endpoint + path);
        var apiKey = resolveApiKey();
        var builder = HttpRequest.newBuilder().uri(uri)
                                            .GET();
        apiKey.onPresent(key -> builder.header("X-API-Key", key));
        return HTTP_OPS.sendString(builder.build()).await()
                                  .flatMap(ClusterHttpClient::extractBody);
    }

    @SuppressWarnings({"JBCT-UTIL-01", "JBCT-SEQ-01"}) private static Result<String> doPost(String endpoint,
                                                                                            String path,
                                                                                            String jsonBody) {
        var uri = URI.create(endpoint + path);
        var apiKey = resolveApiKey();
        var builder = HttpRequest.newBuilder().uri(uri)
                                            .header("Content-Type", "application/json")
                                            .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        apiKey.onPresent(key -> builder.header("X-API-Key", key));
        return HTTP_OPS.sendString(builder.build()).await()
                                  .flatMap(ClusterHttpClient::extractBody);
    }

    @SuppressWarnings({"JBCT-UTIL-01", "JBCT-SEQ-01"}) private static Result<String> doPut(String endpoint,
                                                                                           String path,
                                                                                           String jsonBody) {
        var uri = URI.create(endpoint + path);
        var apiKey = resolveApiKey();
        var builder = HttpRequest.newBuilder().uri(uri)
                                            .header("Content-Type", "application/json")
                                            .PUT(HttpRequest.BodyPublishers.ofString(jsonBody));
        apiKey.onPresent(key -> builder.header("X-API-Key", key));
        return HTTP_OPS.sendString(builder.build()).await()
                                  .flatMap(ClusterHttpClient::extractBody);
    }

    private static Result<String> extractBody(HttpResult<String> response) {
        return response.statusCode() >= 200 && response.statusCode() <300
              ? Result.success(response.body())
              : new HttpError.ApiError(response.statusCode(), response.body()).result();
    }

    private static Option<String> resolveApiKey() {
        var override = API_KEY_OVERRIDE.get();
        if (override != null && !override.isBlank()) {return option(override);}
        return ClusterRegistry.load().option()
                                   .flatMap(ClusterRegistry::current)
                                   .flatMap(ClusterRegistry.ClusterEntry::apiKeyEnv)
                                   .flatMap(envName -> option(System.getenv(envName)));
    }

    @SuppressWarnings({"JBCT-UTIL-01", "JBCT-SEQ-01"}) static Result<String> getDirect(String url) {
        var builder = HttpRequest.newBuilder().uri(URI.create(url))
                                            .GET();
        return HTTP_OPS.sendString(builder.build()).await()
                                  .flatMap(ClusterHttpClient::extractBody);
    }

    @SuppressWarnings({"JBCT-UTIL-01", "JBCT-SEQ-01"}) static Result<String> postDirect(String url, String jsonBody) {
        var builder = HttpRequest.newBuilder().uri(URI.create(url))
                                            .header("Content-Type", "application/json")
                                            .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        return HTTP_OPS.sendString(builder.build()).await()
                                  .flatMap(ClusterHttpClient::extractBody);
    }

    static Result<Unit> drainNode(String address, int managementPort, String nodeId) {
        var url = "http://" + address + ":" + managementPort + "/api/node/drain/" + nodeId;
        return postDirect(url, "{}").mapToUnit();
    }

    static Result<Unit> waitForNodeReady(String address, int managementPort, long timeoutMs) {
        var url = "http://" + address + ":" + managementPort + "/health/ready";
        var deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() <deadline) {
            if (getDirect(url).isSuccess()) {return Result.unitResult();}
            ClusterBootstrapOrchestrator.sleepQuietly(2000);
        }
        return new HttpError.NodeNotReady(address, timeoutMs).result();
    }

    static Result<String> checkClusterHealth(String address, int managementPort) {
        var url = "http://" + address + ":" + managementPort + "/api/health";
        return getDirect(url);
    }

    static Result<Unit> waitForDrainComplete(String address, int managementPort, String nodeId, long timeoutMs) {
        var url = "http://" + address + ":" + managementPort + "/api/node/lifecycle/" + nodeId;
        var deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() <deadline) {
            var stateResult = getDirect(url);
            if (stateResult.map(body -> body.contains("DECOMMISSIONED")).or(false)) {return Result.unitResult();}
            ClusterBootstrapOrchestrator.sleepQuietly(2000);
        }
        return new HttpError.DrainTimeout(nodeId, timeoutMs).result();
    }

    sealed interface HttpError extends Cause {
        HttpError NO_ACTIVE_CLUSTER = new SimpleError("No active cluster context. Use 'aether cluster use <name>' to select one.");

        record SimpleError(String message) implements HttpError{}

        record ApiError(int statusCode, String body) implements HttpError {
            @Override public String message() {
                return "HTTP " + statusCode + ": " + body;
            }
        }

        record NodeNotReady(String address, long timeoutMs) implements HttpError {
            @Override public String message() {
                return "Node " + address + " did not become ready within " + timeoutMs + "ms";
            }
        }

        record DrainTimeout(String nodeId, long timeoutMs) implements HttpError {
            @Override public String message() {
                return "Node " + nodeId + " did not complete drain within " + timeoutMs + "ms";
            }
        }
    }
}
