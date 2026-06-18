// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapContext;
import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapError;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.environment.NodeAddress;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import tools.jackson.databind.JsonNode;

import static org.pragmatica.aether.cli.cluster.BootstrapPhase.CLUSTER_FORMATION;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"})
sealed interface BootstrapPhaseFormation {
    record unused() implements BootstrapPhaseFormation {}

    JsonMapper JSON = JsonMapper.defaultJsonMapper();
    long STORE_RETRY_BUDGET_MS = 60_000L;
    long STORE_RETRY_INTERVAL_MS = 2_000L;

    static Result<BootstrapContext> execute(BootstrapContext ctx) {
        ClusterBootstrapOrchestrator.logPhase(CLUSTER_FORMATION, "Establishing cluster quorum");
        var apiKey = ClusterBootstrapOrchestrator.generateApiKey();

        System.out.printf("  API key generated (%d bytes, Base64 URL-encoded)%n",
                          ClusterBootstrapOrchestrator.API_KEY_BYTES);
        var managementPort = ctx.config().operations().ports().management();
        var scheme = managementScheme(ctx);
        var healthTimeoutMs = ClusterBootstrapOrchestrator.parseDurationMs(ctx.config().operations().timeouts().healthCheck());
        var quorumTimeoutMs = ClusterBootstrapOrchestrator.parseDurationMs(ctx.config().operations().timeouts().quorumFormation());
        var requiredCores = ctx.config().derivedCoreCount();
        var configuredKey = extractConfiguredApiKey(ctx.config());

        return waitForHealth(ctx.addresses(),
                             managementPort,
                             healthTimeoutMs,
                             scheme).flatMap(_ -> waitForQuorum(ctx.addresses(),
                                                                managementPort,
                                                                quorumTimeoutMs,
                                                                requiredCores,
                                                                scheme,
                                                                configuredKey))
                            .flatMap(_ -> finalizeClusterFormation(ctx, apiKey));
    }

    private static String managementScheme(BootstrapContext ctx) {
        return ctx.config()
                  .operations()
                  .tls()
                  .autoGenerate()
               ? "https"
               : "http";
    }

    private static Result<BootstrapContext> finalizeClusterFormation(BootstrapContext ctx, String apiKey) {
        var updatedCtx = ctx.withApiKey(apiKey);

        return storeClusterConfig(updatedCtx).flatMap(_ -> storeApiKey(updatedCtx, apiKey))
                                 .map(_ -> {
                                          persistApiKeyFile(ctx.config().cluster().name(),
                                                            apiKey);

                                          return updatedCtx;
                                      });
    }

    @Contract
    private static void persistApiKeyFile(String clusterName, String apiKey) {
        var keyFile = Path.of(System.getProperty("user.home"), ".aether", "clusters", clusterName, "api-key");
        // #287: the persisted admin api-key file must be owner-only (0600). Replaces the deprecated
        // File.setReadable/setWritable dance with POSIX permissions via SecureFiles.
        ensureParentDir(keyFile).flatMap(_ -> SecureFiles.writeSecure(keyFile, apiKey)).onSuccessRun(() -> System.out.printf("  API key persisted to %s%n",
                                                                                                                             keyFile)).onFailure(cause -> System.err.println("  Warning: failed to persist API key file: " + cause.message()));
    }

    private static Result<Unit> ensureParentDir(Path keyFile) {
        return Result.lift(Causes::fromThrowable,
                           () -> Files.createDirectories(keyFile.getParent()))
                     .mapToUnit();
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<Unit> waitForHealth(List<NodeAddress> addresses,
                                              int managementPort,
                                              long timeoutMs,
                                              String scheme) {
        if (addresses.isEmpty()) {
            return Result.unitResult();
        }

        System.out.printf("  Waiting for %d node(s) to become healthy (timeout: %ds)%n",
                          addresses.size(),
                          timeoutMs / 1000);
        var promises = addresses.stream().map(addr -> pollSingleNodeHealth(addr.publicIp(),
                                                                           managementPort,
                                                                           timeoutMs,
                                                                           scheme)).toList();
        var results = Promise.allOf(promises).await();

        return results.flatMap(BootstrapPhaseFormation::checkAllHealthy);
    }

    private static Result<Unit> checkAllHealthy(List<Result<Unit>> results) {
        for (var result : results) {
            if (result.isFailure()) {
                return result;
            }
        }

        System.out.println("  All nodes healthy");

        return Result.unitResult();
    }

    private static Promise<Unit> pollSingleNodeHealth(String ip, int port, long timeoutMs, String scheme) {
        return Promise.promise(resolver -> {
            var url = scheme + "://" + ip + ":" + port + "/health/live";
            var deadline = System.currentTimeMillis() + timeoutMs;

            while (System.currentTimeMillis() < deadline) {
                if (ClusterBootstrapOrchestrator.httpGet(url).isSuccess()) {
                    resolver.resolve(Result.unitResult());

                    return;
                }

                ClusterBootstrapOrchestrator.sleepQuietly(ClusterBootstrapOrchestrator.POLL_INTERVAL_MS);
            }

            resolver.resolve(new BootstrapError.QuorumNotEstablished(0, 1).result());
        });
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<Unit> waitForQuorum(List<NodeAddress> addresses,
                                              int managementPort,
                                              long timeoutMs,
                                              int requiredCores,
                                              String scheme,
                                              Option<String> apiKey) {
        if (addresses.isEmpty()) {
            return Result.unitResult();
        }

        var endpoint = addresses.getFirst().publicIp();
        var quorumFloor = quorumFloor(requiredCores);

        System.out.printf("  Waiting for quorum at %s://%s:%d (need %d of %d core(s), timeout: %ds)%n",
                          scheme,
                          endpoint,
                          managementPort,
                          quorumFloor,
                          requiredCores,
                          timeoutMs / 1000);
        var deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            if (quorumReached(endpoint, managementPort, scheme, requiredCores, quorumFloor, apiKey)) {
                System.out.printf("  Quorum established (>= %d of %d core(s))%n", quorumFloor, requiredCores);

                return Result.unitResult();
            }

            ClusterBootstrapOrchestrator.sleepQuietly(ClusterBootstrapOrchestrator.POLL_INTERVAL_MS);
        }

        return new BootstrapError.QuorumNotEstablished(0, quorumFloor).result();
    }

    /// Strict majority floor for a cluster of `requiredCores` (`n/2 + 1`), mirroring
    /// `StatusRoutes.quorumOf`. For `requiredCores <= 1` the floor is 1 (a genuine single-node dev
    /// cluster the operator explicitly configured).
    private static int quorumFloor(int requiredCores) {
        return requiredCores <= 1
               ? 1
               : requiredCores / 2 + 1;
    }

    /// #295: split-brain fence at formation. A 200 from a single endpoint's `/health/ready` is NOT
    /// sufficient to declare quorum for a multi-node cluster — a lone node reports itself ready and two
    /// concurrent bootstraps could each conclude they own the cluster. For `requiredCores > 1` we read
    /// the leader-authoritative `/api/health` (forwarded to the leader; carries the cluster-wide
    /// `nodeCount` + `quorum`) and require the reported member count to meet the strict-majority floor.
    /// For a single-core cluster the lightweight readiness probe is sufficient.
    private static boolean quorumReached(String endpoint,
                                         int managementPort,
                                         String scheme,
                                         int requiredCores,
                                         int quorumFloor,
                                         Option<String> apiKey) {
        if (requiredCores <= 1) {
            var readyUrl = scheme + "://" + endpoint + ":" + managementPort + "/health/ready";

            return ClusterBootstrapOrchestrator.httpGet(readyUrl).isSuccess();
        }

        var healthUrl = scheme + "://" + endpoint + ":" + managementPort + "/api/health";

        return ClusterBootstrapOrchestrator.httpGet(healthUrl, apiKey)
                                           .flatMap(JSON::readTree)
                                           .map(node -> healthMeetsFloor(node, quorumFloor))
                                           .or(false);
    }

    /// Decide whether the `/api/health` view has reached the quorum floor: the leader must report
    /// `quorum:true` AND a member count (`nodeCount`) at or above the strict majority. A single-node
    /// self-view (`nodeCount:1`) against an expected multi-core cluster fails this check — exactly the
    /// split-brain case being fenced.
    static boolean healthMeetsFloor(JsonNode health, int quorumFloor) {
        var nodeCount = health.path("nodeCount").asInt(0);
        var hasQuorum = health.path("quorum").asBoolean(false);

        return hasQuorum && nodeCount >= quorumFloor;
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<Unit> storeClusterConfig(BootstrapContext ctx) {
        if (ctx.addresses().isEmpty()) {
            return Result.unitResult();
        }

        var endpoint = buildManagementEndpoint(ctx);
        var persistedToml = SshAuthorizedKeysToml.withAuthorizedKeys(ctx.rawTomlContent(),
                                                                     ctx.sshPublicKeys()
                                                                        .stream()
                                                                        .map(SshPublicKey::value)
                                                                        .toList());
        var configJson = buildConfigJson(persistedToml);
        var configuredKey = extractConfiguredApiKey(ctx.config());

        return retryFormationPost(endpoint + "/api/cluster/config", configJson, "cluster config", configuredKey).onSuccess(_ -> System.out.println("  Cluster config stored in KV-Store"));
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<Unit> storeApiKey(BootstrapContext ctx, String apiKey) {
        if (ctx.addresses().isEmpty()) {
            return Result.unitResult();
        }

        var endpoint = buildManagementEndpoint(ctx);
        var keyHash = KvStoreApiKeyHasher.hashKey(apiKey);
        var keyId = "ak_" + keyHash.substring(0, 8);
        var keyJson = "{\"keyId\":\"" + keyId
                    + "\",\"keyHash\":\"" + keyHash
                    + "\",\"gracePeriodMs\":300000,\"auditAction\":\"CREATED\",\"operatorHint\":\"bootstrap\"}";
        var configuredKey = extractConfiguredApiKey(ctx.config());

        return retryFormationPost(endpoint + "/api/cluster/keys", keyJson, "API key", configuredKey).onSuccess(_ -> System.out.printf("  API key stored (keyId=%s)%n",
                                                                                                                                      keyId));
    }

    private static Option<String> extractConfiguredApiKey(ClusterBootstrapConfig config) {
        return findAdminKey(config).orElse(() -> findFirstSimpleKey(config));
    }

    private static Option<String> findAdminKey(ClusterBootstrapConfig config) {
        var prefix = "app-http.api-keys.";

        return Option.from(config.sources()
                                 .values()
                                 .stream()
                                 .flatMap(source -> source.nodeConfig()
                                                          .stream())
                                 .flatMap(doc -> doc.sectionNames()
                                                    .stream()
                                                    .filter(name -> name.startsWith(prefix))
                                                    .filter(name -> "ADMIN".equalsIgnoreCase(doc.getString(name,
                                                                                                           "authorization_role")
                                                                                                .or("")))
                                                    .map(name -> name.substring(prefix.length())))
                                 .findFirst());
    }

    private static Option<String> findFirstSimpleKey(ClusterBootstrapConfig config) {
        return Option.from(config.sources()
                                 .values()
                                 .stream()
                                 .flatMap(source -> source.nodeConfig()
                                                          .flatMap(doc -> doc.getStringList("app-http", "api_keys"))
                                                          .filter(keys -> !keys.isEmpty())
                                                          .map(java.util.List::getFirst)
                                                          .stream())
                                 .findFirst());
    }

    private static Result<Unit> retryFormationPost(String url, String body, String operation, Option<String> apiKey) {
        var deadline = System.currentTimeMillis() + STORE_RETRY_BUDGET_MS;
        var start = System.currentTimeMillis();
        var attempts = 0;
        var lastError = "no attempts made";

        while (System.currentTimeMillis() < deadline) {
            attempts++;
            var result = ClusterBootstrapOrchestrator.httpPost(url, body, apiKey);

            if (result.isSuccess()) {
                if (attempts > 1) {
                    System.out.printf("  %s store succeeded on attempt %d (%dms)%n",
                                      operation,
                                      attempts,
                                      System.currentTimeMillis() - start);
                }

                return Result.unitResult();
            }

            lastError = extractFailureMessage(result);
            if (attempts == 1 || attempts % 5 == 0) {
                System.out.printf("  Waiting for %s store (attempt %d): %s%n", operation, attempts, lastError);
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
        var port = ctx.config().operations().ports().management();
        var ip = ctx.addresses().getFirst().publicIp();
        var scheme = ctx.config().operations().tls().autoGenerate()
                     ? "https"
                     : "http";

        return scheme + "://" + ip + ":" + port;
    }

    static String buildConfigJson(String rawTomlContent) {
        return "{\"tomlContent\":\"" + escapeJsonString(rawTomlContent) + "\",\"expectedVersion\":0}";
    }

    private static String escapeJsonString(String s) {
        if (s == null) {
            return "";
        }

        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
