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

/// #1547 — the stream replication factor DEFAULT is what keeps a dead owner's partition under terminal
/// removal. A dead owner never returns, so a partition held on one node is lost with it; the ruling makes
/// the default (and the minimum) 3.
///
/// The fixture is the `test-stream` blueprint, whose `[streams.test-events]` section declares NO
/// `replicas` key — the replication factor under test is the DEFAULT, resolved by the provisioning config
/// binder from `StreamConfig.DEFAULT`. The flow: 5-node Ember cluster → deploy → the committed config reads
/// `replicas = 3` → publish N events → wait until every placed non-owner replica has confirmed the tail →
/// kill the partition's HRW owner → at least two SURVIVORS still hold all N events → a REPLACEMENT joins
/// under a fresh node id (terminal removal: the dead identity is never reused).
///
/// What the enabled test proves, precisely: an event that reached the default replica set before the
/// owner died is still held by the survivors after the owner's terminal removal. It does NOT prove that
/// every ACKED event survives: with the default `min-sync-replicas` the publish acks on the owner's local
/// WAL fsync, so an event acked in the replication window before the kill is not covered — that is the
/// `min-sync-replicas` knob's guarantee, not the replication factor's, and this test waits the window out.
/// The acked-record claim, at `replicas = min-sync-replicas = 3`, is #1549's test: a blueprint's
/// dashed `min-sync-replicas` does not reach the runtime today, so it cannot be declared here.
///
/// [#replacementJoined_newOwnerServesEveryReplicatedEvent] then proves the survivors SERVE those events. Until #1550
/// ownership never left the killed owner, and a tripwire here asserted that stall; #1550 retired it.
///
/// Discriminating by construction: with the pre-#1547 default (`replicas = 1`) the owner is the only
/// copy, the tail wait is vacuous (there is no non-owner replica), and no survivor holds any event, so the
/// survivor assertion fails. The committed factor and the pre-kill replica-set size are asserted AFTER it,
/// so that control fails on the lost data rather than on the placement.
///
/// Ember equivalence: the owner kill is [EmberCluster#killNode] (`node.stop()`, a SWIM leave), not a
/// SIGKILL — it exercises the ownership move and the fresh-identity replacement deterministically, not
/// failure-detection latency.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StreamDefaultRfOwnerReplacementTest {
    private static final System.Logger LOG = System.getLogger(StreamDefaultRfOwnerReplacementTest.class.getName());
    private static final int BASE_PORT = 38400;
    private static final int BASE_MGMT_PORT = 38500;
    private static final int BASE_APP_HTTP_PORT = 38600;
    private static final int NODES = 5;
    private static final int INSTANCES = 5;
    private static final int N_EVENTS = 20;
    private static final int PARTITION = 0;

    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final Duration PLACEMENT_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration REPLICATION_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration FAILOVER_TIMEOUT = Duration.ofSeconds(180);
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(90);
    private static final long POLL_GAP_NANOS = Duration.ofMillis(20).toNanos();

    private static final String STREAM_SLICE = TestArtifacts.STREAM_SLICE;
    private static final String BLUEPRINT_ID = "forge.test:stream-default-rf:1.0.0";
    private static final String STREAM_NAME = TestArtifacts.streamEngineKey(BLUEPRINT_ID, "test-events");
    private static final String ERROR_FALLBACK = "{\"error\":\"request failed\"}";

    private static final Pattern EVENT_OBJECT = Pattern.compile("\\{[^{}]*\"offset\"[^{}]*}");
    private static final Pattern OFFSET_FIELD = Pattern.compile("\"offset\"\\s*:\\s*(\\d+)");
    private static final Pattern PAYLOAD_FIELD = Pattern.compile("\"payload\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern NODE_COUNT_FIELD = Pattern.compile("\"nodeCount\"\\s*:\\s*(\\d+)");

    private EmberCluster cluster;
    private String killedOwner = "";
    private final HttpOperations http = jdkHttpOperations();

    private record Event(long offset, String payload) {}

    @BeforeAll
    void setUp() {
        var configProvider = ConfigurationProvider.builder()
                                                  .withSystemProperties("aether.")
                                                  .withEnvironment("AETHER_")
                                                  .build();
        cluster = emberCluster(NODES, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "sdrf", Option.some(configProvider));
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
    void ownerTerminallyRemoved_replacementJoinsFresh_survivorsHoldEveryReplicatedEvent() {
        await().atMost(PLACEMENT_TIMEOUT).pollInterval(POLL_INTERVAL).until(() -> committedConfig().isPresent());
        var committed = committedConfig().unwrap();
        LOG.log(System.Logger.Level.INFO,
                "#1547 committed config for {0}: replicas={1} minSyncReplicas={2}",
                STREAM_NAME,
                committed.replicas(),
                committed.minSyncReplicas());

        await().atMost(PLACEMENT_TIMEOUT).pollInterval(POLL_INTERVAL).until(() -> ownerView().isPresent());
        publishBatch(appPort(), "pre", N_EVENTS);
        var published = drain(appPort(), 0L, N_EVENTS, deadline(DRAIN_TIMEOUT));
        assertContiguousBatch(published, "published before the kill");

        // Wait out the replication window: the committed factor's full replica set is registered and every
        // non-owner in it has confirmed the tail. At the default RF=3 on 5 nodes that is two peers; at RF=1
        // there are none and this holds as soon as the owner has the tail.
        await().atMost(REPLICATION_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> ownerView().map(view -> placedAndReplicated(view, committed.replicas())).or(false));
        var preKill = ownerView().unwrap();
        killedOwner = preKill.ownerNodeId().or("");
        LOG.log(System.Logger.Level.INFO, "#1547 pre-kill replica set: {0}", preKill);
        assertThat(killedOwner).describedAs("HRW owner identified before the kill").isNotBlank();

        LifecycleAwait.nodeBestEffort("kill owner " + killedOwner, cluster, cluster.killNode(killedOwner));
        var holders = survivorsHoldingFullHistory();
        LOG.log(System.Logger.Level.INFO, "#1547 survivors holding all {0} events after the kill: {1}", N_EVENTS, holders);
        assertThat(holders)
            .describedAs("survivors holding every replicated event after the owner's terminal removal")
            .hasSizeGreaterThanOrEqualTo(StreamConfig.MIN_REPLICAS - 1);

        var replacement = cluster.addNode().await().unwrap();
        assertThat(replacement.id()).describedAs("the replacement joins under a FRESH identity").isNotEqualTo(killedOwner);
        await().atMost(WAIT_TIMEOUT).pollInterval(POLL_INTERVAL).until(() -> allNodesAreMembers(NODES));

        assertThat(committed.replicas())
            .describedAs("the stream declares no replicas, so the committed factor is the default")
            .isEqualTo(StreamConfig.MIN_REPLICAS);
        assertThat(preKill.replicas())
            .describedAs("the default factor placed owner + 2 peers before the kill")
            .hasSize(StreamConfig.MIN_REPLICAS);
    }

    /// The serving half (#1550 fixed ownership moving off a killed owner). Runs after the test above, on the same
    /// cluster.
    @Test
    @Order(2)
    void replacementJoined_newOwnerServesEveryReplicatedEvent() {
        awaitOrDump("a surviving replica takes ownership", () -> ownerChanged(killedOwner));
        var served = drain(appPort(), 0L, N_EVENTS, deadline(FAILOVER_TIMEOUT));
        LOG.log(System.Logger.Level.INFO,
                "#1547 after the replacement joined: owner={0} served={1}/{2}",
                ownerView().flatMap(ReplicaSetView::ownerNodeId).or("<none>"),
                served.size(),
                N_EVENTS);
        assertContiguousBatch(served, "served by the new owner after the replacement joined");
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
                                            "#1547 timeout ({0}) view self={1}: {2}",
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

    private static boolean placedAndReplicated(ReplicaSetView view, int committedReplicas) {
        var tail = view.ownerHeadOffset() - 1;

        return view.ownerHeadOffset() >= N_EVENTS && view.replicas().size() >= committedReplicas && view.replicas()
                                                                                                      .stream()
                                                                                                      .filter(r -> !r.hrwOwner())
                                                                                                      .allMatch(r -> r.confirmedOffset() >= tail);
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
        var response = httpPost(port, "/api/stream/publish", "{\"payload\":\"" + payload + "\"}");

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

        return parseEvents(httpPost(port, "/api/stream/read", body));
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

        assertThat(response).describedAs("default-RF stream-slice deployment")
                            .doesNotContain("\"error\"")
                            .contains("\"status\":\"applied\"");
    }

    private boolean appHttpReady() {
        var ports = cluster.getAvailableAppHttpPorts();

        if (ports.isEmpty()) {
            return false;
        }

        var body = httpPost(ports.getFirst(), "/api/stream/read", "{\"fromOffset\":0,\"maxEvents\":1}");

        return !body.contains("\"error\"") && body.contains("events");
    }

    private void failIfSliceFailed() {
        var failed = cluster.slicesStatus()
                            .stream()
                            .anyMatch(s -> s.artifact().equals(STREAM_SLICE) && s.state().equals("FAILED"));

        if (failed) {
            throw new AssertionError("default-RF stream slice deployment FAILED: " + STREAM_SLICE);
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
