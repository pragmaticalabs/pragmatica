// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardResponse;
import org.pragmatica.aether.http.forward.HttpForwardMessage.Pipeline;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.NodeCodecs;
import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.forward.StreamForwardMessage;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.quic.QuicClusterNetwork;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Lazy;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.storage.AppendLog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #2574 end to end: the first publish to a new `confirmation_factor` 2 stream is acknowledged even when every replica's FIRST
/// catch-up read for it is refused by the transport, as a backpressured FORWARD lane refuses it (CI logged exactly this
/// refusal from the `stream-partition-backfill` thread within 30 ms of the stream's creation in every failing
/// `EmberOrdinaryFailoverNoFalseAlertTest` job). The refusal is injected with the test-only outbound refusal filter of the
/// transport ([QuicClusterNetwork#setOutboundRefusalFilter], on the QUIC transport only); it fires once per node, on that node's
/// first catch-up `ReadForward` for the stream.
///
/// Before #2574 the refused read was invisible: the read waited out its timeout (2 s), the offset-0 gap trigger joined that
/// dead flight and pulled nothing, and the owner's wait for every in-sync replica outlived the entry node's forward hop, so the
/// client got 503 "outcome unknown" (the spike's probe d74P1, 5 / 5 red). Now the refused read fails at once (F1), its
/// flight holds the partition's slot for the first refusal hold (~250 ms) and the joined trigger re-runs it (F2), so the
/// replica pulls offset 0 and acknowledges it inside the publish's budget.
@PortBudget
class EmberFirstPublishRefusedCatchupTest {
    private static final int CLUSTER_SIZE = 3;
    private static final int SLOTS = 2 * CLUSTER_SIZE;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_HTTP_OFFSET = 80;

    private static final EmberTestPorts.Block PORTS = new EmberTestPorts.Block(EmberTestPorts.POOL_FIRST,
                                                                               EmberTestPorts.POOL_LAST,
                                                                               EmberTestPorts.POOL_STEP,
                                                                               SLOTS,
                                                                               MGMT_OFFSET,
                                                                               APP_HTTP_OFFSET);

    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(180).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final long LEADER_BUDGET_MS = 90_000L;
    private static final long ISR_BUDGET_MS = 60_000L;
    private static final int REFUSAL_RETRY_ATTEMPTS = 20;
    private static final long REFUSAL_RETRY_BUDGET_MS = 20_000L;
    private static final long REFUSAL_RETRY_PAUSE_MS = 500L;
    private static final String NAMESPACE = "ember";
    private static final String NAME = "refusedcatchup0";
    private static final String VERSION = "1.0.0";
    private static final String ENGINE_KEY = NAMESPACE + ":" + NAME + ":" + VERSION;
    private static final SliceCodec CODEC = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
    private static final Pattern REQUEST_ID = Pattern.compile("\"requestId\":\"([^\"]+)\"");

    /// Every management forward reply any node sent, by request id: what the governor answered, whether or not the
    /// transport then wrote it (the outbound filter runs before the lane's writability check).
    private final Map<String, HttpForwardResponse> managementReplies = new ConcurrentHashMap<>();

    @TempDir
    Path dataDir;

    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            assertThat(cluster.stop().await(STOP_BOUND).fold(Cause::message, _ -> "stopped")).isEqualTo("stopped");
        }
    }

    @Test
    @Timeout(600)
    void firstPublish_toANewCf2Stream_isAcknowledged_whenEveryReplicasFirstCatchupReadIsRefused() {
        cluster = EmberTestPorts.startedCluster(PORTS, this::threeNodes, START_BOUND);
        awaitLeader();
        var refusedBy = ConcurrentHashMap.<String>newKeySet();

        cluster.allNodes().forEach(node -> refuseFirstCatchupRead(node, refusedBy, managementReplies));

        assertThat(post(leaderPort(), "/api/v1/streams/" + NAMESPACE + "/" + NAME + "/" + VERSION, "{\"partitions\":1}"))
            .as("create %s", NAME)
            .startsWith("2");
        var owner = awaitFullIsr().owner().id();
        var governor = streamingGovernor();
        var startedAt = System.nanoTime();
        var response = publishRetryingRefusal(governor, "first-0");
        var elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

        System.out.printf("REFUSED-CATCHUP stream=%s owner=%s governor=%s entry=%s refusedBy=%s firstPublishMs=%d -> %s%n",
                          ENGINE_KEY,
                          owner,
                          governor,
                          entryNode(governor).id(),
                          refusedBy,
                          elapsedMs,
                          response.lines().findFirst().orElse(""));
        // Every placed node starts a catch-up at the config Put, before the ownership record exists, so the eventual owner
        // may be refused too; what matters is that a replica that must acknowledge the publish was.
        assertThat(refusedBy.stream().filter(node -> !node.equals(owner)).toList())
            .as("the fault fired on a replica: its first catch-up read was refused")
            .isNotEmpty();
        assertThat(governorVerdict(response)).as("the first publish is acknowledged, not 503 outcome unknown").startsWith("2");
        assertThat(isOutcomeUnknown(response) || managementReplies.values().stream().map(EmberFirstPublishRefusedCatchupTest::decoded).anyMatch(response::equals))
            .as("positive control: the reply observer captured and decoded the governor's reply the client received (%s)", response)
            .isTrue();

        for (var i = 1; i < 5; i++) {
            assertThat(governorVerdict(publishRetryingRefusal(governor, "first-" + i))).as("publish first-%d", i).startsWith("2");
        }
    }

    /// #2952 (CI run 42564693417): the owner's and a replica's KV dispatchers were held ~670 ms by a WAL-root walk (the startup
    /// snapshot of the stream lives, taken while another stream's life was adopted), so the owner had not applied this stream's
    /// config when the first publish was forwarded to it. The owner then took the publish auto-create path and parked its
    /// forward-handling thread in the 2 s config-commit wait; with CI load on top, the entry's 2.5 s forward hop expired first:
    /// 503 "outcome unknown". Here every node except the leader walks its WAL root [#SLOW_WALK_MS] slowly on its next adoption
    /// (the decoy's, created just before this stream, as in CI). Since #2952 the walk runs off the dispatcher, the config applies
    /// on time, and the first publish is answered well inside [#FIRST_PUBLISH_BOUND_MS]; before, it took ~2.2 s at any walk length.
    @Test
    @Timeout(600)
    void firstPublish_toANewCf2Stream_isAnsweredPromptly_whenTheNonLeadersWalkTheWalRootSlowly() {
        cluster = EmberTestPorts.startedCluster(PORTS, this::threeNodes, START_BOUND);
        awaitLeader();
        var leader = cluster.status().leaderId();
        var refusedBy = ConcurrentHashMap.<String>newKeySet();

        cluster.allNodes().forEach(node -> refuseFirstCatchupRead(node, refusedBy, managementReplies));
        cluster.allNodes().stream().filter(node -> !node.self().id().equals(leader)).forEach(EmberFirstPublishRefusedCatchupTest::slowWalRootWalk);

        assertThat(post(leaderPort(), "/api/v1/streams/" + NAMESPACE + "/decoy0/" + VERSION, "{\"partitions\":1}"))
            .as("create decoy0, whose adoption runs the slow walk")
            .startsWith("2");
        assertThat(post(leaderPort(), "/api/v1/streams/" + NAMESPACE + "/" + NAME + "/" + VERSION, "{\"partitions\":1}"))
            .as("create %s", NAME)
            .startsWith("2");
        var owner = awaitFullIsr().owner().id();
        var governor = streamingGovernor();
        var startedAt = System.nanoTime();
        var response = publishRetryingRefusal(governor, "first-0");
        var elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

        System.out.printf("SLOW-WALK stream=%s leader=%s owner=%s governor=%s entry=%s refusedBy=%s slowWalks=%d firstPublishMs=%d -> %s%n",
                          ENGINE_KEY, leader, owner, governor, entryNode(governor).id(), refusedBy, SLOW_WALKS.get(), elapsedMs,
                          response.lines().findFirst().orElse(""));
        assertThat(SLOW_WALKS.get()).as("control: the non-leaders walked their WAL roots slowly").isGreaterThanOrEqualTo(2);
        assertThat(governorVerdict(response)).as("the first publish is acknowledged, not 503 outcome unknown").startsWith("2");
        assertThat(elapsedMs).as("the first publish is answered without waiting out the owner's 2 s config-commit wait").isLessThan(FIRST_PUBLISH_BOUND_MS);
    }

    private static final long SLOW_WALK_MS = 700L;
    private static final long FIRST_PUBLISH_BOUND_MS = 1_500L;
    private static final AtomicInteger SLOW_WALKS = new AtomicInteger();

    /// Swaps the node's WAL opener for one whose listing takes [#SLOW_WALK_MS], and resets the startup snapshot so the next
    /// adoption takes it again, through the same `scanWalDirectories` production runs.
    @SuppressWarnings({"JBCT-EX-01", "unchecked"})
    private static void slowWalRootWalk(AetherNode node) {
        try {
            var manager = node.streamPartitionManager();
            var logsField = StreamPartitionManager.class.getDeclaredField("logs");
            var snapshotField = StreamPartitionManager.class.getDeclaredField("walDirectoriesAtStart");
            var scan = StreamPartitionManager.class.getDeclaredMethod("scanWalDirectories");

            logsField.setAccessible(true);
            snapshotField.setAccessible(true);
            scan.setAccessible(true);
            var logs = (Option<AppendLog.Opener>) logsField.get(manager);

            assertThat(logs.isPresent()).as("fixture: the node has a stream WAL").isTrue();
            logsField.set(manager, logs.map(EmberFirstPublishRefusedCatchupTest::slowListing));
            snapshotField.set(manager, Lazy.lazy(() -> invokeScan(scan, manager)));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("StreamPartitionManager seam moved: " + e, e);
        }
    }

    private static AppendLog.Opener slowListing(AppendLog.Opener opener) {
        return AppendLog.Opener.opener(opener::open, opener::inspect, () -> listSlowly(opener));
    }

    private static Result<List<String>> listSlowly(AppendLog.Opener opener) {
        SLOW_WALKS.incrementAndGet();
        sleepQuietly(SLOW_WALK_MS);

        return opener.listLogs();
    }

    @SuppressWarnings({"JBCT-EX-01", "unchecked"})
    private static List<String> invokeScan(Method scan, StreamPartitionManager manager) {
        try {
            return (List<String>) scan.invoke(manager);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /// #2847: the publish's verdict as the governor gave it. Until #2583 P2 (reply re-send), the governor's management
    /// forward reply rides the FORWARD lane's fast-fail and its send outcome is ignored
    /// (`ManagementServer#sendManagementForwardPayload`), so a reply refused in a backpressure episode on the governor's
    /// lane to the entry is dropped. The entry then rightly answers 503 "outcome unknown" (it cannot tell), for a publish
    /// the governor acknowledged: CI run 42415039453 refused the reply in a 19 ms episode, 128 ms into the publish. What this
    /// test pins is the governor's answer, so on that 503 the verdict is the reply the governor sent for the same request
    /// id. A governor that never answered (the #2574 failure: the owner's ISR wait outlives the hop) or answered anything
    /// but 2xx still fails the assertion.
    private String governorVerdict(String response) {
        if (!isOutcomeUnknown(response)) {
            return response;
        }

        var requestId = REQUEST_ID.matcher(response);
        var reply = requestId.find()
                    ? Option.option(managementReplies.get(requestId.group(1)))
                    : Option.<HttpForwardResponse>none();

        return reply.map(EmberFirstPublishRefusedCatchupTest::decoded)
                    .map(verdict -> {
                             System.out.printf("REPLY-LEG-LOST client=%s governor=%s%n",
                                               response.lines().findFirst().orElse(""),
                                               verdict.lines().findFirst().orElse(""));

                             return verdict;
                         })
                    .or(response);
    }

    private static boolean isOutcomeUnknown(String response) {
        return response.startsWith("503") && response.contains("outcome unknown");
    }

    /// A reply in the client's `status body` shape; a forward-level failure reply carries no HTTP status and reads `-2`.
    private static String decoded(HttpForwardResponse reply) {
        if (!reply.success()) {
            return "-2 " + new String(reply.payload(), StandardCharsets.UTF_8);
        }

        HttpResponseData data = CODEC.decode(reply.payload());

        return data.statusCode() + " " + new String(data.body(), StandardCharsets.UTF_8);
    }

    /// Refuses `node`'s FIRST catch-up read of the test stream, once, on whichever target it is sent to, and records every
    /// management forward reply `node` sends (never refused).
    private static void refuseFirstCatchupRead(AetherNode node, Set<String> refusedBy, Map<String, HttpForwardResponse> replies) {
        var fired = new AtomicBoolean();
        var self = node.self().id();

        ((QuicClusterNetwork) StreamTripwireSupport.component(node, "clusterNode", RabiaNode.class)
                                                   .network()).setOutboundRefusalFilter((_, message) -> recordManagementReply(message, replies)
                                                                       && isTestCatchupRead(message)
                                                                       && fired.compareAndSet(false, true)
                                                                       && refusedBy.add(self));
    }

    /// Always true, so it never decides a refusal.
    private static boolean recordManagementReply(Object message, Map<String, HttpForwardResponse> replies) {
        if (message instanceof HttpForwardResponse reply && reply.pipeline() == Pipeline.MANAGEMENT) {
            replies.put(reply.requestId(), reply);
        }

        return true;
    }

    private static boolean isTestCatchupRead(Object message) {
        return message instanceof StreamForwardMessage.ReadForward read && read.catchup() && read.streamName().equals(ENGINE_KEY);
    }

    private StreamPartitionOwnershipValue awaitFullIsr() {
        var deadline = System.currentTimeMillis() + ISR_BUDGET_MS;

        while (System.currentTimeMillis() < deadline) {
            var record = committedRecord().filter(value -> value.isrVersion() > 0 && value.isr().size() == CLUSTER_SIZE);

            if (record.isPresent()) {
                return record.unwrap();
            }

            sleepQuietly(50L);
        }

        throw new AssertionError("no committed ISR of " + CLUSTER_SIZE + " for " + ENGINE_KEY + " within " + ISR_BUDGET_MS + "ms");
    }

    /// The record every node agrees on, or none while they differ.
    private Option<StreamPartitionOwnershipValue> committedRecord() {
        var seen = new HashSet<StreamPartitionOwnershipValue>();

        cluster.allNodes()
               .forEach(node -> node.kvStore()
                                    .getTyped(AetherKey.StreamPartitionOwnershipKey.streamPartitionOwnershipKey(ENGINE_KEY, 0),
                                              StreamPartitionOwnershipValue.class)
                                    .onPresent(seen::add));

        return seen.size() == 1
               ? Option.some(seen.iterator().next())
               : Option.none();
    }

    /// A publish entered at a node that is not the STREAMING governor, so the management layer forwards it to the governor
    /// over the HTTP forward hop (a share of the 10 s request budget, 2.5 s) -- the hop whose expiry the CI failure reported
    /// as 503 "outcome unknown". Only the one refusal a publish documents as safe to repeat (`503 ... refused before writing,
    /// retry`) is retried, bounded; any other answer, the 503 "outcome unknown" included, is returned as is.
    private String publishRetryingRefusal(String governor, String payload) {
        var deadline = System.currentTimeMillis() + REFUSAL_RETRY_BUDGET_MS;
        var response = publishResponse(entryNode(governor).mgmtPort(), payload);

        for (var attempt = 1; attempt < REFUSAL_RETRY_ATTEMPTS && isRetryableRefusal(response) && System.currentTimeMillis() < deadline; attempt++) {
            System.out.printf("PUBLISH-REFUSAL-RETRY %s attempt=%d -> %s%n", payload, attempt, response.lines().findFirst().orElse(""));
            sleepQuietly(REFUSAL_RETRY_PAUSE_MS);
            response = publishResponse(entryNode(governor).mgmtPort(), payload);
        }

        return response;
    }

    /// The node that owns the STREAMING task group, which the management layer forwards a publish to.
    private String streamingGovernor() {
        return cluster.allNodes()
                      .getFirst()
                      .taskGroupOwnerResolver()
                      .apply(TaskGroup.STREAMING)
                      .fold(cause -> {
                                throw new AssertionError("no STREAMING governor: " + cause.message());
                            },
                            NodeId::id);
    }

    private static boolean isRetryableRefusal(String response) {
        return response.startsWith("503") && response.contains("refused before writing, retry");
    }

    private EmberCluster.NodeStatus entryNode(String governor) {
        return cluster.status()
                      .nodes()
                      .stream()
                      .filter(node -> !node.id().equals(governor))
                      .findFirst()
                      .orElseThrow();
    }

    private String publishResponse(int mgmtPort, String payload) {
        return post(mgmtPort, "/api/v1/streams/" + NAMESPACE + "/" + NAME + "/" + VERSION + "/publish", "{\"data\":\"" + payload + "\"}");
    }

    private EmberCluster threeNodes(int basePort) {
        var built = emberCluster(CLUSTER_SIZE, basePort, basePort + MGMT_OFFSET, basePort + APP_HTTP_OFFSET, "refusedcu");

        built.withDataBaseDir(dataDir);

        return built;
    }

    private int leaderPort() {
        return cluster.getLeaderManagementPort().or(-1);
    }

    private void awaitLeader() {
        var deadline = System.currentTimeMillis() + LEADER_BUDGET_MS;

        while (System.currentTimeMillis() < deadline && cluster.getLeaderManagementPort().isEmpty()) {
            sleepQuietly(500L);
        }

        assertThat(cluster.getLeaderManagementPort().isPresent()).as("a leader is elected").isTrue();
    }

    /// `status body`, so a caller can assert the status class with `startsWith`.
    @SuppressWarnings("JBCT-EX-01")
    private static String post(int mgmtPort, String path, String json) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://127.0.0.1:" + mgmtPort + path))
                                 .timeout(REQUEST_TIMEOUT)
                                 .header("Content-Type", "application/json")
                                 .POST(HttpRequest.BodyPublishers.ofString(json))
                                 .build();

        try (var client = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            return response.statusCode() + " " + response.body();
        } catch (Exception e) {
            return "-1 " + e;
        }
    }

    @SuppressWarnings("JBCT-EX-01")
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
