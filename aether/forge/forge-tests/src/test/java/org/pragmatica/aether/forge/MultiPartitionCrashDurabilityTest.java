// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.stream.StreamReadRouter.ReplicaSetView;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.lang.Option;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;

import org.pragmatica.aether.ember.EmberCluster;

/// Streaming-persistence — MULTI-PARTITION full-cluster restart crash-durability proof for the
/// per-partition WAL. The single-partition sibling [StreamCrashDurabilityTest] proves ONE partition's
/// WAL replays across a restart; this proves the FOUR partitions of a partitions=4 / RF=2 / min-sync-2
/// stream each replay INDEPENDENTLY — a bug could lose one partition's WAL while the others recover, and
/// only a per-partition assertion catches that.
///
/// Fixture: the `test-stream-multipart` blueprint (`streams.multipart-events`, partitions=4, replicas=2,
/// min-sync-replicas=2, count retention 100000 — nothing is ever sealed, so recovery is pure WAL replay).
/// Keyless app publishes round-robin across the four partitions
/// (`DefaultStreamPublisher#resolvePartition`), so driving every publish through ONE stable app port
/// spreads events over all four; each publish AWAITs a replica ack (min-sync-2), so an acked write is
/// durable on >=2 nodes' WALs before the caller is told "published".
///
/// The cluster is built with a writable, restart-stable per-node data dir
/// (`EmberCluster.withDataBaseDir(@TempDir)`) so each node's disk tier and per-partition WALs
/// (`<baseDir>/<nodeId>/stream-segments/<nodeId>/wal/multipart-events/{0,1,2,3}.wal`) are live. Forge's
/// Rabia persistence is in-memory, so the restart wipes the KV; the test re-deploys the same blueprint,
/// each partition's HRW owner re-materializes and replays its WAL tail before serving reads.
///
/// Flow (exactly the reliable [StreamCrashDurabilityTest] arm-1 pattern, ONE restart = 2nd formation,
/// inside the reliability envelope — a SECOND in-JVM restart trips the leader reconciler's deficit-fill,
/// see that class's #431 handover): gate on all 4 partitions PLACED (owner + in-sync replica) → publish N
/// keyless (round-robin over the 4 partitions) → drain every partition and record its per-partition log →
/// full-cluster graceful restart → drain every partition again and assert each recovered EXACTLY its
/// pre-restart log (independent per-partition WAL replay), offsets contiguous and publish order preserved.
///
/// Precise guarantee: every fsync-acked event on EVERY partition survives a full-cluster restart via
/// independent per-partition WAL replay.
///
/// HONESTY NOTE (same in-JVM scope as the single-partition sibling): the restart is GRACEFUL. A no-hooks
/// process kill (the crash-mid-fsync boundary, where the caller is never acked) is NOT reachable in the
/// in-JVM Ember harness — every stop routes through `AetherNode.stop()`, which runs
/// `streamPartitionManager.close()` SYNCHRONOUSLY before its async tail, and `killNode(graceful=false)`
/// only shortens the async-teardown timeout — and adds no durability (close() fsyncs only bytes ALREADY
/// appended). Durability is purely fsync-before-ack, which a graceful restart proves just as a hard one
/// would (the hot ring is lost either way). True SIGKILL crash durability lives at the cloud docker-kill
/// tier.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MultiPartitionCrashDurabilityTest {
    private static final System.Logger LOG = System.getLogger(MultiPartitionCrashDurabilityTest.class.getName());
    private static final int BASE_PORT = 17500;
    private static final int BASE_MGMT_PORT = 17600;
    private static final int BASE_APP_HTTP_PORT = 17700;
    private static final int NODES = 5;
    private static final int INSTANCES = 5;
    private static final int PARTITIONS = 4;
    private static final int EVENT_COUNT = 40;

    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final Duration PLACEMENT_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration RECOVERY_TIMEOUT = Duration.ofSeconds(240);
    private static final long POLL_GAP_NANOS = Duration.ofMillis(20).toNanos();

    private static final String STREAM_SLICE = TestArtifacts.STREAM_MULTIPART_SLICE;
    private static final String STREAM_NAME = "multipart-events";
    private static final String BLUEPRINT_ID = "forge.test:multipart-crash-durability:1.0.0";
    private static final String ERROR_FALLBACK = "{\"error\":\"request failed\"}";

    private static final Pattern EVENT_OBJECT = Pattern.compile("\\{[^{}]*\"offset\"[^{}]*}");
    private static final Pattern OFFSET_FIELD = Pattern.compile("\"offset\"\\s*:\\s*(\\d+)");
    private static final Pattern PAYLOAD_FIELD = Pattern.compile("\"payload\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern NODE_COUNT_FIELD = Pattern.compile("\"nodeCount\"\\s*:\\s*(\\d+)");

    Path baseDir;

    private EmberCluster cluster;
    private final HttpOperations http = jdkHttpOperations();

    /// One stream event as observed by a consumer over HTTP: physical `offset` within its partition plus
    /// the monotonic `seq` the test embedded in the payload at publish time.
    private record Event(long offset, long seq) {}

    @BeforeAll
    void setUp(@TempDir Path tempDir) {
        this.baseDir = tempDir;

        // A ConfigurationProvider must be present for the node to enable resource provisioning
        // (StreamPublisher / StreamAccess); without it AetherNode installs a no-op facade that fails
        // every resource-backed slice (mirrors StreamFanoutConsumerTest / AbstractMultiPartitionStream).
        var configProvider = ConfigurationProvider.builder()
                                                  .withSystemProperties("aether.")
                                                  .withEnvironment("AETHER_")
                                                  .build();
        cluster = emberCluster(NODES, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "mpcd", Option.some(configProvider));
        // Opt in to a writable, restart-stable per-node data dir -> disk tier + per-partition WALs ON.
        cluster.withDataBaseDir(baseDir);

        startAndAwaitReady();
    }

    @AfterAll
    void tearDown() {
        if (cluster != null) {
            var leaderPort = cluster.getLeaderManagementPort().or(anyMgmtPort());
            httpDelete(leaderPort, "/api/blueprints/" + BLUEPRINT_ID);
            cluster.stop()
                   .await();
        }
    }

    /// THE gate: publish N acked events spread over all 4 partitions (min-sync-2, so each is durable on
    /// >=2 WALs), fully restart the cluster preserving the per-node data dirs, then prove EVERY partition's
    /// WAL replays independently — each partition recovers exactly its pre-restart log, contiguous and in
    /// publish order.
    @Test
    void multiPartitionRestart_recoversEveryPartition_viaWalReplay() throws IOException {
        var port = appPort();

        // All 4 partitions must have owner + in-sync replica PLACED before the min-sync-2 publishes, else a
        // publish to a not-yet-replicated partition cannot ack; placement also confirms HRW spread owners
        // across the cluster so the keyless round-robin populates every partition.
        await().atMost(PLACEMENT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(this::allPartitionsPlaced);

        publishBatch(port, EVENT_COUNT);

        var preByPartition = drainAllPartitions(port);
        assertEveryPartitionPopulated(preByPartition);
        assertAllSeqsPresent(preByPartition, EVENT_COUNT);
        assertPerPartitionOrdered(preByPartition);

        // A NON-EMPTY 'multipart-events' WAL must exist on disk, proving the owners appended + fsync'd the
        // acked events before ack — the only medium that survives the restart (nothing is ever sealed).
        assertWalActiveForMultipart();

        restartCluster();

        var recoveredPort = appPort();
        var postByPartition = drainAllPartitionsUntil(recoveredPort, preByPartition);

        dumpIfPartitionsShort(postByPartition, preByPartition);
        assertEachPartitionRecovered(postByPartition, preByPartition);
        assertAllSeqsPresent(postByPartition, EVENT_COUNT);
        assertPerPartitionOrdered(postByPartition);
    }

    // --- restart ------------------------------------------------------------

    private void restartCluster() {
        cluster.stop()
               .await()
               .onFailure(cause -> {
                   throw new AssertionError("Cluster stop failed: " + cause.message());
               });

        startAndAwaitReady();
    }

    private void startAndAwaitReady() {
        cluster.start()
               .await()
               .onFailure(cause -> {
                   throw new AssertionError("Cluster start failed: " + cause.message());
               });

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.currentLeader().isPresent());

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(this::allNodesHealthy);

        // Gate on FULL membership before publish (pre-restart) or read (post-restart): after a full-cluster
        // restart the cold-boot convergence window must let ALL NODES re-form so every partition's HRW owner
        // is a rejoined node holding its WAL, not an empty-WAL replacement picked because a straggler was
        // prematurely evicted.
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> allNodesAreMembers(NODES));

        // Re-deploy: Forge KV is in-memory, so a full-cluster restart wiped the stream config. This re-puts
        // the deterministic `multipart-events` config -> each partition's owner re-materializes and its
        // partition build opens-or-recovers the WAL and replays the un-sealed tail. Carries NO events.
        deployStreamSlice();

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(this::failIfSliceFailed)
               .until(this::appHttpReady);
    }

    // --- placement (in-JVM, owner-authoritative) ---------------------------

    /// The owner-authoritative replica-set view for `(STREAM_NAME, partition)`: the registry is
    /// authoritative only on the partition's HRW owner (`servedByOwner()` true), so scan every live node's
    /// in-JVM `replicaSnapshot` and return the owner's (the HTTP sensor is delegate-routed, #490).
    private Option<ReplicaSetView> ownerView(int partition) {
        for (var node : cluster.allNodes()) {
            var view = node.streamReadRouter().replicaSnapshot(STREAM_NAME, partition);

            if (view.servedByOwner()) {
                return Option.some(view);
            }
        }

        return Option.none();
    }

    private boolean allPartitionsPlaced() {
        for (int partition = 0; partition < PARTITIONS; partition++) {
            if (!partitionPlaced(partition)) {
                return false;
            }
        }

        return true;
    }

    private boolean partitionPlaced(int partition) {
        return ownerView(partition).map(view -> view.replicas().size() >= 2
                                                 && view.replicas().stream().anyMatch(r -> !r.hrwOwner()))
                                   .or(false);
    }

    // --- WAL on-disk assertion ---------------------------------------------

    private void assertWalActiveForMultipart() throws IOException {
        var multipartWals = walFiles(baseDir).stream()
                                             .filter(MultiPartitionCrashDurabilityTest::isMultipartWal)
                                             .toList();

        assertThat(multipartWals)
            .describedAs("stream WAL must be ACTIVE: a 'multipart-events' WAL file must exist under %s "
                         + "(WAL OFF -> none; acked events would not survive restart)", baseDir)
            .isNotEmpty();

        long totalBytes = 0;

        for (var wal : multipartWals) {
            totalBytes += Files.size(wal);
        }

        assertThat(totalBytes)
            .describedAs("the owners' 'multipart-events' WALs must hold the acked events (non-empty) — proof "
                         + "they were appended + fsync'd before ack, not just that the WAL dir was writable")
            .isPositive();
    }

    private static boolean isMultipartWal(Path walFile) {
        var parent = walFile.getParent();

        return parent != null && parent.getFileName().toString().equals("multipart-events");
    }

    private static List<Path> walFiles(Path base) throws IOException {
        try (var paths = Files.walk(base)) {
            return paths.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".wal"))
                        .toList();
        }
    }

    // --- assertions ---------------------------------------------------------

    private void assertEveryPartitionPopulated(List<List<Event>> byPartition) {
        for (int partition = 0; partition < PARTITIONS; partition++) {
            assertThat(byPartition.get(partition))
                .describedAs("partition %d must be populated (keyless round-robin spreads over all %d partitions)",
                             partition, PARTITIONS)
                .isNotEmpty();
        }
    }

    /// The union of every partition's seqs is exactly {0..count-1} — no acked event lost, none duplicated,
    /// none stranded on the wrong partition.
    private void assertAllSeqsPresent(List<List<Event>> byPartition, int count) {
        var seqs = byPartition.stream()
                              .flatMap(List::stream)
                              .map(Event::seq)
                              .sorted()
                              .toList();

        assertThat(seqs)
            .describedAs("every published seq 0..%d must be present across the partitions exactly once", count - 1)
            .containsExactlyElementsOf(contiguousSeqs(count));
    }

    private static List<Long> contiguousSeqs(int count) {
        var expected = new ArrayList<Long>();

        for (long seq = 0; seq < count; seq++) {
            expected.add(seq);
        }

        return expected;
    }

    /// Per partition, offsets are contiguous from 0 (no dup, no gap) and embedded seqs strictly increase
    /// (the per-partition publish order is preserved). No warm-up publish is done, so offset 0 is the
    /// partition's first real event.
    private void assertPerPartitionOrdered(List<List<Event>> byPartition) {
        for (int partition = 0; partition < PARTITIONS; partition++) {
            var events = byPartition.get(partition);

            for (int i = 0; i < events.size(); i++) {
                assertThat(events.get(i).offset())
                    .describedAs("partition %d offset at index %d is contiguous from 0 (no dup/gap)", partition, i)
                    .isEqualTo((long) i);
            }

            for (int i = 1; i < events.size(); i++) {
                assertThat(events.get(i).seq())
                    .describedAs("partition %d seq strictly increases with offset (publish order preserved)", partition)
                    .isGreaterThan(events.get(i - 1).seq());
            }
        }
    }

    /// Each partition recovered EXACTLY its pre-restart log — same seqs, same order — proving the four
    /// per-partition WALs replayed independently (no partition lost while others recovered).
    private void assertEachPartitionRecovered(List<List<Event>> post, List<List<Event>> pre) {
        for (int partition = 0; partition < PARTITIONS; partition++) {
            var preSeqs = pre.get(partition).stream().map(Event::seq).toList();
            var postSeqs = post.get(partition).stream().map(Event::seq).toList();

            assertThat(postSeqs)
                .describedAs("partition %d: every event durably readable before the restart must be readable "
                             + "after, in order (independent per-partition WAL replay)", partition)
                .containsExactlyElementsOf(preSeqs);
        }
    }

    // --- failure-path observability ----------------------------------------

    private void dumpIfPartitionsShort(List<List<Event>> post, List<List<Event>> pre) {
        for (int partition = 0; partition < PARTITIONS; partition++) {
            if (post.get(partition).size() < pre.get(partition).size()) {
                dumpAllNodeStreamState();

                return;
            }
        }
    }

    /// On a recovery shortfall, dump EVERY live node's in-JVM replica snapshot for ALL 4 partitions
    /// (mirrors `AbstractStreamOwnerFailover.dumpAllReplicaViews`): one WARNING line per (node, partition)
    /// tagged with the reporting node's own id, so a non-recovered partition view is diagnosable from the
    /// shared forge console instead of the assertion dying blind.
    private void dumpAllNodeStreamState() {
        for (var node : cluster.allNodes()) {
            for (int partition = 0; partition < PARTITIONS; partition++) {
                LOG.log(System.Logger.Level.WARNING,
                        "multipart-crash FAIL view self={0} partition={1}: {2}",
                        node.self(),
                        partition,
                        node.streamReadRouter().replicaSnapshot(STREAM_NAME, partition));
            }
        }
    }

    // --- consumers ----------------------------------------------------------

    /// Drain every partition (0..PARTITIONS-1) fully; index i = partition i's events in offset order.
    private List<List<Event>> drainAllPartitions(int port) {
        var byPartition = new ArrayList<List<Event>>();

        for (int partition = 0; partition < PARTITIONS; partition++) {
            byPartition.add(drainPartition(port, partition));
        }

        return byPartition;
    }

    /// Post-restart variant: each partition's owner must re-materialize and self-promote before it serves,
    /// so drain each partition with a generous per-partition deadline until it reaches its pre-restart
    /// count (returns as soon as it does; on a real loss it returns short and the assertion fails + dumps).
    private List<List<Event>> drainAllPartitionsUntil(int port, List<List<Event>> pre) {
        var byPartition = new ArrayList<List<Event>>();

        for (int partition = 0; partition < PARTITIONS; partition++) {
            byPartition.add(drainPartitionUntil(port, partition, pre.get(partition).size(), recoveryDeadlineNanos()));
        }

        return byPartition;
    }

    private List<Event> drainPartitionUntil(int port, int partition, int expectedCount, long deadlineNanos) {
        var collected = new ArrayList<Event>();
        var offset = 0L;

        while (collected.size() < expectedCount && System.nanoTime() < deadlineNanos) {
            var events = readPartition(port, partition, offset, 200);

            if (events.isEmpty()) {
                LockSupport.parkNanos(POLL_GAP_NANOS);
                continue;
            }

            collected.addAll(events);
            offset = events.getLast().offset() + 1;
        }

        return List.copyOf(collected);
    }

    private List<Event> drainPartition(int port, int partition) {
        var all = new ArrayList<Event>();
        var offset = 0L;

        while (true) {
            var events = readPartition(port, partition, offset, 200);

            if (events.isEmpty()) {
                return List.copyOf(all);
            }

            all.addAll(events);
            offset = events.getLast().offset() + 1;
        }
    }

    private List<Event> readPartition(int port, int partition, long fromOffset, int maxEvents) {
        var body = "{\"partition\":" + partition + ",\"fromOffset\":" + fromOffset + ",\"maxEvents\":" + maxEvents + "}";

        return parseEvents(httpPost(port, "/api/stream-mp/read", body));
    }

    private static List<Event> parseEvents(String body) {
        var events = new ArrayList<Event>();
        Matcher objects = EVENT_OBJECT.matcher(body);

        while (objects.find()) {
            var object = objects.group();
            Matcher offset = OFFSET_FIELD.matcher(object);
            Matcher payload = PAYLOAD_FIELD.matcher(object);

            if (offset.find() && payload.find()) {
                addEvent(events, offset.group(1), payload.group(1));
            }
        }

        return List.copyOf(events);
    }

    /// Keep only payloads that are a decimal `seq` (defensive: skips any non-numeric marker) so nothing
    /// pollutes the seq accounting.
    private static void addEvent(List<Event> events, String offset, String payload) {
        if (!payload.isEmpty() && payload.chars().allMatch(Character::isDigit)) {
            events.add(new Event(Long.parseLong(offset), Long.parseLong(payload)));
        }
    }

    // --- publishing ---------------------------------------------------------

    private void publishBatch(int port, int count) {
        for (int seq = 0; seq < count; seq++) {
            publish(port, seq);
        }
    }

    private void publish(int port, long seq) {
        var body = "{\"payload\":\"" + seq + "\"}";
        var response = httpPost(port, "/api/stream-mp/publish", body);

        assertThat(response)
            .describedAs("publish seq %d must ack (min-sync-2: durable on >=2 nodes' WALs before ack)", seq)
            .doesNotContain("\"error\"")
            .contains("published");
    }

    // --- deployment + readiness --------------------------------------------

    private void deployStreamSlice() {
        var blueprint = """
            id = "%s"

            [[slices]]
            artifact = "%s"
            instances = %d
            """.formatted(BLUEPRINT_ID, STREAM_SLICE, INSTANCES);
        var leaderPort = cluster.getLeaderManagementPort().or(anyMgmtPort());
        var response = postBlueprintWithRetry(leaderPort, blueprint);

        assertThat(response)
            .describedAs("multi-partition (partitions=4, RF=2) stream-slice deployment")
            .doesNotContain("\"error\"")
            .contains("\"status\":\"applied\"");
    }

    private boolean appHttpReady() {
        var ports = cluster.getAvailableAppHttpPorts();

        if (ports.isEmpty()) {
            return false;
        }

        var body = httpPost(ports.getFirst(), "/api/stream-mp/read", "{\"partition\":0,\"fromOffset\":0,\"maxEvents\":1}");

        return !body.contains("\"error\"") && body.contains("events");
    }

    private void failIfSliceFailed() {
        var failed = cluster.slicesStatus()
                            .stream()
                            .anyMatch(s -> s.artifact().equals(STREAM_SLICE) && s.state().equals("FAILED"));

        if (failed) {
            throw new AssertionError("multi-partition stream slice deployment FAILED: " + STREAM_SLICE);
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

    private long recoveryDeadlineNanos() {
        return System.nanoTime() + RECOVERY_TIMEOUT.toNanos();
    }

    private boolean allNodesHealthy() {
        return cluster.status()
                      .nodes()
                      .stream()
                      .allMatch(node -> checkNodeHealth(node.mgmtPort()));
    }

    private boolean checkNodeHealth(int port) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/health"))
                                 .GET()
                                 .timeout(Duration.ofSeconds(5))
                                 .build();
        return http.sendString(request)
                   .await()
                   .map(r -> r.statusCode() == 200 && r.body().contains("\"quorum\":true"))
                   .or(false);
    }

    private boolean allNodesAreMembers(int expected) {
        var leaderPort = cluster.getLeaderManagementPort().or(anyMgmtPort());
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + leaderPort + "/api/health"))
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
            lastResponse = httpPostToml(port, "/api/blueprints", body);

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
