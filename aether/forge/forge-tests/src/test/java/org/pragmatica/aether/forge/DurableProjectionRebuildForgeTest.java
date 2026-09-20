// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpRequest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;


/// #1333 acceptance — a durable projection wired to the runtime, rebuilt through the operator surface,
/// on a real 3-node cluster.
///
/// The fixture is `ProjectionSlice` (test-durable-topic): a `Projection` over the single-partition
/// durable topic `projection-events`, attached through the provisioned `ProjectionRuntime`, folding
/// `state * 10 + seq`. One partition and the slice on every node means exactly one node consumes the
/// group, and its in-process store is the one that fills — the others stay empty by construction, which
/// is why every model read below scans the nodes and takes the one that holds a model.
///
/// Scenario: seq 1..6 → model `123456`. Arm poison for seq 3 on every node. `POST …/rebuild/{group}` on
/// the node the groups route names as the consumer. The rewound consumer replays every retained offset
/// (the readiness warm-ups, seq 0, fold to 0 and sit at the front), dead-letters seq 3 after the
/// 5-attempt budget, and the runtime's cursor report — stamped with the rewind's token — lets the store
/// skip that offset and go LIVE: model `12456`, exactly ONE dead letter, groups route LIVE with the
/// committed cursor past the replayed head under the rewind's epoch. Then seq 7 → `124567` and still one
/// dead letter.
///
/// **The dead-letter detector.** The `.dlq` stream cannot be read through the catalog routes (its name has
/// four colon-separated parts), so "exactly one dead letter" is asserted from the fixture's per-seq attempt
/// counts: a dead-lettered event is exactly one that hit the 5-attempt budget. A second dead letter — the
/// cadence-gap cascade the in-JVM twin's mutation M1 shows — would be a second seq at 5 replay attempts.
/// A live event refused `Rebuilding` once and admitted on its first retry shows as 2 attempts, which is the
/// stated one-burned-retry cost, never 5. So this test never READS the DLQ: "one dead letter" here is an
/// inference from the fixture's attempt counter; the `.dlq` stream itself is read only in the in-JVM twin
/// (`DurableProjectionRebuildTest.deadLettersForGroup`, over the real `DeadLetterHandler`).
///
/// **LOCAL is asserted, not assumed:** a node that hosts the slice but consumes none of the group's
/// partitions answers `409` naming the consuming node. That refusal is what keeps a per-node store
/// coherent — see `TopicRoutes.rebuildGroup`.
///
/// **What a green run does NOT prove:** no owner-loss during the replay (no kill arm); no redrive (spec §9's
/// redrive surface does not exist); a shared `ProjectionStore` (none exists — the in-process store is
/// single-assignee by construction, which `partitions = 1` guarantees here).
///
/// Heavy: deploys a standalone test blueprint by coordinates, so it runs where those are built
/// (`heavy-forge.yml`, `./build.sh`), not in `ci.yml`'s forge-tests job.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DurableProjectionRebuildForgeTest {
    private static final int BASE_PORT = 26500;
    private static final int BASE_MGMT_PORT = 26600;
    private static final int BASE_APP_HTTP_PORT = 26700;
    private static final int NODES = 3;
    private static final int INSTANCES = 3;
    private static final String PROJECTION_SLICE = TestArtifacts.DURABLE_PROJECTION_SLICE;
    private static final String BLUEPRINT_ID = "forge.test:durable-projection:1.0.0";
    /// `BlueprintNamespace.deriveNamespace`: the blueprint's `groupId.artifactId`.
    private static final String TOPIC_NAMESPACE = "forge.test.durable-projection";
    private static final String TOPIC = "projection-events";
    private static final String TOPIC_VERSION = "1.0.0";

    /// `DurableGroupIdentity.groupId`: the slice's version-stable base plus the subscriber method.
    private static final String GROUP = "org.pragmatica.aether.test:test-durable-topic-projection-slice#onProjectionEvent";

    private static final String GROUPS_PATH = "/api/v1/topics/" + TOPIC_NAMESPACE
                                            + "/" + TOPIC
                                            + "/" + TOPIC_VERSION
                                            + "/groups";

    /// The group's `#` is percent-encoded, as `RouteAssembler.encodeSegment` does for the CLI: bare, a
    /// client treats it as a URI fragment and never sends it.
    private static final String REBUILD_PATH = "/api/v1/topics/" + TOPIC_NAMESPACE
                                             + "/" + TOPIC
                                             + "/" + TOPIC_VERSION
                                             + "/rebuild/" + URLEncoder.encode(GROUP, StandardCharsets.UTF_8);

    private static final String ERROR_FALLBACK = "{\"error\":\"request failed\"}";
    private static final int POISON_SEQ = 3;
    private static final int RETRY_BUDGET = 5;
    private static final long MODEL_BEFORE = 123456L;
    private static final long MODEL_AFTER_REBUILD = 12456L;
    private static final long MODEL_AFTER_LIVE_PUBLISH = 124567L;
    private static final Pattern LIVE_CURSOR = Pattern.compile("\"liveCursor\"\\s*:\\s*(\\d+)");
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final Pattern MODEL_FIELD = Pattern.compile("\"model\"\\s*:\\s*(-?\\d+)");
    private static final Pattern LIVE_FIELD = Pattern.compile("\"live\"\\s*:\\s*(true|false)");

    private static final Pattern ATTEMPT_ENTRY = Pattern.compile("\\{\\s*\"seq\"\\s*:\\s*(\\d+)\\s*,\\s*\"attempts\"\\s*:\\s*(\\d+)\\s*}");

    private static final Pattern HELD_HERE_TRUE = Pattern.compile("\"heldHere\"\\s*:\\s*true");
    private static final Pattern REPLAY_STATE = Pattern.compile("\"replayState\"\\s*:\\s*\"(\\w+)\"");
    private static final Pattern COMMITTED_CURSOR = Pattern.compile("\"committedCursor\"\\s*:\\s*(\\d+)");
    private static final Pattern COMMITTED_EPOCH = Pattern.compile("\"committedEpoch\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern GENERATION_FIELD = Pattern.compile("\"generation\"\\s*:\\s*(\\d+)");

    private static final Pattern TOKEN_FIELD = Pattern.compile("\"token\"\\s*:\\s*\\{\\s*\"generation\"\\s*:\\s*(\\d+)\\s*,\\s*\"rewind\"\\s*:\\s*(\\d+)\\s*}");

    private static final Pattern THROUGH_FIELD = Pattern.compile("\"throughOffset\"\\s*:\\s*(\\d+)");

    private EmberCluster cluster;
    private final HttpOperations http = jdkHttpOperations();

    @BeforeAll
    void setUp(@TempDir Path baseDir) {
        var configProvider = ConfigurationProvider.builder()
                                                  .withSystemProperties("aether.")
                                                  .withEnvironment("AETHER_")
                                                  .build();

        cluster = emberCluster(NODES, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "dpr", Option.some(configProvider));
        cluster.withDataBaseDir(baseDir);
        LifecycleAwait.settled("cluster start in setUp()", cluster, cluster.start());
        await().atMost(WAIT_TIMEOUT).pollInterval(POLL_INTERVAL).until(() -> cluster.currentLeader()
                                                                                    .isPresent());
        await().atMost(WAIT_TIMEOUT).pollInterval(POLL_INTERVAL).until(this::allNodesHealthy);
        deployProjectionSlice();
        // The assignee is the partition's HRW owner ONLY once the slice is ACTIVE there; a node still
        // ACTIVATING (measured: one node took ~85s, force-transitioned by the activation timeout) joins the
        // candidate set later and MOVES the consumer mid-test. Wait for the placement to be final.
        await().atMost(WAIT_TIMEOUT)
             .pollInterval(POLL_INTERVAL)
             .failFast(this::failIfSliceFailed)
             .untilAsserted(() -> assertThat(activeInstances()).describedAs("slice ACTIVE on every node before the assignment is read")
                                            .isEqualTo(NODES));
        await().atMost(WAIT_TIMEOUT)
             .pollInterval(POLL_INTERVAL)
             .failFast(this::failIfSliceFailed)
             .until(this::appHttpReady);
        // The warm-up publish (seq 0) doubles as the readiness gate for the backing stream's owner ring;
        // it is offset 0 of the replay range and folds to 0, so the model arithmetic is unchanged by it.
        await().atMost(WAIT_TIMEOUT)
             .pollInterval(POLL_INTERVAL)
             .failFast(this::failIfSliceFailed)
             .until(this::warmupPublishReady);
        await().atMost(WAIT_TIMEOUT)
             .pollInterval(POLL_INTERVAL)
             .failFast(this::failIfSliceFailed)
             .untilAsserted(() -> assertThat(consumerAppPort().isPresent()).describedAs("the group attached and delivered the warm-up on exactly one node")
                                            .isTrue());
    }

    @AfterAll
    void tearDown() {
        if (cluster != null) {
            httpDelete(cluster.getLeaderManagementPort().or(anyMgmtPort()),
                       "/api/v1/blueprints/" + BLUEPRINT_ID);
            LifecycleAwait.bestEffort("cluster stop in tearDown()", cluster, cluster.stop());
        }
    }

    @Test
    void rebuild_replaysInOrder_skipsTheDeadLetteredOffset_andGoesLive_observedThroughTheOperatorSurface() {
        for (var seq = 1; seq <= 6; seq++) {
            publish(seq);
        }

        awaitModel(MODEL_BEFORE);
        var consumerNode = awaitConsumerNode();
        // The committed columns are NOT asserted before the rebuild: checkpoints are requested only from an
        // ack on the 500ms cadence, so a partition whose events all land within 500ms of the attach and then
        // goes quiet has NO committed checkpoint at all (measured: attach at .349, warm-up at .375, seq 1..6 by
        // .764 — none), and one that lags keeps the last cadence-triggered commit (measured: 3 of 7). Only
        // "never rewound" is invariant here. The post-rebuild assertion below IS at the head, because the dead
        // letter's forced checkpoint request schedules the follow-up that lands the tail.
        var groupsBefore = httpGet(consumerNode.mgmtPort(), GROUPS_PATH);

        assertThat(replayState(groupsBefore)).describedAs("the consuming node hosts the projection and reports its state: %s",
                                                          groupsBefore)
                  .isEqualTo("LIVE");
        assertThat(firstString(COMMITTED_EPOCH, groupsBefore)).describedAs("never rewound (absent, or the unrewound epoch): %s",
                                                                           groupsBefore)
                  .isIn("", "0/0");
        // The head is derived, not assumed: the readiness gate's warm-up publish (seq 0, folds to 0) can land
        // more than once when its response is lost, so the partition holds seq 1..6 plus one or more zeros.
        var eventsBeforeRebuild = firstLong(LIVE_CURSOR, httpGet(consumerNode.mgmtPort(), GROUPS_PATH));
        var replayThrough = eventsBeforeRebuild - 1;

        assertThat(eventsBeforeRebuild).describedAs("seq 1..6 plus at least one warm-up").isGreaterThanOrEqualTo(7L);
        var attemptsBefore = attempts(consumerNode.appPort());

        assertThat(attemptsBefore.getOrDefault(POISON_SEQ, 0)).describedAs("control: seq %d delivered once before the rebuild",
                                                                           POISON_SEQ)
                  .isEqualTo(1);
        // LOCAL, asserted: a node that hosts the slice but consumes nothing answers 409 naming the consumer.
        var bystander = cluster.status()
                               .nodes()
                               .stream()
                               .filter(node -> node.mgmtPort() != consumerNode.mgmtPort())
                               .findFirst()
                               .orElseThrow();
        var refused = httpPostWithStatus(bystander.mgmtPort(), REBUILD_PATH, "");

        assertThat(refused.status()).describedAs("rebuild on a non-consuming node is refused: %s",
                                                 refused.body())
                  .isEqualTo(409);
        assertThat(refused.body()).contains(consumerNode.id());
        cluster.getAvailableAppHttpPorts().forEach(port -> armPoison(port, POISON_SEQ));
        var rebuild = httpPostWithStatus(consumerNode.mgmtPort(), REBUILD_PATH, "");

        assertThat(rebuild.status()).describedAs("rebuild accepted on the consuming node: %s",
                                                 rebuild.body())
                  .isEqualTo(200);
        assertThat(firstInt(GENERATION_FIELD, rebuild.body())).describedAs("first rebuild → generation 1").isEqualTo(1);
        assertThat(firstLong(THROUGH_FIELD,
                             rebuild.body())).describedAs("captured head = the last visible offset")
                  .isEqualTo(replayThrough);
        var token = TOKEN_FIELD.matcher(rebuild.body());

        assertThat(token.find()).describedAs("the rewind token is answered: %s", rebuild.body()).isTrue();
        var tokenEpoch = token.group(1) + "/" + token.group(2);

        awaitModel(MODEL_AFTER_REBUILD);
        await().atMost(DELIVERY_TIMEOUT)
             .pollInterval(POLL_INTERVAL)
             .failFast(this::failIfSliceFailed)
             .untilAsserted(() -> assertThat(live(consumerNode.appPort())).describedAs("the store goes LIVE once the committed cursor passes the skipped offset")
                                            .isTrue());
        var deltas = deltas(attemptsBefore, attempts(consumerNode.appPort()));

        assertThat(deltas.get(POISON_SEQ)).describedAs("the poison replay offset exhausted exactly the durable retry budget: %s",
                                                       deltas)
                  .isEqualTo(RETRY_BUDGET);
        assertThat(deltas.entrySet()
                         .stream()
                         .filter(entry -> entry.getKey() != POISON_SEQ && entry.getKey() != 0)
                         .allMatch(entry -> entry.getValue() >= 1 && entry.getValue() <= 2)).describedAs("every other replayed event was admitted first time or after ONE burned Rebuilding retry, never dead-lettered: %s",
                                                                                                         deltas)
                  .isTrue();
        assertThat(deltas.values().stream().filter(delta -> delta >= RETRY_BUDGET).count()).describedAs("exactly ONE dead letter: %s",
                                                                                                        deltas)
                  .isEqualTo(1L);
        await().atMost(DELIVERY_TIMEOUT)
             .pollInterval(POLL_INTERVAL)
             .untilAsserted(() -> {
                                var groups = httpGet(consumerNode.mgmtPort(),
                                                     GROUPS_PATH);

                                assertThat(replayState(groups)).describedAs("groups route: %s", groups)
                                          .isEqualTo("LIVE");
                                assertThat(firstString(COMMITTED_EPOCH, groups)).describedAs("the committed checkpoint carries the rewind's epoch")
                                          .isEqualTo(tokenEpoch);
                                assertThat(firstLong(COMMITTED_CURSOR, groups)).describedAs("committed cursor past the replayed head")
                                          .isEqualTo(eventsBeforeRebuild);
                            });
        cluster.getAvailableAppHttpPorts().forEach(port -> armPoison(port, -1));
        publish(7);
        awaitModel(MODEL_AFTER_LIVE_PUBLISH);
        var afterLive = deltas(attemptsBefore, attempts(consumerNode.appPort()));

        assertThat(afterLive.get(7)).describedAs("a live event after LIVE is admitted first time").isEqualTo(1);
        assertThat(afterLive.values().stream().filter(delta -> delta >= RETRY_BUDGET).count()).describedAs("still exactly ONE dead letter")
                  .isEqualTo(1L);
    }

    // --- observations ------------------------------------------------------------------------
    private record ConsumerNode(String id, int mgmtPort, int appPort) {}

    /// The node whose groups row says `heldHere: true` — the one consuming the group's single partition.
    /// Its app port is derived from the slot (`base + slot` on every port family), and the derivation is
    /// checked against the model: the consuming node is the only one whose store holds one.
    /// STABLE across a reconcile interval, not merely observed once: the assignment is recomputed on the
    /// 5 s tick, and a node that turned ACTIVE just before the ACTIVE gate passed can take the partition on
    /// the NEXT tick (measured: force-transition at 22:33:29, consumer moved at 22:33:32). A rebuild POSTed
    /// to the node that then loses the partition rewinds a consumer whose replays land in the new node's
    /// fresh store — the old store never goes LIVE. Three consecutive reads 2.5 s apart span one tick.
    private ConsumerNode awaitConsumerNode() {
        var holder = new ConsumerNode[1];
        var seen = new java.util.ArrayList<String>();

        await().atMost(WAIT_TIMEOUT)
             .pollInterval(Duration.ofMillis(2_500))
             .untilAsserted(() -> {
                                var found = cluster.status()
                                                   .nodes()
                                                   .stream()
                                                   .filter(node -> HELD_HERE_TRUE.matcher(httpGet(node.mgmtPort(),
                                                                                                  GROUPS_PATH)).find())
                                                   .map(node -> new ConsumerNode(node.id(),
                                                                                 node.mgmtPort(),
                                                                                 BASE_APP_HTTP_PORT + node.mgmtPort() - BASE_MGMT_PORT))
                                                   .toList();

                                assertThat(found).describedAs("exactly one node holds the group's partition")
                                          .hasSize(1);
                                seen.add(found.getFirst().id());
                                assertThat(seen.size() >= 3 && seen.subList(seen.size() - 3, seen.size()).stream().distinct().count() == 1)
                                          .describedAs("the assignment is stable across one reconcile interval: %s", seen)
                                          .isTrue();
                                assertThat(consumerAppPort()).describedAs("the model lives on the node that holds the partition")
                                          .isEqualTo(Option.some(found.getFirst().appPort()));
                                holder[0] = found.getFirst();
                            });

        return holder[0];
    }

    /// The app port whose fixture holds a model — absent until the group attached and folded the warm-up.
    private Option<Integer> consumerAppPort() {
        return cluster.getAvailableAppHttpPorts()
                      .stream()
                      .filter(port -> model(port) >= 0)
                      .findFirst()
                      .map(Option::some)
                      .orElseGet(Option::none);
    }

    private void awaitModel(long expected) {
        await().atMost(DELIVERY_TIMEOUT)
             .pollInterval(POLL_INTERVAL)
             .failFast(this::failIfSliceFailed)
             .untilAsserted(() -> assertThat(cluster.getAvailableAppHttpPorts()
                                                    .stream()
                                                    .map(this::model)
                                                    .filter(model -> model >= 0)
                                                    .toList()).describedAs("the consuming node's model")
                                            .containsExactly(expected));
    }

    private long model(int appPort) {
        return firstLong(MODEL_FIELD, status(appPort), -1L);
    }

    private boolean live(int appPort) {
        var matcher = LIVE_FIELD.matcher(status(appPort));

        return matcher.find() && Boolean.parseBoolean(matcher.group(1));
    }

    private Map<Integer, Integer> attempts(int appPort) {
        var counts = new HashMap<Integer, Integer>();
        var matcher = ATTEMPT_ENTRY.matcher(status(appPort));

        while (matcher.find()) {
            counts.put(Integer.parseInt(matcher.group(1)),
                       Integer.parseInt(matcher.group(2)));
        }

        return counts;
    }

    private static Map<Integer, Integer> deltas(Map<Integer, Integer> before, Map<Integer, Integer> after) {
        var deltas = new HashMap<Integer, Integer>();

        after.forEach((seq, count) -> deltas.put(seq, count - before.getOrDefault(seq, 0)));

        return deltas;
    }

    private static String replayState(String groups) {
        return firstString(REPLAY_STATE, groups);
    }

    private String status(int appPort) {
        return httpPost(appPort, "/api/projection/status", "{}");
    }

    private void publish(int seq) {
        var response = httpPost(anyAppPort(), "/api/projection/publish", "{\"seq\":" + seq + "}");

        assertThat(response).describedAs("durable publish resolves at the min-sync floor").doesNotContain("\"error\"");
    }

    private void armPoison(int appPort, int seq) {
        var response = httpPost(appPort, "/api/projection/arm-poison", "{\"seq\":" + seq + "}");

        assertThat(response).doesNotContain("\"error\"");
    }

    // --- cluster plumbing ----------------------------------------------------------------------
    private void deployProjectionSlice() {
        var blueprint = """
            id = "%s"

            [[slices]]
            artifact = "%s"
            instances = %d
            """.formatted(BLUEPRINT_ID, PROJECTION_SLICE, INSTANCES);
        var response = httpPostToml(cluster.getLeaderManagementPort().or(anyMgmtPort()),
                                    "/api/v1/blueprints",
                                    blueprint);

        assertThat(response).describedAs("projection slice deployment")
                  .doesNotContain("\"error\"")
                  .contains("\"status\":\"applied\"");
    }

    private boolean appHttpReady() {
        var ports = cluster.getAvailableAppHttpPorts();

        return ! ports.isEmpty() && status(ports.getFirst()).contains("generation");
    }

    private boolean warmupPublishReady() {
        var ports = cluster.getAvailableAppHttpPorts();

        if (ports.isEmpty()) {
            return false;
        }

        var response = httpPost(ports.getFirst(), "/api/projection/publish", "{\"seq\":0}");

        return ! response.contains("\"error\"") && response.contains("published");
    }

    private long activeInstances() {
        return cluster.slicesStatus()
                      .stream()
                      .filter(status -> status.artifact()
                                              .equals(PROJECTION_SLICE))
                      .flatMap(status -> status.instances()
                                               .stream())
                      .filter(instance -> instance.state()
                                                  .equals("ACTIVE"))
                      .count();
    }

    private void failIfSliceFailed() {
        var failed = cluster.slicesStatus()
                            .stream()
                            .anyMatch(status -> status.artifact()
                                                      .equals(PROJECTION_SLICE) && status.state()
                                                                                         .equals("FAILED"));

        if (failed) {
            throw new AssertionError("Projection slice deployment FAILED: " + PROJECTION_SLICE);
        }
    }

    private int anyAppPort() {
        return cluster.getAvailableAppHttpPorts()
                      .stream()
                      .findFirst()
                      .orElseThrow(() -> new AssertionError("No app-http route is ready"));
    }

    private int anyMgmtPort() {
        return cluster.status()
                      .nodes()
                      .getFirst()
                      .mgmtPort();
    }

    private boolean allNodesHealthy() {
        return cluster.status()
                      .nodes()
                      .stream()
                      .allMatch(node -> checkNodeHealth(node.mgmtPort()));
    }

    private boolean checkNodeHealth(int port) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/v1/health"))
                                 .GET()
                                 .timeout(Duration.ofSeconds(5))
                                 .build();

        return http.sendString(request)
                   .await()
                   .map(response -> response.statusCode() == 200 && response.body()
                                                                            .contains("\"quorum\":true"))
                   .or(false);
    }

    private static int firstInt(Pattern pattern, String body) {
        return (int) firstLong(pattern, body, 0L);
    }

    private static long firstLong(Pattern pattern, String body) {
        return firstLong(pattern, body, 0L);
    }

    private static long firstLong(Pattern pattern, String body, long absent) {
        Matcher matcher = pattern.matcher(body);

        return matcher.find()
               ? Long.parseLong(matcher.group(1))
               : absent;
    }

    private static String firstString(Pattern pattern, String body) {
        Matcher matcher = pattern.matcher(body);

        return matcher.find()
               ? matcher.group(1)
               : "";
    }

    // --- HTTP ------------------------------------------------------------------------------------
    private record Response(int status, String body) {}

    private String httpGet(int port, String path) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .GET()
                                 .timeout(Duration.ofSeconds(15))
                                 .build();

        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }

    private Response httpPostWithStatus(int port, String path, String body) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .header("Content-Type", "application/json")
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .timeout(Duration.ofSeconds(30))
                                 .build();

        return http.sendString(request)
                   .await()
                   .map(result -> new Response(result.statusCode(),
                                               result.body()))
                   .or(new Response(-1, ERROR_FALLBACK));
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
        return httpPostWithStatus(port, path, body).body();
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
