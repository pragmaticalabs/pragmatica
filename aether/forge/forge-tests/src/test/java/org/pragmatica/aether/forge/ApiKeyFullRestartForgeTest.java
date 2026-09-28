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
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterIncarnationKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterIncarnationValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
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


/// #1020, moved onto the KV backup restore by #1533 — a whole-cluster restart is a REGULAR START of a
/// FRESH set of core nodes (new NodeIds, the same cluster configuration), followed by the KV restore
/// (owner ruling, 2026-09-28). Restarting the same NodeIds with empty state is not a restart mode (#1543).
///
/// The shape under test:
/// 1. cluster A (`akr-1..3`) holds cluster state — a minted API key, a deployed slice, the system streams —
///    and its change-triggered backup (#1532) reaches the shared `[backup] remote`;
/// 2. every node of A stops;
/// 3. cluster B (`akrb-1..3`, fresh identities) starts with `restore = auto`: genesis forms exactly as a
///    regular start does, the leader restores the backup head before any cluster-state write is admitted
///    (`BackupRestoreCoordinator`, `RestoreGate`), and the incarnation moves past A's (#1621);
/// 4. the restored state is live on B: the key is accepted on every node, the slice is ACTIVE on B's
///    nodes, and nothing names a node of A — placements and stream partition owners are runtime keys, never
///    backed up, so B assigns its own (`BackupKeyClassificationTest` pins that no backed-up key or value
///    reaches a NodeId);
/// 5. the control: cluster C (`akrc-1..3`) with `restore = "fresh"` ignores the backup, and the key is
///    REFUSED on every node — so the acceptance in 4 is the restore's doing.
///
/// Management security is `API_KEY` with one config-declared ADMIN key, because under Ember's default
/// `SecurityMode.NONE` the management API skips authentication entirely and "accepted" would be
/// unobservable. The config key mints and deploys; the minted key is what every acceptance presents.
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiKeyFullRestartForgeTest {
    private static final int BASE_PORT = 31900;
    private static final int BASE_MGMT_PORT = 32000;
    private static final int BASE_APP_HTTP_PORT = 32100;
    private static final int NODES = 3;
    private static final String PREFIX_A = "akr";
    private static final String PREFIX_B = "akrb";
    private static final String PREFIX_C = "akrc";
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final TimeSpan HTTP_BOUND = TimeSpan.timeSpan(15).seconds();
    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String CONFIG_API_KEY = "forge-1020-config-key";
    private static final String MINTED_KEY = "forge-1020-first-minted-key";
    private static final String MINTED_KEY_ID = "first";
    private static final String KEYS_PATH = "/api/v1/cluster/keys";
    private static final String BLUEPRINT_ID = "forge.test:full-restart:1.0.0";
    private static final String SLICE = TestArtifacts.ECHO_SLICE;
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
        cluster = startCluster(PREFIX_A, RestoreMode.AUTO);
    }

    @AfterAll
    void tearDown() {
        if (cluster != null) {
            LifecycleAwait.bestEffort("cluster stop in tearDown()", cluster, cluster.stop());
        }
    }

    @Test
    void freshCoresRestoreTheBackup_andAFreshStartIgnoresIt() {
        mint(MINTED_KEY_ID, MINTED_KEY);
        assertAccepted(MINTED_KEY, nodeIds(PREFIX_A));
        deployEchoSlice();
        await().alias("the slice is ACTIVE on cluster A")
             .atMost(WAIT_TIMEOUT)
             .pollInterval(POLL_INTERVAL)
             .until(this::sliceActive);
        awaitBackupHeadContains("api-key/" + MINTED_KEY_ID);
        awaitBackupHeadContains("app-blueprint/" + BLUEPRINT_ID);
        var incarnationA = incarnationOf(cluster);

        LifecycleAwait.settled("stop of every node of cluster A", cluster, cluster.stop());

        cluster = startCluster(PREFIX_B, RestoreMode.AUTO);
        assertAccepted(MINTED_KEY, nodeIds(PREFIX_B));
        assertThat(listKeys(PREFIX_B + "-2")).as("cluster B lists the restored cluster-held key")
                  .contains("\"keyId\":\"" + MINTED_KEY_ID + "\"", "\"source\":\"cluster\"");
        assertThat(incarnationOf(cluster)).as("the restore moves the incarnation past cluster A's")
                                          .isGreaterThan(incarnationA);
        await().alias("the restored slice is ACTIVE on cluster B's own nodes")
             .atMost(WAIT_TIMEOUT)
             .pollInterval(POLL_INTERVAL)
             .until(() -> sliceActive() && sliceInstancesOnlyOn(PREFIX_B));
        await().alias("every stream partition owner is a node of cluster B")
             .atMost(WAIT_TIMEOUT)
             .pollInterval(POLL_INTERVAL)
             .until(() -> streamOwnersOnlyOn(PREFIX_B));

        LifecycleAwait.settled("stop of every node of cluster B", cluster, cluster.stop());

        cluster = startCluster(PREFIX_C, RestoreMode.FRESH);
        assertRefused(MINTED_KEY, nodeIds(PREFIX_C));
    }

    // --- clusters -----------------------------------------------------------
    private EmberCluster startCluster(String prefix, RestoreMode restore) {
        var next = emberCluster(NODES, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, prefix);

        // MUST precede start(): every node reads the mode, the key map and the backup config at construction.
        next.withAppHttpSecurity(SecurityMode.API_KEY,
                                 Map.of(CONFIG_API_KEY,
                                        ApiKeyEntry.apiKeyEntry("forge-1020-config", Set.of("service"), "ADMIN")));
        next.withKvBackup(backupDir, remote.toString(), restore);
        cluster = next;
        LifecycleAwait.settled("start of cluster " + prefix, next, next.start());
        awaitMembers(NODES);

        return next;
    }

    private static String[] nodeIds(String prefix) {
        return java.util.stream.IntStream.rangeClosed(1, NODES)
                                         .mapToObj(index -> prefix + "-" + index)
                                         .toArray(String[]::new);
    }

    private static long incarnationOf(EmberCluster cluster) {
        return cluster.allNodes()
                      .getFirst()
                      .kvStore()
                      .getTyped(ClusterIncarnationKey.clusterIncarnationKey(), ClusterIncarnationValue.class)
                      .map(ClusterIncarnationValue::incarnation)
                      .or(0L);
    }

    // --- slice and stream ownership -----------------------------------------
    private void deployEchoSlice() {
        var blueprint = """
            id = "%s"

            [[slices]]
            artifact = "%s"
            instances = 1
            """.formatted(BLUEPRINT_ID, SLICE);
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + leaderMgmtPort() + "/api/v1/blueprints"))
                                 .header("Content-Type", "application/toml")
                                 .header(API_KEY_HEADER, CONFIG_API_KEY)
                                 .POST(HttpRequest.BodyPublishers.ofString(blueprint))
                                 .timeout(Duration.ofSeconds(15))
                                 .build();
        var response = send(request, "deploy " + BLUEPRINT_ID);

        assertThat(response.body()).as("blueprint deploy: %s", response.body())
                                   .doesNotContain("\"error\"");
    }

    private boolean sliceActive() {
        return cluster.slicesStatus()
                      .stream()
                      .anyMatch(status -> status.artifact()
                                                .equals(SLICE) && status.state()
                                                                        .equals("ACTIVE"));
    }

    private boolean sliceInstancesOnlyOn(String prefix) {
        return cluster.slicesStatus()
                      .stream()
                      .filter(status -> status.artifact()
                                              .equals(SLICE))
                      .flatMap(status -> status.instances()
                                               .stream())
                      .allMatch(instance -> instance.nodeId()
                                                    .startsWith(prefix + "-"));
    }

    private boolean streamOwnersOnlyOn(String prefix) {
        var owners = new ArrayList<String>();

        cluster.allNodes()
               .getFirst()
               .kvStore()
               .forEach(StreamPartitionOwnershipKey.class,
                        StreamPartitionOwnershipValue.class,
                        (key, value) -> owners.add(value.owner()
                                                        .id()));

        return !owners.isEmpty() && owners.stream()
                                          .allMatch(owner -> owner.startsWith(prefix + "-"));
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
