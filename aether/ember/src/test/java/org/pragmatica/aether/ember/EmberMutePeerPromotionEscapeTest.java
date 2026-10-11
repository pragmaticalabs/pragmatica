// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.forward.StreamForwardMessage;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #2080 -- the bounded ISR escape, end to end, in the #1563 shape: a peer that keeps its transport handshaking, stays a MEMBER in every
/// survivor's membership view, and answers NOTHING to the owner's watermark probe.
///
/// Five in-process nodes. Before the stream exists, one non-leader node is made mute: its
/// inbound `ReadForward` messages (the owner activation's probe) are dropped, nothing else is. Consensus, SWIM, replication and every
/// other message class still flow, so no survivor ever declares it DEAD and no leader shrinks it out of the committed in-sync set. The
/// stream is then created, and its owner has to activate against five live members, one of which never answers.
///
/// On rc4 the owner's gate waits for the mute member for as long as it stays in the live set, so the stream never becomes writable. At
/// the fix the owner (named in the committed in-sync set, `isrVersion > 0`) proceeds after `promotion_escape_after` (120 s) and reports one `stream-promotion-past-unreachable-peers` event; the mute member
/// still replicates, so acknowledged publishes then succeed. The test asserts, with a poll line each [#POLL_EVERY_MS]:
///   - the mute member really dropped probes (the count is printed and asserted > 0), so the pass cannot come from a probe that was never
///     sent;
///   - the mute member is still MEMBER in the owner's membership view when the stream becomes writable, so it was the escape and not a
///     DEAD verdict that unblocked the partition;
///   - the stream accepts a publish within [#WRITABLE_BOUND_MS] of its creation, and the owner node holds the escape event.
///
/// Model, stated plainly: this is the #1563 shape at the layer the gates read (the stream probe), not a wedged JVM. The Ember
/// `blackhole` is not used because there the QUIC idle timeout converges a DEAD verdict (about 79 s) and the committed in-sync set then
/// shrinks, which unblocks the partition by the other route (#2077).
@PortBudget
class EmberMutePeerPromotionEscapeTest {
    private static final int CLUSTER_SIZE = 5;
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
    private static final long LEADER_BUDGET_MS = 90_000L;
    /// The stated bound: the escape bound (`[streaming] promotion_escape_after`, 120 s at the default) plus the re-drive backoff (at most
    /// 2 s), the no-op consensus round, the epoch-start commit and a publish round, with slack.
    static final long WRITABLE_BOUND_MS = 150_000L;
    private static final long POLL_EVERY_MS = 3_000L;
    private static final String STREAM_NAMESPACE = "ember";
    private static final int MAX_STREAM_TRIES = 8;

    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            assertThat(cluster.stop().await(STOP_BOUND).fold(Cause::message, _ -> "stopped")).isEqualTo("stopped");
        }
    }

    @Test
    @Timeout(600)
    void mutePeer_neverAnswersTheProbe_streamBecomesWritableWithinTheBound_andTheEscapeIsReported() {
        cluster = EmberTestPorts.startedCluster(PORTS,
                                                basePort -> emberCluster(CLUSTER_SIZE,
                                                                         basePort,
                                                                         basePort + MGMT_OFFSET,
                                                                         basePort + APP_HTTP_OFFSET,
                                                                         "mute"),
                                                START_BOUND);
        var deadline = System.currentTimeMillis() + LEADER_BUDGET_MS;

        while (System.currentTimeMillis() < deadline && cluster.getLeaderManagementPort().isEmpty()) {
            sleepQuietly(200L);
        }

        assertThat(cluster.getLeaderManagementPort().isPresent()).as("a leader is elected").isTrue();
        var leaderId = cluster.currentLeader().unwrap();
        var nodes = List.copyOf(cluster.allNodes());
        var members = nodes.stream().map(n -> NodeId.nodeId(n.self().id()).unwrap()).toList();
        var victim = nodes.stream().filter(n -> !n.self().id().equals(leaderId)).findFirst().orElseThrow();
        var victimId = NodeId.nodeId(victim.self().id()).unwrap();
        var dropped = new AtomicInteger();

        victim.setInboundFaultFilter((_, message) -> {
            if (message instanceof StreamForwardMessage.ReadForward) {
                dropped.incrementAndGet();

                return false;
            }

            return true;
        });
        System.out.printf("PROBE ESCAPE leader=%s mute=%s%n", leaderId, victimId);

        // The stream's owner is whatever placement picks; a stream the mute member itself owns activates normally (its own probes are
        // answered), so it is skipped -- it is the control that the mute member works. The first stream owned by another node is measured.
        String name = null;
        AetherNode owner = null;
        var createdAt = 0L;

        for (var index = 0; index < MAX_STREAM_TRIES && owner == null; index++) {
            var candidate = "escape" + index;

            createdAt = System.currentTimeMillis();
            assertThat(createWithRetry(candidate)).as("create " + candidate).startsWith("2");
            var committedOwner = committedOwnerOf(nodes, STREAM_NAMESPACE + ":" + candidate + ":1.0.0");

            System.out.printf("PROBE ESCAPE stream=%s committedOwner=%s%n", candidate, committedOwner);
            if (!committedOwner.equals(victimId.id())) {
                name = candidate;
                owner = nodes.stream().filter(n -> n.self().id().equals(committedOwner)).findFirst().orElseThrow();
            }
        }

        assertThat(owner).as("some stream within " + MAX_STREAM_TRIES + " tries is owned by a node other than the mute one").isNotNull();
        var stream = STREAM_NAMESPACE + ":" + name + ":1.0.0";
        var survivors = nodes.stream().filter(n -> n != victim).toList();
        var writableAtMs = -1L;

        while (System.currentTimeMillis() - createdAt < WRITABLE_BOUND_MS && writableAtMs < 0) {
            var accepted = publish(survivors, name);

            System.out.printf("PROBE ESCAPE t=+%ds writable=%s probesDroppedByMute=%d record=%s ownerSeesMute=%s%n",
                              (System.currentTimeMillis() - createdAt) / 1000,
                              accepted,
                              dropped.get(),
                              record(owner, stream),
                              owner.membershipFsm().memberStates().get(victimId));
            if (accepted) {
                writableAtMs = System.currentTimeMillis() - createdAt;
            } else {
                sleepQuietly(POLL_EVERY_MS);
            }
        }

        System.out.printf("PROBE ESCAPE writableAtMs=%d bound=%d probesDroppedByMute=%d%n", writableAtMs, WRITABLE_BOUND_MS, dropped.get());
        assertThat(dropped.get()).as("the mute member dropped the owner's probes (the scenario is not vacuous)").isPositive();
        assertThat(String.valueOf(owner.membershipFsm().memberStates().get(victimId)))
            .as("the mute member is still a MEMBER in the owner's view: nothing declared it dead")
            .containsIgnoringCase("member");
        assertThat(writableAtMs).as("the stream accepts a publish within " + WRITABLE_BOUND_MS + " ms of its creation (-1 = never)")
                                .isBetween(0L, WRITABLE_BOUND_MS);
        assertThat(owner.eventAggregator().events().await().or(List.of()))
            .as("the owner reported the escape as an operator event")
            .anySatisfy(event -> assertThat(String.valueOf(event)).contains("stream-promotion-past-unreachable-peers").contains(victimId.id()));
    }

    private static String record(AetherNode node, String stream) {
        var key = AetherKey.StreamPartitionOwnershipKey.streamPartitionOwnershipKey(stream, 0);

        return node.kvStore()
                   .getTyped(key, StreamPartitionOwnershipValue.class)
                   .map(v -> "owner=" + v.owner().id() + " isr=" + v.isr().stream().map(NodeId::id).toList() + " isrV=" + v.isrVersion())
                   .or("<none>");
    }

    private boolean publish(List<AetherNode> survivors, String name) {
        return survivors.stream()
                        .anyMatch(node -> postTo(mgmtPortOf(node), "/api/v1/streams/" + STREAM_NAMESPACE + "/" + name + "/1.0.0/publish", "{\"data\":\"probe\"}")
                            .startsWith("2"));
    }

    /// The committed owner of partition 0, read from every node until one holds the record (30 s budget).
    private static String committedOwnerOf(List<AetherNode> nodes, String stream) {
        var deadline = System.currentTimeMillis() + 30_000L;
        var key = AetherKey.StreamPartitionOwnershipKey.streamPartitionOwnershipKey(stream, 0);

        while (System.currentTimeMillis() < deadline) {
            for (var n : nodes) {
                var record = n.kvStore().getTyped(key, StreamPartitionOwnershipValue.class);

                if (record.isPresent()) {
                    return record.map(v -> v.owner().id()).or("");
                }
            }
            sleepQuietly(300L);
        }

        return "";
    }

    private int mgmtPortOf(AetherNode node) {
        return cluster.status()
                      .nodes()
                      .stream()
                      .filter(i -> i.id().equals(node.self().id()))
                      .findFirst()
                      .map(i -> i.mgmtPort())
                      .orElse(-1);
    }

    private String createWithRetry(String name) {
        var deadline = System.currentTimeMillis() + 60_000L;
        var path = "/api/v1/streams/" + STREAM_NAMESPACE + "/" + name + "/1.0.0";
        var response = postTo(cluster.getLeaderManagementPort().or(-1), path, "{\"partitions\":1}");

        while (!response.startsWith("2") && System.currentTimeMillis() < deadline) {
            sleepQuietly(500L);
            response = postTo(cluster.getLeaderManagementPort().or(-1), path, "{\"partitions\":1}");
        }

        return response;
    }

    @SuppressWarnings("JBCT-EX-01")
    private static String postTo(int mgmtPort, String path, String json) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://127.0.0.1:" + mgmtPort + path))
                                 .timeout(Duration.ofSeconds(20))
                                 .header("Content-Type", "application/json")
                                 .POST(HttpRequest.BodyPublishers.ofString(json))
                                 .build();

        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            return response.statusCode() + " " + response.body();
        } catch (Exception e) {
            return "-1 " + e;
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
