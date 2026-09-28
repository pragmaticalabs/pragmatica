// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.pragmatica.aether.config.ApiKeyEntry;
import org.pragmatica.aether.config.BackupConfig.RestoreMode;
import org.pragmatica.aether.config.SecurityMode;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;


/// #1020, moved onto the KV backup restore by #1533 — a cluster-minted API key survives a full (all-nodes)
/// restart when `[backup]` is enabled, on EVERY node.
///
/// The guarantee under test, stated precisely: consensus runs in memory, so a restarted cluster starts
/// empty; a key minted through `POST /api/v1/cluster/keys` and present in the backup head (the
/// change-triggered, leader-only backup pushed to `[backup] remote`, #1532) before every node stops is
/// accepted after the restart, because the new leader restores the head before any cluster-state write is
/// admitted (`BackupRestoreCoordinator`, `RestoreGate`) and every node then holds it through consensus.
///
/// The control is the same restart with `[backup] restore = "fresh"`: the backup is ignored, and the same
/// key must be REFUSED on every node — so the acceptance above is the restore's doing, not a leftover.
///
/// Management security is `API_KEY` with one config-declared ADMIN key, because under Ember's default
/// `SecurityMode.NONE` the management API skips authentication entirely and "accepted" would be
/// unobservable. The config key mints; the minted key is what every acceptance assertion presents.
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiKeyFullRestartForgeTest {
    private static final int BASE_PORT = 31900;
    private static final int BASE_MGMT_PORT = 32000;
    private static final int BASE_APP_HTTP_PORT = 32100;
    private static final int NODES = 3;
    private static final String NODE_PREFIX = "akr";
    private static final String NODE_1 = NODE_PREFIX + "-1";
    private static final String NODE_2 = NODE_PREFIX + "-2";
    private static final String NODE_3 = NODE_PREFIX + "-3";
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final TimeSpan HTTP_BOUND = TimeSpan.timeSpan(15).seconds();
    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String CONFIG_API_KEY = "forge-1020-config-key";
    private static final String MINTED_KEY = "forge-1020-first-minted-key";
    private static final String MINTED_KEY_ID = "first";
    private static final String KEYS_PATH = "/api/v1/cluster/keys";
    private static final String BACKUP_BRANCH = "kv-backup";
    private static final String BACKUP_FILE = "kv-backup.txt";
    private static final Pattern NODE_COUNT_FIELD = Pattern.compile("\"nodeCount\"\\s*:\\s*(\\d+)");

    private EmberCluster cluster;
    private final HttpOperations http = jdkHttpOperations();
    private Path backupDir;
    private Path remote;

    @BeforeAll
    void setUp(@TempDir Path tempDir) {
        backupDir = tempDir.resolve("nodes");
        remote = tempDir.resolve("remote.git");
        git(tempDir, "init", "--quiet", "--bare", remote.toString());
        cluster = emberCluster(NODES, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, NODE_PREFIX);
        // MUST precede start(): every node reads the mode, the key map and the backup config at construction.
        cluster.withAppHttpSecurity(SecurityMode.API_KEY,
                                    Map.of(CONFIG_API_KEY,
                                           ApiKeyEntry.apiKeyEntry("forge-1020-config", Set.of("service"), "ADMIN")));
        cluster.withKvBackup(backupDir, remote.toString(), RestoreMode.AUTO);
        LifecycleAwait.settled("cluster start in setUp()", cluster, cluster.start());
        awaitMembers(NODES);
    }

    @AfterAll
    void tearDown() {
        if (cluster != null) {
            LifecycleAwait.bestEffort("cluster stop in tearDown()", cluster, cluster.stop());
        }
    }

    @Test
    void fullRestart_restoresTheBackup_everyNodeAcceptsTheMintedKey_andAFreshRestartDoesNot() {
        mint(MINTED_KEY_ID, MINTED_KEY);
        assertAccepted(MINTED_KEY, NODE_1, NODE_2, NODE_3);
        awaitBackupHeadContains("api-key/" + MINTED_KEY_ID);
        LifecycleAwait.settled("cluster stop", cluster, cluster.stop());
        LifecycleAwait.settled("cluster restart restoring the backup", cluster, cluster.start());
        awaitMembers(NODES);
        assertAccepted(MINTED_KEY, NODE_1, NODE_2, NODE_3);
        assertThat(listKeys(NODE_2)).as("node 2 lists the restored cluster-held key")
                  .contains("\"keyId\":\"" + MINTED_KEY_ID + "\"", "\"source\":\"cluster\"");
        // The control: the same restart ignoring the backup. The key must be gone everywhere.
        LifecycleAwait.settled("cluster stop before the fresh control", cluster, cluster.stop());
        cluster.withKvBackup(backupDir, remote.toString(), RestoreMode.FRESH);
        LifecycleAwait.settled("cluster restart with restore = fresh", cluster, cluster.start());
        awaitMembers(NODES);
        assertRefused(MINTED_KEY, NODE_1, NODE_2, NODE_3);
    }

    // --- mint / accept ------------------------------------------------------
    /// Mint through the leader with the CONFIG key. The request carries the SHA-256 hex of the plaintext,
    /// byte-for-byte what `KvStoreApiKeyValidator` compares against.
    private void mint(String keyId, String plaintext) {
        var body = "{\"keyId\":\"" + keyId
                 + "\",\"keyHash\":\"" + sha256Hex(plaintext)
                 + "\",\"gracePeriodMs\":0,"
                 + "\"auditAction\":\"CREATED\",\"operatorHint\":\"forge-1020\",\"authorizationRole\":\"ADMIN\"}";
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + leaderMgmtPort() + KEYS_PATH))
                                 .header("Content-Type", "application/json")
                                 .header(API_KEY_HEADER, CONFIG_API_KEY)
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .timeout(Duration.ofSeconds(15))
                                 .build();
        var response = send(request, "mint " + keyId);

        assertThat(response.statusCode()).as("mint %s through the leader with the config key: %s",
                                             keyId,
                                             response.body())
                  .isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"ACTIVE\"");
    }

    /// The acceptance assertion: `GET /api/v1/cluster/keys` on the NAMED node, presenting only the minted
    /// key. 200 means the node's KV-store validator found an ACTIVE record whose hash matches; 403 is
    /// the ticket's signature. Polled briefly: a node that has just activated may still be replaying.
    private void assertAccepted(String plaintext, String... nodeIds) {
        for (var nodeId : nodeIds) {
            await().alias(plaintext + " accepted on " + nodeId)
                 .atMost(Duration.ofSeconds(30))
                 .pollInterval(POLL_INTERVAL)
                 .untilAsserted(() -> assertThat(statusWithKey(nodeId, plaintext)).as("%s presented on %s",
                                                                                      plaintext,
                                                                                      nodeId)
                                                .isEqualTo(200));
        }
    }

    /// The control's assertion: the key must be refused. Waits out a still-activating node the same way.
    private void assertRefused(String plaintext, String... nodeIds) {
        for (var nodeId : nodeIds) {
            await().alias(plaintext + " refused on " + nodeId)
                 .atMost(Duration.ofSeconds(30))
                 .pollInterval(POLL_INTERVAL)
                 .untilAsserted(() -> assertThat(statusWithKey(nodeId, plaintext)).as("%s presented on %s after a fresh restart",
                                                                                      plaintext,
                                                                                      nodeId)
                                                .isEqualTo(403));
        }
    }

    /// The backup head on the shared remote — what the restart restores — names the key.
    private void awaitBackupHeadContains(String keyString) {
        await().alias("backup head contains " + keyString)
             .atMost(WAIT_TIMEOUT)
             .pollInterval(POLL_INTERVAL)
             .until(() -> backupHead().contains(" " + keyString + "\n") || backupHead().endsWith(" " + keyString));
    }

    private String backupHead() {
        return gitOrEmpty(remote, "show", BACKUP_BRANCH + ":" + BACKUP_FILE);
    }

    private int statusWithKey(String nodeId, String plaintext) {
        return send(listRequest(nodeId, plaintext), "list keys on " + nodeId).statusCode();
    }

    private String listKeys(String nodeId) {
        return send(listRequest(nodeId, CONFIG_API_KEY), "list keys on " + nodeId).body();
    }

    private HttpRequest listRequest(String nodeId, String apiKey) {
        return HttpRequest.newBuilder()
                          .uri(URI.create("http://localhost:" + mgmtPort(nodeId) + KEYS_PATH))
                          .header(API_KEY_HEADER, apiKey)
                          .GET()
                          .timeout(Duration.ofSeconds(15))
                          .build();
    }

    private HttpResult<String> send(HttpRequest request, String what) {
        return http.sendString(request)
                   .await(HTTP_BOUND)
                   .fold(cause -> {
                             throw new AssertionError(what + " did not answer: " + cause.message());
                         },
                         result -> result);
    }

    // --- cluster helpers ----------------------------------------------------
    private int mgmtPort(String nodeId) {
        return cluster.status()
                      .nodes()
                      .stream()
                      .filter(node -> node.id()
                                          .equals(nodeId))
                      .map(EmberCluster.NodeStatus::mgmtPort)
                      .findFirst()
                      .orElseThrow(() -> new AssertionError("no running node " + nodeId + " in " + cluster.status()));
    }

    private int leaderMgmtPort() {
        return cluster.getLeaderManagementPort()
                      .or(() -> cluster.status()
                                       .nodes()
                                       .getFirst()
                                       .mgmtPort());
    }

    /// Readiness: a leader exists and its `/api/v1/health` reports quorum with `nodeCount` at the
    /// expected membership (the same gate `BootstrapPhaseFormation.healthMeetsFloor` uses).
    private void awaitMembers(int expected) {
        await().alias("leader elected among " + expected + " nodes")
             .atMost(WAIT_TIMEOUT)
             .pollInterval(POLL_INTERVAL)
             .until(() -> cluster.currentLeader()
                                 .isPresent());
        await().alias(expected + " members with quorum")
             .atMost(WAIT_TIMEOUT)
             .pollInterval(POLL_INTERVAL)
             .until(() -> leaderHealthReports(expected));
    }

    private boolean leaderHealthReports(int expected) {
        // `/api/v1/health` is a VIEWER read under API_KEY mode; only the `/health/live|ready` probes bypass the gate.
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + leaderMgmtPort() + "/api/v1/health"))
                                 .header(API_KEY_HEADER, CONFIG_API_KEY)
                                 .GET()
                                 .timeout(Duration.ofSeconds(5))
                                 .build();

        return http.sendString(request)
                   .await()
                   .map(r -> r.statusCode() == 200 && healthReports(r.body(),
                                                                    expected))
                   .or(false);
    }

    private static boolean healthReports(String body, int expected) {
        var matcher = NODE_COUNT_FIELD.matcher(body);

        return body.contains("\"quorum\":true")
               && matcher.find()
               && Integer.parseInt(matcher.group(1)) == expected;
    }

    private static String git(Path dir, String... args) {
        var result = runGit(dir, args);

        if (result.exitCode() != 0) {
            throw new AssertionError("git " + String.join(" ", args) + " failed: " + result.output());
        }

        return result.output();
    }

    private static String gitOrEmpty(Path dir, String... args) {
        var result = runGit(dir, args);

        return result.exitCode() == 0
               ? result.output()
               : "";
    }

    private record GitResult(int exitCode, String output) {}

    private static GitResult runGit(Path dir, String... args) {
        var command = new ArrayList<>(List.of("git", "-C", dir.toString()));

        command.addAll(List.of(args));
        try {
            var process = new ProcessBuilder(command).redirectErrorStream(true)
                                                     .start();
            var output = new String(process.getInputStream()
                                           .readAllBytes(),
                                    StandardCharsets.UTF_8);

            return new GitResult(process.waitFor(), output);
        } catch (IOException e) {
            throw new AssertionError("could not run git", e);
        } catch (InterruptedException e) {
            Thread.currentThread()
                  .interrupt();

            throw new AssertionError("interrupted running git", e);
        }
    }

    private static String sha256Hex(String plaintext) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available", e);
        }
    }
}
