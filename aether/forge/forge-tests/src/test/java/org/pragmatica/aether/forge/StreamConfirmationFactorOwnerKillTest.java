// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.awaitility.core.ConditionTimeoutException;
import org.pragmatica.aether.slice.ReplicationFactors;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.aether.stream.StreamReadRouter.ReplicaSetView;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Option;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;

import org.pragmatica.aether.ember.EmberCluster;

/// #1564 acceptance A — at `replication_factor = 3, confirmation_factor = 2` an acknowledged record survives the
/// loss of the partition owner. The fixture is `test-stream-repl` (`[streams.repl-failover-events]`: RF 3, CF 2), so
/// a publish acks once the owner and ONE peer hold the event; the second peer need not. The flow: 5-node Ember
/// cluster → deploy → the committed config reads RF 3 / CF 2 → publish N events, each acked → kill the partition's
/// owner IMMEDIATELY after the last ack, with no wait for replication → ownership moves off the killed owner
/// (#1555) → the new owner serves all N acked events, contiguous and in order.
///
/// What it rests on: CF 2 puts every acked event on at least one survivor, and #1555's promotion gate catches the
/// new owner up from the highest-head live member before it serves (know 17cddabf3, #411). Without that catch-up
/// a promoted replica that had not received the tail would serve a gap.
///
/// Ember equivalence: the kill is [EmberCluster#killNode] (`node.stop()`, a SWIM leave), not a SIGKILL.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StreamConfirmationFactorOwnerKillTest {
    private static final System.Logger LOG = System.getLogger(StreamConfirmationFactorOwnerKillTest.class.getName());
    private static final int BASE_PORT = 14500;
    private static final int BASE_MGMT_PORT = 14600;
    private static final int BASE_APP_HTTP_PORT = 14700;
    private static final int NODES = 5;
    private static final int INSTANCES = 5;
    private static final int N_EVENTS = 20;
    private static final int PARTITION = 0;

    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final Duration PLACEMENT_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration FAILOVER_TIMEOUT = Duration.ofSeconds(180);
    private static final long POLL_GAP_NANOS = Duration.ofMillis(20).toNanos();

    private static final String STREAM_SLICE = TestArtifacts.STREAM_REPL_SLICE;
    private static final String BLUEPRINT_ID = "forge.test:stream-cf2-owner-kill:1.0.0";
    private static final String STREAM_NAME = TestArtifacts.streamEngineKey(BLUEPRINT_ID, "repl-failover-events");
    private static final String ERROR_FALLBACK = "{\"error\":\"request failed\"}";
    private static final int DECLARED_FACTOR = 3;
    private static final int DECLARED_CONFIRMATION = 2;

    private static final Pattern EVENT_OBJECT = Pattern.compile("\\{[^{}]*\"offset\"[^{}]*}");
    private static final Pattern OFFSET_FIELD = Pattern.compile("\"offset\"\\s*:\\s*(\\d+)");
    private static final Pattern PAYLOAD_FIELD = Pattern.compile("\"payload\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern NODE_COUNT_FIELD = Pattern.compile("\"nodeCount\"\\s*:\\s*(\\d+)");

    private EmberCluster cluster;
    private String killedNode = "";
    private final HttpOperations http = jdkHttpOperations();

    private record Event(long offset, String payload) {}

    @BeforeAll
    void setUp() {
        var configProvider = ConfigurationProvider.builder()
                                                  .withSystemProperties("aether.")
                                                  .withEnvironment("AETHER_")
                                                  .build();
        cluster = emberCluster(NODES, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "stre", Option.some(configProvider));
        startAndAwaitReady();
    }

    @AfterAll
    void tearDown() {
        if (cluster != null) {
            var leaderPort = cluster.getLeaderManagementPort().or(anyMgmtPort());
            httpDelete(leaderPort, "/api/v1/blueprints/" + BLUEPRINT_ID);
            LifecycleAwait.bestEffort("cluster stop in tearDown()", cluster, cluster.stop());
        }
    }

    @Test
    @Order(1)
    void ownerKilledRightAfterTheLastAck_newOwnerServesEveryAckedEvent() {
        await().atMost(PLACEMENT_TIMEOUT).pollInterval(POLL_INTERVAL).until(() -> committedConfig().isPresent());
        var committed = committedConfig().unwrap();
        LOG.log(System.Logger.Level.INFO,
                "#1564 committed config for {0}: replication_factor={1} confirmation_factor={2}",
                STREAM_NAME,
                committed.replicationFactor(),
                committed.confirmationFactor());
        assertThat(committed.replication())
            .describedAs("the fixture's declared RF 3 / CF 2 reaches the committed runtime config")
            .isEqualTo(new ReplicationFactors(DECLARED_FACTOR, DECLARED_CONFIRMATION));

        // Publish only once the full replica set is registered, so the owner has two peers to replicate to.
        await().atMost(PLACEMENT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> ownerView().map(view -> view.replicas().size() >= DECLARED_FACTOR).or(false));
        publishBatch(appPort(), "pre", N_EVENTS);

        // No replication wait: the owner is killed as soon as the last publish has been acked at CF 2.
        var atAck = ownerView().unwrap();
        killedNode = atAck.ownerNodeId().or("");
        assertThat(killedNode).describedAs("owner identified at the last ack").isNotBlank();
        LOG.log(System.Logger.Level.INFO, "#1564 replica set at the last ack: {0}", atAck);
        LifecycleAwait.nodeBestEffort("kill owner " + killedNode, cluster, cluster.killNode(killedNode));

        LOG.log(System.Logger.Level.INFO, "#1564 survivors holding all {0} acked events after the kill: {1}", N_EVENTS, survivorsHoldingFullHistory());
        awaitOrDump("ownership leaves the killed owner", () -> ownerChanged(killedNode));
        var served = drain(appPort(), 0L, N_EVENTS, deadline(FAILOVER_TIMEOUT));
        LOG.log(System.Logger.Level.INFO,
                "#1564 after the owner's loss: owner={0} served={1}/{2}",
                ownerView().flatMap(ReplicaSetView::ownerNodeId).or("<none>"),
                served.size(),
                N_EVENTS);
        assertContiguousBatch(served, "served by the new owner after the owner's loss");
    }

    /// Live nodes whose LOCAL partition holds offsets `0..N-1` — read from each node's own ring, not from
    /// any owner's registry, so it is a statement about where the data is, independent of who serves it.
    private List<String> survivorsHoldingFullHistory() {
        return cluster.allNodes()
                      .stream()
                      .filter(node -> holdsFullHistory(node.streamReadRouter().replicaSnapshot(STREAM_NAME, PARTITION)))
                      .map(node -> node.self().id())
                      .toList();
    }

    private static boolean holdsFullHistory(ReplicaSetView localView) {
        return localView.earliestRetainedOffset() == 0 && localView.ownerHeadOffset() >= N_EVENTS;
    }

    // --- replica-set view (in-JVM, owner-authoritative) ---------------------

    private Option<ReplicaSetView> ownerView() {
        for (var node : cluster.allNodes()) {
            var view = node.streamReadRouter().replicaSnapshot(STREAM_NAME, PARTITION);

            if (view.servedByOwner()) {
                return Option.some(view);
            }
        }

        return Option.none();
    }

    /// Failure-path observability, as in [AbstractStreamOwnerFailover]: on timeout every live node's
    /// replica view is logged before the timeout propagates, so a stalled ownership move is diagnosable.
    private void awaitOrDump(String what, Callable<Boolean> condition) {
        try {
            await().atMost(FAILOVER_TIMEOUT).pollInterval(POLL_INTERVAL).alias(what).until(condition);
        } catch (ConditionTimeoutException timeout) {
            cluster.allNodes()
                   .forEach(node -> LOG.log(System.Logger.Level.WARNING,
                                            "#1564 timeout ({0}) view self={1}: {2}",
                                            what,
                                            node.self(),
                                            node.streamReadRouter().replicaSnapshot(STREAM_NAME, PARTITION)));
            throw timeout;
        }
    }

    private Option<StreamConfig> committedConfig() {
        return Option.from(cluster.allNodes().stream().findFirst())
                     .flatMap(node -> node.kvStore()
                                          .getTyped(StreamConfigKey.streamConfigKey(STREAM_NAME),
                                                    StreamConfigValue.class))
                     .map(StreamConfigValue::config);
    }

    private boolean ownerChanged(String oldOwner) {
        return ownerView().flatMap(ReplicaSetView::ownerNodeId)
                          .map(owner -> !owner.isBlank() && !owner.equals(oldOwner))
                          .or(false);
    }

    // --- assertions ---------------------------------------------------------

    private static void assertContiguousBatch(List<Event> events, String phase) {
        assertThat(events)
            .describedAs("all %d events %s (no loss, no dups)", N_EVENTS, phase)
            .hasSize(N_EVENTS);

        for (int i = 0; i < N_EVENTS; i++) {
            assertThat(events.get(i).offset()).describedAs("event %d offset", i).isEqualTo((long) i);
            assertThat(events.get(i).payload()).describedAs("event %d payload", i).isEqualTo("pre-" + i);
        }
    }

    // --- publish / read -----------------------------------------------------

    private void publishBatch(int port, String tag, int count) {
        for (int i = 0; i < count; i++) {
            publish(port, tag + "-" + i);
        }
    }

    private void publish(int port, String payload) {
        var response = httpPost(port, "/api/stream-repl/publish", "{\"payload\":\"" + payload + "\"}");

        assertThat(response).describedAs("publish '%s' must succeed", payload)
                            .doesNotContain("\"error\"")
                            .contains("published");
    }

    private List<Event> drain(int port, long base, int count, long deadlineNanos) {
        var collected = new ArrayList<Event>();
        var offset = base;

        while (collected.size() < count && System.nanoTime() < deadlineNanos) {
            var events = readEvents(port, offset, count);

            if (events.isEmpty()) {
                LockSupport.parkNanos(POLL_GAP_NANOS);
                continue;
            }

            collected.addAll(events);
            offset = events.getLast().offset() + 1;
        }

        return List.copyOf(collected);
    }

    private List<Event> readEvents(int port, long fromOffset, int maxEvents) {
        var body = "{\"fromOffset\":" + fromOffset + ",\"maxEvents\":" + maxEvents + "}";

        return parseEvents(httpPost(port, "/api/stream-repl/read", body));
    }

    private static List<Event> parseEvents(String body) {
        var events = new ArrayList<Event>();
        Matcher objects = EVENT_OBJECT.matcher(body);

        while (objects.find()) {
            var object = objects.group();
            Matcher offset = OFFSET_FIELD.matcher(object);
            Matcher payload = PAYLOAD_FIELD.matcher(object);

            if (offset.find() && payload.find()) {
                events.add(new Event(Long.parseLong(offset.group(1)), payload.group(1)));
            }
        }

        return List.copyOf(events);
    }

    // --- deployment + readiness --------------------------------------------

    private void startAndAwaitReady() {
        LifecycleAwait.settled("cluster start in startAndAwaitReady()", cluster, cluster.start());

        await().atMost(WAIT_TIMEOUT).pollInterval(POLL_INTERVAL).until(() -> cluster.currentLeader().isPresent());
        await().atMost(WAIT_TIMEOUT).pollInterval(POLL_INTERVAL).until(() -> allNodesAreMembers(NODES));

        deployStreamSlice();

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(this::failIfSliceFailed)
               .until(this::appHttpReady);
    }

    private void deployStreamSlice() {
        var blueprint = """
            id = "%s"

            [[slices]]
            artifact = "%s"
            instances = %d
            """.formatted(BLUEPRINT_ID, STREAM_SLICE, INSTANCES);
        var leaderPort = cluster.getLeaderManagementPort().or(anyMgmtPort());
        var response = postBlueprintWithRetry(leaderPort, blueprint);

        assertThat(response).describedAs("#1564 stream-slice deployment")
                            .doesNotContain("\"error\"")
                            .contains("\"status\":\"applied\"");
    }

    private boolean appHttpReady() {
        var ports = cluster.getAvailableAppHttpPorts();

        if (ports.isEmpty()) {
            return false;
        }

        var body = httpPost(ports.getFirst(), "/api/stream-repl/read", "{\"fromOffset\":0,\"maxEvents\":1}");

        return !body.contains("\"error\"") && body.contains("events");
    }

    private void failIfSliceFailed() {
        var failed = cluster.slicesStatus()
                            .stream()
                            .anyMatch(s -> s.artifact().equals(STREAM_SLICE) && s.state().equals("FAILED"));

        if (failed) {
            throw new AssertionError("#1564 stream slice deployment FAILED: " + STREAM_SLICE);
        }
    }

    private int appPort() {
        return cluster.getAvailableAppHttpPorts()
                      .stream()
                      .findFirst()
                      .orElseThrow(() -> new AssertionError("No app-http route is ready"));
    }

    private int anyMgmtPort() {
        return cluster.status().nodes().getFirst().mgmtPort();
    }

    private static long deadline(Duration budget) {
        return System.nanoTime() + budget.toNanos();
    }

    private boolean allNodesAreMembers(int expected) {
        var leaderPort = cluster.getLeaderManagementPort().or(anyMgmtPort());
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + leaderPort + "/api/v1/health"))
                                 .GET()
                                 .timeout(Duration.ofSeconds(5))
                                 .build();
        return http.sendString(request)
                   .await()
                   .map(r -> r.statusCode() == 200 && healthHasFullMembership(r.body(), expected))
                   .or(false);
    }

    private static boolean healthHasFullMembership(String body, int expected) {
        if (!body.contains("\"quorum\":true")) {
            return false;
        }

        var matcher = NODE_COUNT_FIELD.matcher(body);

        return matcher.find() && Integer.parseInt(matcher.group(1)) >= expected;
    }

    // --- HTTP ---------------------------------------------------------------

    private String postBlueprintWithRetry(int port, String body) {
        var lastResponse = ERROR_FALLBACK;

        for (int attempt = 1; attempt <= 3; attempt++) {
            lastResponse = httpPostToml(port, "/api/v1/blueprints", body);

            if (!lastResponse.contains("\"error\"")) {
                return lastResponse;
            }

            if (attempt < 3) {
                LockSupport.parkNanos(Duration.ofSeconds(2).toNanos());
            }
        }

        return lastResponse;
    }

    private String httpPostToml(int port, String path, String body) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .header("Content-Type", "application/toml")
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .timeout(Duration.ofSeconds(10))
                                 .build();
        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }

    private String httpPost(int port, String path, String body) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .header("Content-Type", "application/json")
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .timeout(Duration.ofSeconds(15))
                                 .build();
        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }

    private String httpDelete(int port, String path) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .DELETE()
                                 .timeout(Duration.ofSeconds(10))
                                 .build();
        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }
}
