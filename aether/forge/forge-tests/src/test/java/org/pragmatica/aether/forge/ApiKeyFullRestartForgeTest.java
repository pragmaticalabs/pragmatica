// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.pragmatica.aether.config.ApiKeyEntry;
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


/// #1020 — a cluster-minted API key survives a full (all-nodes) graceful restart when `[backup]` is
/// enabled, on EVERY node, including the one whose disk snapshot is ahead of its first responder.
///
/// The guarantee under test, stated precisely: a key minted through `POST /api/v1/cluster/keys` and
/// acknowledged before a graceful all-nodes stop is accepted after restart, because it is a record in
/// the consensus KV state machine, which `RabiaEngine.shutdownAndReset` saves through
/// `GitBackedPersistence` to `[backup] path` at stop, and which sync adoption restores at boot — from
/// a peer's persisted snapshot when a peer is ahead, and (the #1020 fix) from the node's OWN
/// persisted snapshot when every responder is behind it.
///
/// The phase skew is produced deliberately, not left to the scheduler: node 1 is stopped first and
/// saves at phase P; a second key is then minted through the surviving quorum, so nodes 2 and 3 save
/// at P' > P. The restart holds node 3 back, so node 2's ONLY sync responder is node 1 — behind it.
/// Before the fix, node 2 refused the response (correctly) and activated on an EMPTY store at phase 0:
/// the operator's key answered 403 on the node holding the most advanced snapshot. Node 1 adopts node
/// 2's response (ahead of it), so it is the control: the peer-restore path was never the defect.
///
/// Management security is `API_KEY` with one config-declared ADMIN key, because under Ember's default
/// `SecurityMode.NONE` the management API skips authentication entirely and "accepted" would be
/// unobservable. The config key mints; the minted keys are what every acceptance assertion presents.
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
    private static final String FIRST_KEY = "forge-1020-first-minted-key";
    private static final String SECOND_KEY = "forge-1020-second-minted-key";
    private static final String KEYS_PATH = "/api/v1/cluster/keys";
    private static final Pattern NODE_COUNT_FIELD = Pattern.compile("\"nodeCount\"\\s*:\\s*(\\d+)");

    private EmberCluster cluster;
    private final HttpOperations http = jdkHttpOperations();

    @BeforeAll
    void setUp(@TempDir Path backupDir) {
        cluster = emberCluster(NODES, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, NODE_PREFIX);
        // MUST precede start(): every node reads the mode, the key map and the backup root at construction.
        cluster.withAppHttpSecurity(SecurityMode.API_KEY,
                                    Map.of(CONFIG_API_KEY,
                                           ApiKeyEntry.apiKeyEntry("forge-1020-config", Set.of("service"), "ADMIN")));
        // #1341's seam: the caller provisions <baseDir>/<nodeId>, as production provisioning does —
        // GitBackedPersistence writes into its directory and never creates it.
        for (int i = 1; i <= NODES; i++) {
            createDirectory(backupDir.resolve(NODE_PREFIX + "-" + i));
        }

        cluster.withConsensusBaseDir(backupDir);
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
    void fullGracefulRestart_everyNodeAcceptsTheKeysMintedBeforeTheStop() {
        mint("first", FIRST_KEY);
        assertAccepted(FIRST_KEY, NODE_1, NODE_2, NODE_3);
        // Phase skew: node 1 saves at P on its graceful stop; the second mint advances the survivors past P.
        LifecycleAwait.settled("graceful stop of " + NODE_1, cluster, cluster.killNode(NODE_1, true));
        awaitMembers(NODES - 1);
        mint("second", SECOND_KEY);
        assertAccepted(SECOND_KEY, NODE_2, NODE_3);
        LifecycleAwait.settled("cluster stop", cluster, cluster.stop());
        // Restart with node 3 held back: node 2's only responder is node 1, whose snapshot is BEHIND node 2's.
        LifecycleAwait.settled("cluster restart with " + NODE_3 + " held back",
                               cluster,
                               cluster.start(Set.of(NODE_3)));
        awaitMembers(NODES - 1);
        assertAccepted(SECOND_KEY, NODE_2);
        assertAccepted(FIRST_KEY, NODE_2);
        assertThat(listKeys(NODE_2)).as("node 2 must list both cluster-held keys it activated on — its own persisted snapshot")
                  .contains("\"keyId\":\"first\"", "\"keyId\":\"second\"", "\"source\":\"cluster\"");
        // The control: node 1 restarted BEHIND and adopted node 2's response — the peer-restore path.
        assertAccepted(SECOND_KEY, NODE_1);
        assertAccepted(FIRST_KEY, NODE_1);
        LifecycleAwait.settled("start of held-back " + NODE_3, cluster, cluster.startHeldBackNodes());
        awaitMembers(NODES);
        assertAccepted(SECOND_KEY, NODE_3);
        assertAccepted(FIRST_KEY, NODE_3);
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

    private static void createDirectory(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new AssertionError("could not provision consensus dir " + dir, e);
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
