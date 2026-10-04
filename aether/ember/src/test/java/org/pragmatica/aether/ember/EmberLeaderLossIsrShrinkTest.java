// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.stream.IntStream;

import org.pragmatica.aether.config.StreamingConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.assertj.core.api.Assertions.assertThat;


/// #1730, multi-node: when the node that dies is both the consensus leader and a non-owner ISR member, the leader that
/// replaces it drops it from the committed ISR once membership counts it departed, not one `isrLagMax` later.
///
/// The ISR shrink for a departed member is the leader's ownership-writer decision, made on the reconcile a membership
/// decision triggers; placement keeps every committed ISR member, so until that shrink the dead node also stays in the
/// partition's replica set. The writer's "live" set is placement membership (installed voters narrowed to the
/// membership FSM's counted members), so the shrink lands when membership drops the node. In a warm cluster that should
/// be SWIM suspicion plus FAULTY, about ten seconds (not measured here); in a cluster this young SWIM's cold-boot suppression
/// (#1830) defers FAULTY for a peer it never observed HEALTHY. Measured on bigboy 2026-10-03: 32 s (authoritative
/// removal) and 67 s (the deferred FAULTY replayed). `isrLagMax` is raised to ten minutes so the owner's lag shrink
/// cannot be what passes this test, and the 150 s budget is bounded by membership departure, not by the election.
///
/// The in-run precondition: before the kill, the committed record names the leader as an ISR member that is not the
/// owner, so the expected record is exactly "same owner, ISR minus the leader".
class EmberLeaderLossIsrShrinkTest {
    private static final int CLUSTER_SIZE = 5;
    private static final int SLOTS = 2 * CLUSTER_SIZE;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_HTTP_OFFSET = 80;

    private static final EmberTestPorts.Block PORTS = new EmberTestPorts.Block(SLOTS,
                                                                               MGMT_OFFSET,
                                                                               APP_HTTP_OFFSET);

    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(180).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan ISR_LAG_MAX = TimeSpan.timeSpan(10).minutes();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final long LEADER_BUDGET_MS = 90_000L;
    private static final long ISR_BUDGET_MS = 60_000L;
    private static final long SHRINK_BUDGET_MS = 150_000L;
    private static final int STREAMS = 14;
    private static final String NAMESPACE = "ember";
    private static final String VERSION = "1.0.0";

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
    void leaderThatWasANonOwnerIsrMember_dies_nextLeaderDropsItFromTheIsr_onMembershipDeparture_notTheLagWindow() {
        cluster = EmberTestPorts.startedCluster(PORTS, this::fiveNodes, START_BOUND);
        awaitLeader();
        var leaderId = cluster.currentLeader().unwrap();
        var streams = IntStream.range(0, STREAMS).mapToObj(i -> "ldrloss" + i).toList();

        streams.forEach(name -> {
            var created = post(leaderPort(),
                               "/api/v1/streams/" + NAMESPACE + "/" + name + "/" + VERSION,
                               "{\"partitions\":1}");

            assertThat(created).as("create %s", name)
                      .startsWith("2");
        });
        // RF 3 of 5: per stream the leader is a non-owner ISR member with probability 2/5, so one of fourteen misses
        // with probability 0.6^14 < 0.1%.
        var target = streams.stream()
                            .map(name -> engineKey(name))
                            .map(key -> new Target(key,
                                                   awaitFullIsr(key,
                                                                List.of())))
                            .filter(candidate -> !candidate.record()
                                                           .owner()
                                                           .id()
                                                           .equals(leaderId) && candidate.record()
                                                                                         .isr()
                                                                                         .stream()
                                                                                         .anyMatch(member -> member.id()
                                                                                                                   .equals(leaderId)))
                            .findFirst()
                            .orElseThrow(() -> new AssertionError("no stream whose ISR holds leader " + leaderId
                                                                 + " as a non-owner"));
        var before = target.record();

        assertThat(cluster.killNode(leaderId).await(STOP_BOUND).fold(Cause::message, _ -> "killed")).as("kill leader %s",
                                                                                                        leaderId)
                  .isEqualTo("killed");
        var killedAt = System.nanoTime();
        var after = awaitRecord(target.key(),
                                leaderId,
                                record -> record.isr()
                                                .stream()
                                                .noneMatch(member -> member.id()
                                                                           .equals(leaderId)));
        var elapsedMs = (System.nanoTime() - killedAt) / 1_000_000L;

        System.out.printf("LDRLOSS stream=%s leader=%s before=%s after=%s in %dms%n",
                          target.key(),
                          leaderId,
                          before,
                          after,
                          elapsedMs);
        assertThat(after.isPresent()).as("the committed ISR of %s still holds the dead leader %s %dms after the kill (before: %s)",
                                         target.key(),
                                         leaderId,
                                         SHRINK_BUDGET_MS,
                                         before)
                  .isTrue();
        assertThat(after.unwrap().owner()).as("a non-owner left: ownership stands").isEqualTo(before.owner());
        assertThat(after.unwrap().isr()).as("exactly the leader left the ISR")
                  .containsExactlyElementsOf(before.isr()
                                                   .stream()
                                                   .filter(member -> !member.id()
                                                                            .equals(leaderId))
                                                   .toList());
    }

    private record Target(String key, StreamPartitionOwnershipValue record) {}

    private static String engineKey(String name) {
        return NAMESPACE + ":" + name + ":" + VERSION;
    }

    private StreamPartitionOwnershipValue awaitFullIsr(String key, List<String> excluded) {
        var deadline = System.currentTimeMillis() + ISR_BUDGET_MS;

        while (System.currentTimeMillis() < deadline) {
            var record = committedRecord(key, excluded).filter(value -> value.isrVersion() > 0 && value.isr()
                                                                                                       .size() == 3);

            if (record.isPresent()) {
                return record.unwrap();
            }

            sleepQuietly(250L);
        }

        throw new AssertionError("no committed ISR of three for " + key + " within " + ISR_BUDGET_MS + "ms");
    }

    private Option<StreamPartitionOwnershipValue> awaitRecord(String key,
                                                              String killed,
                                                              java.util.function.Predicate<StreamPartitionOwnershipValue> done) {
        var deadline = System.currentTimeMillis() + SHRINK_BUDGET_MS;

        while (System.currentTimeMillis() < deadline) {
            var record = committedRecord(key, List.of(killed)).filter(done);

            if (record.isPresent()) {
                return record;
            }

            sleepQuietly(250L);
        }

        return Option.none();
    }

    /// The record every live node agrees on, or none while they differ.
    private Option<StreamPartitionOwnershipValue> committedRecord(String key, List<String> excluded) {
        var seen = new HashSet<StreamPartitionOwnershipValue>();

        cluster.allNodes()
               .stream()
               .filter(node -> !excluded.contains(node.self().id()))
               .forEach(node -> node.kvStore()
                                    .getTyped(AetherKey.StreamPartitionOwnershipKey.streamPartitionOwnershipKey(key, 0),
                                              StreamPartitionOwnershipValue.class)
                                    .onPresent(seen::add));

        return seen.size() == 1
               ? Option.some(seen.iterator().next())
               : Option.none();
    }

    private EmberCluster fiveNodes(int basePort) {
        var built = emberCluster(CLUSTER_SIZE, basePort, basePort + MGMT_OFFSET, basePort + APP_HTTP_OFFSET, "ldrloss");

        built.withDataBaseDir(dataDir);
        built.withStreamingConfig(StreamingConfig.streamingConfig().withIsrLagMax(ISR_LAG_MAX));

        return built;
    }

    private int leaderPort() {
        return cluster.getLeaderManagementPort()
                      .or(-1);
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
