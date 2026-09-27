// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.replication.ReplicaPlacement;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;

/// #1555 regression: a partitioned stream owner that HEALS back must not act as owner on a stale view or a short
/// ring. The schedule is the v1555 verifier's nl1/nl3 one, bounded to at most [#REQUEST_BUDGET] HTTP requests
/// (ownership and placement are observed in-JVM, never polled over HTTP):
///
///   1. RF=2 stream on 5 nodes, the HRW owner deliberately NOT the consensus leader; publish 20 `pre` events.
///   2. Black-hole the owner until another node owns and serves the partition; publish 5 `post` events there.
///   3. Heal the ex-owner; wait for a single settled owner; publish 5 `heal` events.
///   4. Read the partition through every node.
///
/// Before the #1555 promotion gate the healed ex-owner either reclaimed ownership on its 20-event ring and wrote
/// `heal-*` at offsets 20..24 over the acked `post-*` (nl1: replicas diverge, acked records lost), or kept
/// claiming `servedByOwner` on a stale committed view (nl3: a zombie serving truncated reads). The test pins
/// both: every node must read exactly `pre-0..19, post-0..4, heal-0..4`, and from the heal onward no node may
/// claim `servedByOwner` unless its own committed ownership record names it.
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StreamOwnerHealBackTest {
    private static final System.Logger LOG = System.getLogger(StreamOwnerHealBackTest.class.getName());
    private static final int NODES = 5;
    private static final int PARTITION = 0;
    private static final int BASE_PORT = 26000;
    private static final int BASE_MGMT_PORT = 26100;
    private static final int BASE_APP_HTTP_PORT = 26200;
    private static final int REQUEST_BUDGET = 60;
    private static final Duration FORM_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration MOVE_TIMEOUT = Duration.ofSeconds(180);
    private static final Duration SETTLE_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration POLL = Duration.ofMillis(500);
    private static final String STREAM_SLICE = TestArtifacts.STREAM_REPL_SLICE;
    private static final String BLUEPRINT_ID = "forge.test:stream-owner-heal-back:1.0.0";
    private static final String STREAM_NAME = TestArtifacts.streamEngineKey(BLUEPRINT_ID, "repl-failover-events");
    private static final Pattern EVENT_OBJECT = Pattern.compile("\\{[^{}]*\"offset\"[^{}]*}");
    private static final Pattern OFFSET_FIELD = Pattern.compile("\"offset\"\\s*:\\s*(\\d+)");
    private static final Pattern PAYLOAD_FIELD = Pattern.compile("\"payload\"\\s*:\\s*\"([^\"]*)\"");

    private final HttpOperations http = jdkHttpOperations();
    private final AtomicInteger requests = new AtomicInteger();
    private final List<String> zombieClaims = new CopyOnWriteArrayList<>();
    private final AtomicBoolean watchingZombies = new AtomicBoolean();
    private EmberCluster cluster;
    private volatile String blackholed = "";

    private record Event(long offset, String payload) {}

    @BeforeAll
    void setUp() {
        var configProvider = ConfigurationProvider.builder()
                                                  .withSystemProperties("aether.")
                                                  .withEnvironment("AETHER_")
                                                  .build();
        var prefix = prefixWhoseOwnerIsNotTheLeader();

        cluster = emberCluster(NODES, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, prefix, Option.some(configProvider));
        cluster.start()
               .await();
        await().atMost(FORM_TIMEOUT)
               .pollInterval(POLL)
               .until(() -> cluster.currentLeader()
                                   .isPresent());
        deploy();
        await().atMost(FORM_TIMEOUT)
               .pollInterval(POLL)
               .until(this::placedAndServed);
        awaitAppRoutes();
    }

    /// The app route answers once its slice routes are synchronized; at most five reads, three seconds apart.
    private void awaitAppRoutes() {
        var ready = false;

        for (var attempt = 0; attempt < 5 && !ready; attempt++) {
            ready = post(anyPort(), "/api/stream-repl/read", "{\"fromOffset\":0,\"maxEvents\":1}", "application/json").contains("events");

            if (!ready) {
                LockSupport.parkNanos(Duration.ofSeconds(3).toNanos());
            }
        }

        assertThat(ready).as("app routes ready").isTrue();
    }

    @AfterAll
    void tearDown() {
        watchingZombies.set(false);

        if (cluster != null) {
            cluster.allNodes()
                   .forEach(node -> node.blackhole(false));
            cluster.stop()
                   .await();
        }
    }

    @Test
    void healedExOwner_neverActsOnStaleViewOrShortRing_everyNodeReadsTheAckedHistory() {
        publish(anyPort(), "pre", 20);
        var owner = claimants().stream()
                               .findFirst()
                               .orElseThrow();
        LOG.log(System.Logger.Level.INFO, "#1555 heal-back: owner {0}, leader {1}", owner, cluster.currentLeader().or("?"));

        blackholed = owner;
        node(owner).blackhole(true);
        await().atMost(MOVE_TIMEOUT)
               .pollInterval(POLL)
               .until(() -> !claimantsExcluding(owner).isEmpty());
        publish(anyPort(), "post", 5);

        var watcher = Thread.ofPlatform()
                            .daemon()
                            .start(this::watchZombieClaims);
        node(owner).blackhole(false);
        blackholed = "";
        await().atMost(SETTLE_TIMEOUT)
               .pollInterval(POLL)
               .until(this::singleSettledOwner);
        publish(anyPort(), "heal", 5);

        var expected = expectedPayloads();
        var reads = cluster.allNodes()
                           .stream()
                           .map(node -> node.self().id() + " -> " + readAll(portOf(node)))
                           .toList();
        watchingZombies.set(false);
        joinQuietly(watcher);
        LOG.log(System.Logger.Level.INFO, "#1555 heal-back reads: {0}; zombie claims: {1}; requests: {2}", reads, zombieClaims, requests.get());

        assertThat(zombieClaims).as("no node may claim servedByOwner unless its own committed ownership record names it")
                                .isEmpty();
        assertThat(reads).as("every node reads exactly the acked history, in order, with no heal-* over post-*")
                         .allSatisfy(read -> assertThat(read).endsWith(" -> " + expected));
        assertThat(requests.get()).as("bounded probe").isLessThanOrEqualTo(REQUEST_BUDGET);
    }

    private static List<String> expectedPayloads() {
        return Stream.of(tagged("pre", 20), tagged("post", 5), tagged("heal", 5))
                     .flatMap(List::stream)
                     .toList();
    }

    private static List<String> tagged(String tag, int count) {
        return IntStream.range(0, count)
                        .mapToObj(i -> tag + "-" + i)
                        .toList();
    }

    // ---- in-JVM ownership observation (no HTTP) ----

    /// The zombie invariant, sampled every 200 ms from the heal to the end: a node claiming `servedByOwner` must
    /// hold a committed ownership record naming itself. A violation counts only when it is seen on two consecutive
    /// samples, so the instant of a legitimate ownership flip (claim and record read a moment apart) is not one.
    private void watchZombieClaims() {
        watchingZombies.set(true);
        var previous = List.<String> of();

        while (watchingZombies.get()) {
            var current = zombieSample();

            current.stream()
                   .filter(previous::contains)
                   .forEach(zombieClaims::add);
            previous = current;
            LockSupport.parkNanos(Duration.ofMillis(200).toNanos());
        }
    }

    private List<String> zombieSample() {
        return cluster.allNodes()
                      .stream()
                      .filter(node -> node.streamReadRouter()
                                          .replicaSnapshot(STREAM_NAME, PARTITION)
                                          .servedByOwner())
                      .filter(node -> !committedOwner(node).equals(node.self().id()))
                      .map(node -> node.self().id() + " claims servedByOwner with committed owner " + committedOwner(node))
                      .toList();
    }

    private static String committedOwner(AetherNode node) {
        return node.kvStore()
                   .getTyped(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(STREAM_NAME, PARTITION),
                             StreamPartitionOwnershipValue.class)
                   .map(value -> value.owner().id())
                   .or("none");
    }

    private boolean placedAndServed() {
        return cluster.allNodes()
                      .stream()
                      .map(node -> node.streamReadRouter().replicaSnapshot(STREAM_NAME, PARTITION))
                      .anyMatch(view -> view.servedByOwner() && view.replicas().size() >= 2);
    }

    private boolean singleSettledOwner() {
        var claims = claimants();

        return claims.size() == 1 && committedOwner(node(claims.getFirst())).equals(claims.getFirst());
    }

    private List<String> claimants() {
        return claimantsExcluding("");
    }

    private List<String> claimantsExcluding(String excluded) {
        return cluster.allNodes()
                      .stream()
                      .filter(node -> !node.self().id().equals(excluded))
                      .filter(node -> node.streamReadRouter()
                                          .replicaSnapshot(STREAM_NAME, PARTITION)
                                          .servedByOwner())
                      .map(node -> node.self().id())
                      .sorted()
                      .toList();
    }

    private AetherNode node(String id) {
        return cluster.getNode(id)
                      .toResult(Causes.cause("no node " + id))
                      .unwrap();
    }

    private int anyPort() {
        return cluster.allNodes()
                      .stream()
                      .filter(node -> !node.self().id().equals(blackholed))
                      .map(StreamOwnerHealBackTest::portOf)
                      .sorted()
                      .findFirst()
                      .orElseThrow();
    }

    private static int portOf(AetherNode node) {
        return node.appHttpServer()
                   .boundPort()
                   .or(0);
    }

    /// Owner placement is HRW over the configured ids; pick the node-id prefix whose owner is not `prefix-1`, the
    /// node the sorted-first leader rule tends to elect, so black-holing the owner keeps the leader (nl schedule).
    private static String prefixWhoseOwnerIsNotTheLeader() {
        for (var c = 'a'; c <= 'z'; c++) {
            var prefix = "hb" + c;

            if (!predictedOwner(prefix).equals(prefix + "-1")) {
                return prefix;
            }
        }

        throw new AssertionError("no prefix places the owner away from node 1");
    }

    private static String predictedOwner(String prefix) {
        var ids = IntStream.rangeClosed(1, NODES)
                           .mapToObj(i -> NodeId.nodeId(prefix + "-" + i).unwrap())
                           .toList();

        return ReplicaPlacement.place(STREAM_NAME, PARTITION, ids, 2)
                               .map(placement -> placement.owner().id())
                               .or("?");
    }

    // ---- bounded HTTP ----

    private void publish(int port, String tag, int count) {
        for (var i = 0; i < count; i++) {
            assertThat(publishOne(port, tag + "-" + i)).as("publish %s-%d", tag, i).contains("published");
        }
    }

    /// One publish, retried at most twice a second apart on a transient refusal (ownership still settling).
    private String publishOne(int port, String payload) {
        var response = "";

        for (var attempt = 0; attempt < 3 && !response.contains("published"); attempt++) {
            if (attempt > 0) {
                LockSupport.parkNanos(Duration.ofSeconds(1).toNanos());
            }

            response = post(port, "/api/stream-repl/publish", "{\"payload\":\"" + payload + "\"}", "application/json");
        }

        return response;
    }

    /// Up to two attempts, a second apart, to read the whole partition through `port`; the payloads read.
    private List<String> readAll(int port) {
        var best = List.<String> of();

        for (var attempt = 0; attempt < 2 && best.size() < 30; attempt++) {
            best = parse(post(port, "/api/stream-repl/read", "{\"fromOffset\":0,\"maxEvents\":30}", "application/json"))
                   .stream()
                   .map(Event::payload)
                   .toList();

            if (best.size() < 30) {
                LockSupport.parkNanos(Duration.ofSeconds(1).toNanos());
            }
        }

        return best;
    }

    private static List<Event> parse(String body) {
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

        return events;
    }

    private String post(int port, String path, String body, String contentType) {
        if (requests.incrementAndGet() > REQUEST_BUDGET) {
            throw new AssertionError("request budget of " + REQUEST_BUDGET + " exhausted");
        }

        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .header("Content-Type", contentType)
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .timeout(Duration.ofSeconds(10))
                                 .build();

        return http.sendString(request)
                   .await()
                   .map(response -> response.body())
                   .or("{\"error\":\"request failed\"}");
    }

    private void deploy() {
        var blueprint = """
            id = "%s"

            [[slices]]
            artifact = "%s"
            instances = %d
            """.formatted(BLUEPRINT_ID, STREAM_SLICE, NODES);
        var port = cluster.getLeaderManagementPort()
                          .or(cluster.status()
                                     .nodes()
                                     .getFirst()
                                     .mgmtPort());
        var response = "";

        for (var attempt = 0; attempt < 3 && !response.contains("\"status\":\"applied\""); attempt++) {
            response = post(port, "/api/v1/blueprints", blueprint, "application/toml");
        }

        assertThat(response).contains("\"status\":\"applied\"");
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(Duration.ofSeconds(2));
        } catch (InterruptedException e) {
            Thread.currentThread()
                  .interrupt();
        }
    }
}
