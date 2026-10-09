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
    private static final long ADMISSION_BUDGET_MS = 90_000L;
    private static final long EVENT_REPLICATION_BUDGET_MS = 120_000L;

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
        followerDrainScenario(false);
    }

    /// Run 2's exact shape, forced: a SWIM incarnation refutation moves the leader's FSM DEPARTING to MEMBER while
    /// the drain is still commanded. The leader must still report a planned departure.
    @Test
    @Timeout(600)
    void followerDrain_withAnIncarnationRefutationMidDrain_stillRaisesNothing() {
        followerDrainScenario(true);
    }

    private void followerDrainScenario(boolean refuteMidDrain) {
        start();
        var leaderId = cluster.currentLeader().unwrap();
        var drainee = followerOf(leaderId);
        var nodes = List.copyOf(cluster.allNodes());
        var survivorIds = survivors(nodes, drainee).stream().map(n -> n.self().id()).collect(java.util.stream.Collectors.toSet());

        assertThat(drain(drainee)).as("drain admitted").startsWith("2");

        if (refuteMidDrain) {
            var leader = cluster.getNode(leaderId).unwrap();

            leader.membershipFsm().onSwimHealthy(nodeId(drainee), 1_000_000L);
            awaitCondition(() -> "the leader's FSM took the refutation (DEPARTING to MEMBER); state " + leader.membershipFsm().memberStates().get(nodeId(drainee)),
                           ADMISSION_BUDGET_MS,
                           () -> "Member".equals(leader.membershipFsm().memberStates().get(nodeId(drainee))));
        }

        awaitCondition("the drained node left the cluster", DEPARTURE_BUDGET_MS, () -> cluster.getNode(drainee).isEmpty());
        // Positive control: EVERY survivor processed the departure and reported it as announced. An absence below
        // from a survivor that never saw the edge would pass vacuously.
        awaitCondition(() -> "every survivor holds the announced departure record; observers that do: " + departureObservers(nodes, drainee) + " of " + survivorIds,
                       EVENT_REPLICATION_BUDGET_MS,
                       () -> departureObservers(nodes, drainee).containsAll(survivorIds));
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
        var observers = nodes.stream().filter(n -> !n.self().id().equals(victim)).toList();

        awaitCondition("an observer raised the CRITICAL node-health alert for the unplanned death",
                       DEPARTURE_BUDGET_MS,
                       () -> observers.stream().anyMatch(n -> hasCriticalAlert(n, victim)));
        // The operator surface: the cluster-events stream. Bounded long enough for replication; on failure the
        // message carries what every observer actually holds, so a replication LOSS is visible, not smoothed over.
        awaitCondition(() -> "an observer's cluster-events stream carries CRITICAL NodeFailed for the unplanned death; held: " + eventDump(observers, victim),
                       EVENT_REPLICATION_BUDGET_MS,
                       () -> observers.stream().anyMatch(n -> criticalNodeFailed(n, victim)));
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
        awaitCondition("a survivor raised the CRITICAL alert for the leader kill (unplanned stays loud)",
                       DEPARTURE_BUDGET_MS,
                       () -> survivors(nodes, leaderId, drainee).stream().anyMatch(n -> hasCriticalAlert(n, leaderId)));
        awaitCondition(() -> "a survivor's cluster-events stream carries CRITICAL NodeFailed for the leader kill; held: " + eventDump(survivors(nodes, leaderId, drainee), leaderId),
                       EVENT_REPLICATION_BUDGET_MS,
                       () -> survivors(nodes, leaderId, drainee).stream().anyMatch(n -> criticalNodeFailed(n, leaderId)));
        awaitCondition("the drainee is gone", DEPARTURE_BUDGET_MS, () -> cluster.getNode(drainee).isEmpty());
        var survivors = survivors(nodes, leaderId, drainee);
        var survivorIds = survivors.stream().map(n -> n.self().id()).collect(java.util.stream.Collectors.toSet());

        // Positive control: every surviving observer reported the drainee's departure as announced. Under a
        // notifier that never announces (mutation m1) they report a CRITICAL NodeFailed instead and this goes red.
        awaitCondition(() -> "every surviving observer holds the announced departure record; observers that do: " + departureObservers(survivors, drainee) + " of " + survivorIds,
                       EVENT_REPLICATION_BUDGET_MS,
                       () -> departureObservers(survivors, drainee).containsAll(survivorIds));
        sleepQuietly(SETTLE_MS);

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
        // #2061: a leader is NOT a readable cluster-events stream. The stream's partition is created and promoted after the
        // quorum forms; a node blackholed before that point is a co-replica that cold-start promotion waits for ("cold-start
        // self-promotion BLOCKED - a co-replica is unreachable"), so the stream never becomes writable and EVERY observer
        // reads events=0, while the local node-health alert still fires. CI hit exactly that on #2068 and #2072: blackhole
        // 5-7 s after quorum. Acting only once every node can read an event keeps the event-surface assertions meaning
        // "the stream carried the record" instead of racing the stream's own boot. A precondition only: no assertion below
        // is weakened.
        awaitCondition(() -> "the cluster-events stream is readable on every node before the scenario starts; nodes reading nothing: " + silentNodes(),
                       EVENT_REPLICATION_BUDGET_MS,
                       () -> silentNodes().isEmpty());
    }

    private List<String> silentNodes() {
        return cluster.allNodes().stream().filter(n -> events(n).isEmpty()).map(n -> n.self().id()).toList();
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

    /// Ids of the observers whose announced `NodeLeft` for `subject` is readable from any of `readers`.
    private static java.util.Set<String> departureObservers(List<AetherNode> readers, String subject) {
        return readers.stream()
                      .flatMap(n -> events(n).stream())
                      .filter(e -> e instanceof ClusterEvent.NodeLeft && subject.equals(e.details().get("nodeId")) && "DrainRequested".equals(e.details().get("cause")))
                      .map(e -> e.details().get("observedBy"))
                      .collect(java.util.stream.Collectors.toSet());
    }

    private static boolean announcedNodeLeft(AetherNode observer, String departed) {
        return events(observer).stream()
                               .anyMatch(e -> e instanceof ClusterEvent.NodeLeft && departed.equals(e.details().get("nodeId"))
                                              && "DrainRequested".equals(e.details().get("cause")));
    }

    /// The node-health alert is local state on the observer, so unlike the cluster-events stream (whose read
    /// can lag or gap on a replica) it is a reliable positive control that an unplanned death is loud.
    private static boolean hasCriticalAlert(AetherNode observer, String failed) {
        return observer.alertManager()
                       .getActiveNodeHealthAlerts()
                       .stream()
                       .anyMatch(a -> a.nodeId().id().equals(failed));
    }

    /// Per observer: how many events it can read and how many NodeFailed/NodeLeft name `subject`.
    private static String eventDump(List<AetherNode> observers, String subject) {
        return observers.stream()
                        .map(n -> {
                            var all = events(n);

                            return n.self().id() + "[events=" + all.size()
                                   + " failed=" + all.stream().filter(e -> e instanceof ClusterEvent.NodeFailed && subject.equals(e.details().get("nodeId"))).count()
                                   + " left=" + all.stream().filter(e -> e instanceof ClusterEvent.NodeLeft && subject.equals(e.details().get("nodeId"))).count() + "]";
                        })
                        .toList()
                        .toString();
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

    /// Waits for the signal the drain route itself reads: the LEADER's readiness view (`reportedStates`, filled
    /// from pongs) reporting the target READY. Right after formation that view is empty and the drain POST
    /// answers 404 "Node lifecycle not found" (run 1, 2026-10-08, every arm) although the node is a live
    /// member: the GET route answers 503 for that condition (#1868) but the drain POST path still says 404 [see
    /// f-2014-report.md]. One POST after the signal, no retry: a refusal must fail the test, not be smoothed over.
    private String drain(String nodeId) {
        var leader = cluster.getNode(cluster.currentLeader().unwrap()).unwrap();

        awaitCondition("the leader's readiness view reports " + nodeId + " READY",
                       ADMISSION_BUDGET_MS,
                       () -> leader.metricsCollector().reportedStates().get(nodeId(nodeId)) == org.pragmatica.aether.metrics.NodeReportedState.READY);

        return drainOnce(nodeId);
    }

    private String drainOnce(String nodeId) {
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
        awaitCondition(() -> what, budgetMs, condition);
    }

    private static void awaitCondition(Supplier<String> what, long budgetMs, BooleanSupplier condition) {
        var deadline = System.currentTimeMillis() + budgetMs;

        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }

            sleepQuietly(500L);
        }

        assertThat(condition.getAsBoolean()).as(what.get()).isTrue();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
