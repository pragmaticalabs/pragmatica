// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.config.ApiKeyEntry;
import org.pragmatica.aether.config.BackupConfig.RestoreMode;
import org.pragmatica.aether.config.SecurityMode;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.node.backup.RestoreGate;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;

/// #2718 — the window in which the cluster's backup restore decision has not committed, held open deterministically.
///
/// The backup remote does not exist yet, so the restore cannot read it and the decision stays uncommitted (an
/// unreadable backup is never a fresh boot, `EmberKvBackupRestoreTest`). Over the management API an API-key mint
/// (a backed-up key) is then refused by `RestoreGate` with `RestorePending`. Two pins:
///
/// 1. the refusal is answered **503**, a transient status a client may retry, not the unclassified 500;
/// 2. once the remote exists, the readiness signal a caller can wait on — every node's `RestoreGate.isOpen` — turns
///    the same mint into 200. This is the signal `ApiKeyFullRestartForgeTest` waits on before it mints.
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RestoreWindowMintForgeTest {
    private static final int BASE_PORT = 33900;
    private static final int BASE_MGMT_PORT = 34000;
    private static final int BASE_APP_HTTP_PORT = 34100;
    private static final int NODES = 3;
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final TimeSpan HTTP_BOUND = TimeSpan.timeSpan(15).seconds();
    private static final String CONFIG_API_KEY = "forge-2718-config-key";
    private static final String KEYS_PATH = "/api/v1/cluster/keys";

    private EmberCluster cluster;
    private final HttpOperations http = jdkHttpOperations();
    private Path remote;

    @BeforeAll
    void setUp(@TempDir Path tempDir) {
        remote = tempDir.resolve("remote.git");
        cluster = emberCluster(NODES, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "rwm");
        cluster.withAppHttpSecurity(SecurityMode.API_KEY,
                                    Map.of(CONFIG_API_KEY,
                                           ApiKeyEntry.apiKeyEntry("forge-2718-config", Set.of("service"), "ADMIN")));
        cluster.withKvBackup(tempDir.resolve("nodes"), remote.toString(), RestoreMode.AUTO);
        LifecycleAwait.settled("start of the restore-window cluster", cluster, cluster.start());
        await().alias("a leader is elected")
             .atMost(WAIT_TIMEOUT)
             .pollInterval(POLL_INTERVAL)
             .until(() -> cluster.currentLeader()
                                 .isPresent());
    }

    @AfterAll
    void tearDown() {
        if (cluster != null) {
            LifecycleAwait.bestEffort("cluster stop in tearDown()", cluster, cluster.stop());
        }
    }

    @Test
    void aMintInsideTheRestoreWindow_isRefused503_andLandsOnceEveryNodeHoldsTheDecision() throws IOException, InterruptedException {
        assertThat(cluster.allNodes()).as("no node holds a decision while the backup cannot be read")
                          .noneMatch(node -> RestoreGate.isOpen(node.kvStore()));

        var refused = mint("window-1");

        assertThat(refused.statusCode()).as("mint inside the restore window: %s", refused.body())
                  .isEqualTo(503);

        initBareRemote();
        await().alias("every node holds a terminal backup restore decision")
             .atMost(WAIT_TIMEOUT)
             .pollInterval(POLL_INTERVAL)
             .until(() -> cluster.allNodes()
                                 .stream()
                                 .allMatch(node -> RestoreGate.isOpen(node.kvStore())));

        var accepted = mint("window-2");

        assertThat(accepted.statusCode()).as("mint after the decision: %s", accepted.body())
                  .isEqualTo(200);
    }

    private void initBareRemote() throws IOException, InterruptedException {
        var process = new ProcessBuilder("git", "init", "--quiet", "--bare", remote.toString()).redirectErrorStream(true)
                                                                                                .start();
        var output = new String(process.getInputStream()
                                       .readAllBytes());

        assertThat(process.waitFor()).as("git init of the backup remote: %s", output)
                  .isZero();
    }

    private HttpResult<String> mint(String keyId) {
        var body = "{\"keyId\":\"" + keyId
                 + "\",\"keyHash\":\"" + "0".repeat(64)
                 + "\",\"gracePeriodMs\":0,"
                 + "\"auditAction\":\"CREATED\",\"operatorHint\":\"forge-2718\",\"authorizationRole\":\"ADMIN\"}";
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + leaderMgmtPort() + KEYS_PATH))
                                 .header("Content-Type", "application/json")
                                 .header("X-API-Key", CONFIG_API_KEY)
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .timeout(Duration.ofSeconds(15))
                                 .build();

        return http.sendString(request)
                   .await(HTTP_BOUND)
                   .fold(cause -> {
                             throw new AssertionError("mint " + keyId + " did not answer: " + cause.message());
                         },
                         result -> result);
    }

    private int leaderMgmtPort() {
        return cluster.getLeaderManagementPort()
                      .or(() -> cluster.status()
                                       .nodes()
                                       .getFirst()
                                       .mgmtPort());
    }
}
