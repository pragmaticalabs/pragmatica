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
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.pragmatica.aether.api.ClusterEvent;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #2014 — a PLANNED departure must not page, an UNPLANNED one must.
///
/// Five in-process nodes, so a follower drain is admitted by the disruption budget. Observers are read
/// in-process (`alertManager()`, `eventAggregator()`), the same two surfaces the operator sees. Each test
/// reports the observers that RAN the departure edge (positive control): a "no CRITICAL" result from a
/// node that never saw the departure would pass vacuously.
///
/// [unverified: the leader-change test stops the leader right after every observer holds the mark; whether
/// the drainee's DEAD edge lands before or after the leader stops depends on timing, so the test pins
/// "no CRITICAL for the drainee whichever side of the leader change it lands", not a fixed order.]
@PortBudget
class EmberPlannedDepartureAlertsTest {
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
    private static final long DEPARTURE_BUDGET_MS = 120_000L;
    private static final long SETTLE_MS = 20_000L;

    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            assertThat(cluster.stop().await(STOP_BOUND).fold(Cause::message, _ -> "stopped")).isEqualTo("stopped");
        }
    }

    @Test
    @Timeout(600)
    void followerDrain_raisesNoCriticalAlertOrEvent_andNoQuorumLost_onAnyNode() {
        start();
        var leaderId = cluster.currentLeader().unwrap();
        var drainee = followerOf(leaderId);
        var nodes = List.copyOf(cluster.allNodes());

        assertThat(drain(drainee)).as("drain admitted").startsWith("2");
        awaitCondition("the drained node left the cluster", DEPARTURE_BUDGET_MS, () -> cluster.getNode(drainee).isEmpty());
        // The edge must have RUN on the observers for the absence below to mean anything.
        awaitCondition("an observer recorded the announced departure",
                       DEPARTURE_BUDGET_MS,
                       () -> nodes.stream().anyMatch(n -> !n.self().id().equals(drainee) && announcedNodeLeft(n, drainee)));
        sleepQuietly(SETTLE_MS);

        assertThat(criticalSurfaces(nodes)).as("CRITICAL alerts/events anywhere after a planned follower drain").isEmpty();
        assertThat(quorumLostAnywhere(nodes)).as("QuorumLost on any node, the drained one included").isEmpty();
    }

    @Test
    @Timeout(600)
    void blackholedFollower_stillRaisesCriticalNodeFailedAndAlert() {
        start();
        var leaderId = cluster.currentLeader().unwrap();
        var victim = followerOf(leaderId);
        var nodes = List.copyOf(cluster.allNodes());

        assertThat(cluster.blackhole(victim).await(STOP_BOUND).isSuccess()).isTrue();
        awaitCondition("an observer raised CRITICAL for the unplanned death",
                       DEPARTURE_BUDGET_MS,
                       () -> nodes.stream()
                                  .filter(n -> !n.self().id().equals(victim))
                                  .anyMatch(n -> n.alertManager().getActiveNodeHealthAlerts().stream().anyMatch(a -> a.nodeId().id().equals(victim))
                                                 && criticalNodeFailed(n, victim)));
        assertThat(announcedNodeLeft(nodes.getFirst(), victim)).as("an unplanned death is never recorded as an announced departure").isFalse();
    }

    @Test
    @Timeout(600)
    void leaderChangeAfterTheDrainCommand_stillSuppressesTheDrainee_butNotTheLeaderKill() {
        start();
        var leaderId = cluster.currentLeader().unwrap();
        var drainee = followerOf(leaderId);
        var nodes = List.copyOf(cluster.allNodes());

        assertThat(drain(drainee)).as("drain admitted").startsWith("2");
        // Every observer holds the mark (or the drainee is already gone) BEFORE the leader goes.
        awaitCondition("observers hold the mark",
                       DEPARTURE_BUDGET_MS,
                       () -> cluster.getNode(drainee).isEmpty()
                             || nodes.stream()
                                     .filter(n -> !n.self().id().equals(drainee) && !n.self().id().equals(leaderId))
                                     .allMatch(n -> n.alertManager().hasAnnouncedDeparture(nodeId(drainee))));
        assertThat(cluster.killNode(leaderId).await(STOP_BOUND).isSuccess()).isTrue();
        awaitCondition("a survivor raised CRITICAL for the leader kill (unplanned stays loud)",
                       DEPARTURE_BUDGET_MS,
                       () -> survivors(nodes, leaderId, drainee).stream().anyMatch(n -> criticalNodeFailed(n, leaderId)));
        awaitCondition("the drainee is gone", DEPARTURE_BUDGET_MS, () -> cluster.getNode(drainee).isEmpty());
        sleepQuietly(SETTLE_MS);

        var survivors = survivors(nodes, leaderId, drainee);

        assertThat(survivors).isNotEmpty();
        assertThat(survivors).noneMatch(n -> criticalNodeFailed(n, drainee))
                             .noneMatch(n -> n.alertManager().getActiveNodeHealthAlerts().stream().anyMatch(a -> a.nodeId().id().equals(drainee)));
    }

    private void start() {
        cluster = EmberTestPorts.startedCluster(PORTS,
                                                basePort -> emberCluster(CLUSTER_SIZE,
                                                                         basePort,
                                                                         basePort + MGMT_OFFSET,
                                                                         basePort + APP_HTTP_OFFSET,
                                                                         "pdep"),
                                                START_BOUND);
        var deadline = System.currentTimeMillis() + LEADER_BUDGET_MS;

        while (System.currentTimeMillis() < deadline && cluster.getLeaderManagementPort().isEmpty()) {
            sleepQuietly(500L);
        }

        assertThat(cluster.getLeaderManagementPort().isPresent()).as("a leader is elected").isTrue();
    }

    private String followerOf(String leaderId) {
        return cluster.allNodes()
                      .stream()
                      .map(n -> n.self().id())
                      .filter(id -> !id.equals(leaderId))
                      .findFirst()
                      .orElseThrow();
    }

    private static org.pragmatica.consensus.NodeId nodeId(String id) {
        return org.pragmatica.consensus.NodeId.nodeId(id).unwrap();
    }

    private static List<AetherNode> survivors(List<AetherNode> all, String... gone) {
        var goneIds = List.of(gone);

        return all.stream().filter(n -> !goneIds.contains(n.self().id())).toList();
    }

    private static List<ClusterEvent> events(AetherNode node) {
        return node.eventAggregator().events().await().or(List.of());
    }

    private static boolean announcedNodeLeft(AetherNode observer, String departed) {
        return events(observer).stream()
                               .anyMatch(e -> e instanceof ClusterEvent.NodeLeft && departed.equals(e.details().get("nodeId"))
                                              && "DrainRequested".equals(e.details().get("cause")));
    }

    private static boolean criticalNodeFailed(AetherNode observer, String failed) {
        return events(observer).stream()
                               .anyMatch(e -> e instanceof ClusterEvent.NodeFailed && failed.equals(e.details().get("nodeId"))
                                              && e.severity() == ClusterEvent.Severity.CRITICAL);
    }

    private static List<String> criticalSurfaces(List<AetherNode> nodes) {
        return nodes.stream()
                    .flatMap(n -> java.util.stream.Stream.concat(events(n).stream()
                                                                          .filter(e -> e instanceof ClusterEvent.NodeFailed)
                                                                          .map(e -> n.self().id() + " event " + e.summary()),
                                                                 n.alertManager().getActiveNodeHealthAlerts()
                                                                  .stream()
                                                                  .map(a -> n.self().id() + " alert " + a.nodeId().id())))
                    .toList();
    }

    private static List<String> quorumLostAnywhere(List<AetherNode> nodes) {
        return nodes.stream()
                    .flatMap(n -> events(n).stream()
                                           .filter(e -> e instanceof ClusterEvent.QuorumLost)
                                           .map(e -> n.self().id() + " " + e.summary()))
                    .toList();
    }

    private String drain(String nodeId) {
        var port = cluster.getLeaderManagementPort().or(-1);
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/nodes/drain/" + nodeId))
                                 .timeout(Duration.ofSeconds(30))
                                 .header("Content-Type", "application/json")
                                 .POST(HttpRequest.BodyPublishers.noBody())
                                 .build();

        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            return response.statusCode() + " " + response.body();
        } catch (Exception e) {
            return "-1 " + e;
        }
    }

    private static void awaitCondition(String what, long budgetMs, BooleanSupplier condition) {
        var deadline = System.currentTimeMillis() + budgetMs;

        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }

            sleepQuietly(500L);
        }

        assertThat(condition.getAsBoolean()).as(what).isTrue();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
