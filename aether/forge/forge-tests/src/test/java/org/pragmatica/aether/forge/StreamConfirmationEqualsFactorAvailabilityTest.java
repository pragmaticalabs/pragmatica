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

/// #1564 acceptance B — at `confirmation_factor = replication_factor` a write needs every replica, so losing one
/// core refuses writes to the partition until a replacement holds the data. The fixture is `test-stream-acked`
/// (`[streams.acked-events]`: RF 3, CF 3) on a 3-node Ember cluster, where RF 3 places the partition on every core.
/// The flow: publishes succeed with every core up → kill one NON-owner core → publishes to the partition fail (the
/// owner cannot reach CF 3: `NOT_ENOUGH_REPLICAS` once the dead peer leaves the registry, a replication timeout
/// while it is still registered) → a replacement joins under a fresh identity → it is placed into the partition's
/// replica set, catches up, and publishes succeed again (#1732: the voter install that makes a replacement a
/// placement member did not trigger a reconcile, so the replacement never entered an existing replica set). This
/// is the availability half of the policy: writes tolerate `RF − CF` = 0 replica losses.
///
/// Ember equivalence: the kill is [EmberCluster#killNode] (`node.stop()`, a SWIM leave), not a SIGKILL.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StreamConfirmationEqualsFactorAvailabilityTest {
    private static final System.Logger LOG = System.getLogger(StreamConfirmationEqualsFactorAvailabilityTest.class.getName());
    private static final int BASE_PORT = 16500;
    private static final int BASE_MGMT_PORT = 16600;
    private static final int BASE_APP_HTTP_PORT = 16700;
    private static final int NODES = 3;
    private static final int INSTANCES = 3;
    private static final int N_EVENTS = 20;
    private static final int PARTITION = 0;

    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final Duration PLACEMENT_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration FAILOVER_TIMEOUT = Duration.ofSeconds(180);
    private static final long POLL_GAP_NANOS = Duration.ofMillis(20).toNanos();

    private static final String STREAM_SLICE = TestArtifacts.STREAM_ACKED_SLICE;
    private static final String BLUEPRINT_ID = "forge.test:stream-cf-equals-rf:1.0.0";
    private static final String STREAM_NAME = TestArtifacts.streamEngineKey(BLUEPRINT_ID, "acked-events");
    private static final String ERROR_FALLBACK = "{\"error\":\"request failed\"}";
    private static final int DECLARED_FACTOR = 3;
    private static final int REFUSED_ATTEMPTS = 3;

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
    void everyCoreUp_publishesSucceed_atConfirmationEqualToFactor() {
        await().atMost(PLACEMENT_TIMEOUT).pollInterval(POLL_INTERVAL).until(() -> committedConfig().isPresent());
        assertThat(committedConfig().unwrap().replication())
            .describedAs("the fixture's declared RF 3 / CF 3 reaches the committed runtime config")
            .isEqualTo(new ReplicationFactors(DECLARED_FACTOR, DECLARED_FACTOR));
        await().atMost(PLACEMENT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> ownerView().map(view -> view.replicas().size() >= DECLARED_FACTOR).or(false));

        publishBatch(appPort(), "pre", N_EVENTS);
        assertContiguousBatch(drain(appPort(), 0L, N_EVENTS, deadline(FAILOVER_TIMEOUT)), "read back with every core up");
    }

    /// Mutation "the barrier reads CF as 1" turns this red: the publishes would succeed on the owner alone.
    @Test
    @Order(2)
    void oneCoreLost_publishesToThePartitionAreRefused() {
        var owner = ownerView().flatMap(ReplicaSetView::ownerNodeId).or("");
        assertThat(owner).describedAs("owner identified before the kill").isNotBlank();
        killedNode = cluster.allNodes()
                            .stream()
                            .map(node -> node.self().id())
                            .filter(id -> !id.equals(owner))
                            .findFirst()
                            .orElseThrow();
        LOG.log(System.Logger.Level.INFO, "#1564 killing non-owner core {0} (owner {1})", killedNode, owner);
        LifecycleAwait.nodeBestEffort("kill core " + killedNode, cluster, cluster.killNode(killedNode));

        for (int attempt = 0; attempt < REFUSED_ATTEMPTS; attempt++) {
            var response = httpPost(appPort(), "/api/stream-acked/publish", "{\"payload\":\"refused-" + attempt + "\"}");
            LOG.log(System.Logger.Level.INFO, "#1564 publish {0} with one core lost: {1}", attempt, response);
            assertThat(response).describedAs("a publish at CF == RF with one of three cores lost must be refused")
                                .doesNotContain("\"published\"");
        }
    }

    /// #1732 acceptance: a replacement core that joins under a fresh identity enters the partition's replica set,
    /// catches up, and CF 3 is met again, so publishes succeed.
    ///
    /// Positive control, taken BEFORE the replacement joins: the owner's view is readable and lists exactly the two
    /// survivors, so "the replacement is placed" afterwards is a change of that very view and not an artefact of an
    /// unreadable one. Before the fix the replacement stayed out of the view for good (measured 2026-09-29 on
    /// cloudbb-2 at `d4bf26db9`: three minutes after `stre-4` was a member, the owner's registry still held only the
    /// two survivors and `stre-4`'s local view was empty), so this test timed out in [#awaitOrDump].
    ///
    /// Also observed in that run, and consistent with the outcome-unknown contract: the first publish in
    /// [#oneCoreLost_publishesToThePartitionAreRefused] answered `REPLICATION_TIMEOUT` ("outcome unknown") while the dead
    /// peer was still registered, and it HAD appended — the owner's head moved 20 → 21 on both survivors.
    @Test
    @Order(3)
    void replacementJoined_isPlaced_catchesUp_publishesSucceedAgain() {
        var survivors = ownerView().map(view -> view.replicas()
                                                    .stream()
                                                    .map(replica -> replica.nodeId())
                                                    .toList())
                                   .or(List.of());

        assertThat(survivors).describedAs("positive control: the owner view is readable and lists the two survivors")
                             .hasSize(2)
                             .doesNotContain(killedNode);

        joinReplacement();

        awaitOrDump("the replacement enters the partition's replica set", this::replacementPlaced);
        awaitOrDump("publishes succeed once the replacement is placed",
                    () -> httpPost(appPort(), "/api/stream-acked/publish", "{\"payload\":\"after\"}").contains("\"published\""));
        awaitOrDump("the replacement holds the pre-kill history", this::replacementHoldsFullHistory);
        LOG.log(System.Logger.Level.INFO, "#1564 nodes holding the pre-kill history after the replacement: {0}", survivorsHoldingFullHistory());
    }

    private String replacementNode = "";

    private void joinReplacement() {
        var replacement = cluster.addNode().await().unwrap();

        assertThat(replacement.id()).describedAs("the replacement joins under a FRESH identity").isNotEqualTo(killedNode);
        replacementNode = replacement.id();
        await().atMost(WAIT_TIMEOUT).pollInterval(POLL_INTERVAL).until(() -> allNodesAreMembers(NODES));
    }

    private boolean replacementPlaced() {
        return ownerView().map(view -> view.replicas()
                                           .stream()
                                           .anyMatch(replica -> replica.nodeId()
                                                                       .equals(replacementNode)))
                          .or(false);
    }

    private boolean replacementHoldsFullHistory() {
        return cluster.allNodes()
                      .stream()
                      .filter(node -> node.self().id().equals(replacementNode))
                      .anyMatch(node -> holdsFullHistory(node.streamReadRouter().replicaSnapshot(STREAM_NAME, PARTITION)));
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
        var response = httpPost(port, "/api/stream-acked/publish", "{\"payload\":\"" + payload + "\"}");

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

        return parseEvents(httpPost(port, "/api/stream-acked/read", body));
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

        var body = httpPost(ports.getFirst(), "/api/stream-acked/read", "{\"fromOffset\":0,\"maxEvents\":1}");

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
