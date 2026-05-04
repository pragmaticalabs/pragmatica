// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapContext;
import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapError;
import org.pragmatica.aether.environment.NodeAddress;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.pragmatica.aether.cli.cluster.BootstrapPhase.CLUSTER_FORMATION;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"}) sealed interface BootstrapPhaseFormation {
    record unused() implements BootstrapPhaseFormation{}

    long STORE_RETRY_BUDGET_MS = 60_000L;
    long STORE_RETRY_INTERVAL_MS = 2_000L;

    static Result<BootstrapContext> execute(BootstrapContext ctx) {
        ClusterBootstrapOrchestrator.logPhase(CLUSTER_FORMATION, "Establishing cluster quorum");
        var apiKey = ClusterBootstrapOrchestrator.generateApiKey();
        System.out.printf("  API key generated (%d bytes, Base64 URL-encoded)%n",
                          ClusterBootstrapOrchestrator.API_KEY_BYTES);
        var managementPort = ctx.config().operations()
                                       .ports()
                                       .management();
        var healthTimeoutMs = ClusterBootstrapOrchestrator.parseDurationMs(ctx.config().operations()
                                                                                     .timeouts()
                                                                                     .healthCheck());
        var quorumTimeoutMs = ClusterBootstrapOrchestrator.parseDurationMs(ctx.config().operations()
                                                                                     .timeouts()
                                                                                     .quorumFormation());
        var requiredCores = ctx.config().derivedCoreCount();
        return waitForHealth(ctx.addresses(),
                             managementPort,
                             healthTimeoutMs).flatMap(_ -> waitForQuorum(ctx.addresses(),
                                                                         managementPort,
                                                                         quorumTimeoutMs,
                                                                         requiredCores))
                            .flatMap(_ -> finalizeClusterFormation(ctx, apiKey));
    }

    private static Result<BootstrapContext> finalizeClusterFormation(BootstrapContext ctx, String apiKey) {
        var updatedCtx = ctx.withApiKey(apiKey);
        return storeClusterConfig(updatedCtx)
            .flatMap(_ -> storeApiKey(updatedCtx, apiKey))
            .map(_ -> {
                persistApiKeyFile(ctx.config().cluster().name(), apiKey);
                return updatedCtx;
            });
    }

    @Contract private static void persistApiKeyFile(String clusterName, String apiKey) {
        var keyFile = Path.of(System.getProperty("user.home"), ".aether", "clusters", clusterName, "api-key");
        try {
            Files.createDirectories(keyFile.getParent());
            Files.writeString(keyFile, apiKey);
            var file = keyFile.toFile();
            var _ = file.setReadable(false, false);
            var _ = file.setReadable(true, true);
            var _ = file.setWritable(false, false);
            var _ = file.setWritable(true, true);
            System.out.printf("  API key persisted to %s%n", keyFile);
        } catch (IOException e) {
            System.err.println("  Warning: failed to persist API key file: " + e.getMessage());
        }
    }

    @SuppressWarnings("JBCT-EX-01") private static Result<Unit> waitForHealth(List<NodeAddress> addresses,
                                                                              int managementPort,
                                                                              long timeoutMs) {
        if (addresses.isEmpty()) {return Result.unitResult();}
        System.out.printf("  Waiting for %d node(s) to become healthy (timeout: %ds)%n",
                          addresses.size(),
                          timeoutMs / 1000);
        var promises = addresses.stream().map(addr -> pollSingleNodeHealth(addr.publicIp(),
                                                                           managementPort,
                                                                           timeoutMs))
                                       .toList();
        var results = Promise.allOf(promises).await();
        return results.flatMap(BootstrapPhaseFormation::checkAllHealthy);
    }

    private static Result<Unit> checkAllHealthy(List<Result<Unit>> results) {
        for (var result : results) {if (result.isFailure()) {return result;}}
        System.out.println("  All nodes healthy");
        return Result.unitResult();
    }

    private static Promise<Unit> pollSingleNodeHealth(String ip, int port, long timeoutMs) {
        return Promise.promise(resolver -> {
                                   var url = "http://" + ip + ":" + port + "/health/live";
                                   var deadline = System.currentTimeMillis() + timeoutMs;
                                   while (System.currentTimeMillis() <deadline) {
                                       if (ClusterBootstrapOrchestrator.httpGet(url).isSuccess()) {
                                           resolver.resolve(Result.unitResult());
                                           return;
                                       }
                                       ClusterBootstrapOrchestrator.sleepQuietly(ClusterBootstrapOrchestrator.POLL_INTERVAL_MS);
                                   }
                                   resolver.resolve(new BootstrapError.QuorumNotEstablished(0, 1).result());
                               });
    }

    @SuppressWarnings("JBCT-EX-01") private static Result<Unit> waitForQuorum(List<NodeAddress> addresses,
                                                                              int managementPort,
                                                                              long timeoutMs,
                                                                              int requiredCores) {
        if (addresses.isEmpty()) {return Result.unitResult();}
        var endpoint = addresses.getFirst().publicIp();
        var url = "http://" + endpoint + ":" + managementPort + "/health/ready";
        System.out.printf("  Waiting for quorum at %s (need %d core(s), timeout: %ds)%n",
                          url,
                          requiredCores,
                          timeoutMs / 1000);
        var deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() <deadline) {
            var response = ClusterBootstrapOrchestrator.httpGet(url);
            if (response.isSuccess()) {
                System.out.printf("  Quorum established (%d core(s) required)%n", requiredCores);
                return Result.unitResult();
            }
            ClusterBootstrapOrchestrator.sleepQuietly(ClusterBootstrapOrchestrator.POLL_INTERVAL_MS);
        }
        return new BootstrapError.QuorumNotEstablished(0, requiredCores).result();
    }

    @SuppressWarnings("JBCT-EX-01") private static Result<Unit> storeClusterConfig(BootstrapContext ctx) {
        if (ctx.addresses().isEmpty()) {return Result.unitResult();}
        var endpoint = buildManagementEndpoint(ctx);
        var configJson = buildConfigJson(ctx.rawTomlContent());
        return retryFormationPost(endpoint + "/api/cluster/config", configJson, "cluster config")
            .onSuccess(_ -> System.out.println("  Cluster config stored in KV-Store"));
    }

    @SuppressWarnings("JBCT-EX-01") private static Result<Unit> storeApiKey(BootstrapContext ctx, String apiKey) {
        if (ctx.addresses().isEmpty()) {return Result.unitResult();}
        var endpoint = buildManagementEndpoint(ctx);
        var keyHash = KvStoreApiKeyHasher.hashKey(apiKey);
        var keyId = "ak_" + keyHash.substring(0, 8);
        var keyJson = "{\"keyId\":\"" + keyId + "\",\"keyHash\":\"" + keyHash + "\",\"gracePeriodMs\":300000,\"auditAction\":\"CREATED\",\"operatorHint\":\"bootstrap\"}";
        return retryFormationPost(endpoint + "/api/cluster/keys", keyJson, "API key")
            .onSuccess(_ -> System.out.printf("  API key stored (keyId=%s)%n", keyId));
    }

    // The leader's NodeLifecycle FSM races with the quorum-established signal — Phase 6 detects
    // ready=200 from /health/ready as soon as enough peers are connected, but the leader's
    // single-writer KV path (used by `cluster config` + `cluster keys`) requires the leader's
    // own NodeLifecycle to be ACTIVE. The gap is typically a few seconds; retry with a budget.
    private static Result<Unit> retryFormationPost(String url, String body, String operation) {
        var deadline = System.currentTimeMillis() + STORE_RETRY_BUDGET_MS;
        var start = System.currentTimeMillis();
        var attempts = 0;
        var lastError = "no attempts made";
        while (System.currentTimeMillis() < deadline) {
            attempts++;
            var result = ClusterBootstrapOrchestrator.httpPost(url, body);
            if (result.isSuccess()) {
                if (attempts > 1) {
                    System.out.printf("  %s store succeeded on attempt %d (%dms)%n",
                                      operation, attempts, System.currentTimeMillis() - start);
                }
                return Result.unitResult();
            }
            lastError = extractFailureMessage(result);
            if (attempts == 1 || attempts % 5 == 0) {
                System.out.printf("  Waiting for %s store (attempt %d): %s%n",
                                  operation, attempts, lastError);
            }
            ClusterBootstrapOrchestrator.sleepQuietly(STORE_RETRY_INTERVAL_MS);
        }
        return new BootstrapError.FormationWriteFailed(operation,
                                                       attempts,
                                                       System.currentTimeMillis() - start,
                                                       lastError).result();
    }

    private static String extractFailureMessage(Result<String> result) {
        return result.fold(cause -> cause.message(), _ -> "");
    }

    private static String buildManagementEndpoint(BootstrapContext ctx) {
        var port = ctx.config().operations()
                             .ports()
                             .management();
        var ip = ctx.addresses().getFirst()
                              .publicIp();
        return "http://" + ip + ":" + port;
    }

    static String buildConfigJson(String rawTomlContent) {
        return "{\"tomlContent\":\"" + escapeJsonString(rawTomlContent) + "\",\"expectedVersion\":0}";
    }

    private static String escapeJsonString(String s) {
        if (s == null) {return "";}
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r")
                        .replace("\t", "\\t");
    }
}
