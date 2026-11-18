// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.pragmatica.aether.deployment.membership.ntt.DrainProcedure;
import org.pragmatica.aether.metrics.NodeReportedState;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #3348 — an operator drain of the CURRENT LEADER, through the same management route an operator uses.
///
/// The issue claims (read from code, not run) that the leader marks ITSELF DEPARTING while its own
/// `DrainProcedure` never leaves INACTIVE (the ping that carries the drain command skips the sender). This test
/// RECORDS what happens (`T3348` lines on stdout, one per observable change) and asserts the expected behaviour
/// of the issue: "the drain either works or is refused at the route; it never half-applies".
///
/// Observed at `98e479c8c6` (3 cores, default engine, 5 runs): the route REFUSES with `404 Node lifecycle not found`, on the
/// leader's port and on a follower's, because no node's readiness view carries the leader (the leader's own view is built from
/// pongs and omits itself); nothing is dispatched, so the leader stays Member/INACTIVE and keeps leadership. The claim's
/// DEPARTING path is therefore not reached through the route. This is a positive pin of "never half-applies"; the refusal's
/// status and wording are NOT pinned (a 404 that names no remedy is a separate documentation/typed-error question).
///
/// The `DrainProcedure` is not reachable through the `AetherNode` interface (it is a local of the node
/// assembly), so the test finds it by walking the node's object graph (instrumentation only, no production change).
@PortBudget
class EmberLeaderDrainTest {
    private static final int CLUSTER_SIZE = Integer.getInteger("t3348.size", 3);
    private static final int SLOTS = 2 * 5;
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
    private static final long READY_BUDGET_MS = 90_000L;
    // membership departure timeout = MembershipConfig.DEFAULT_SPLIT_TIMEOUT (15 s); the window covers it with margin
    private static final long OBSERVE_WINDOW_MS = 60_000L;
    private static final long SAMPLE_MS = 100L;

    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            assertThat(cluster.stop().await(STOP_BOUND).fold(Cause::message, _ -> "stopped")).isEqualTo("stopped");
        }
    }

    /// Control: a NON-leader drained the same way runs its drain procedure and exits.
    @Test
    @Timeout(600)
    void followerDrain_control_drainRunsAndNodeExits() {
        start();
        var leaderId = cluster.currentLeader().unwrap();
        var follower = cluster.allNodes().stream().map(n -> n.self().id()).filter(id -> !id.equals(leaderId)).findFirst().orElseThrow();
        var drainee = cluster.getNode(follower).unwrap();
        var procedure = drainProcedureOf(drainee);

        awaitReady(follower);

        var answer = postDrain(follower, cluster.getLeaderManagementPort().or(-1));

        System.out.println("T3348 control size=" + CLUSTER_SIZE + " route=" + answer);
        assertThat(answer).as("control: the follower drain is admitted by the route").startsWith("2");

        var deadline = System.currentTimeMillis() + OBSERVE_WINDOW_MS;

        while (System.currentTimeMillis() < deadline && cluster.getNode(follower).isPresent()) {
            sleepQuietly(SAMPLE_MS);
        }

        // DRAINING lasts milliseconds on an idle node; the state is single-shot (nothing leads back to INACTIVE), so the
        // final state proves it left INACTIVE.
        var sawDraining = procedure.state() != DrainProcedure.DrainState.INACTIVE;

        System.out.println("T3348 control drainProcedure left INACTIVE=" + sawDraining + " final=" + procedure.state()
                           + " exited=" + cluster.getNode(follower).isEmpty());
        assertThat(sawDraining).as("control: the follower's DrainProcedure left INACTIVE").isTrue();
        assertThat(cluster.getNode(follower).isEmpty()).as("control: the drained follower exited").isTrue();
    }

    /// The claim of #3348. Records the timeline, then asserts the expected behaviour: at no sample is the leader
    /// DEPARTING on itself while its own drain procedure is INACTIVE.
    @Test
    @Timeout(600)
    void leaderDrain_throughTheLeadersRoute_neverHalfApplies() {
        leaderDrainScenario(false);
    }

    /// The same drain posted to a FOLLOWER's management port: an operator may address any node.
    @Test
    @Timeout(600)
    void leaderDrain_throughAFollowersRoute_neverHalfApplies() {
        leaderDrainScenario(true);
    }

    private void leaderDrainScenario(boolean viaFollower) {
        start();
        var leaderId = cluster.currentLeader().unwrap();
        var leader = cluster.getNode(leaderId).unwrap();
        var leaderNodeId = nodeId(leaderId);
        var procedure = drainProcedureOf(leader);
        var followers = cluster.allNodes().stream().filter(n -> !n.self().id().equals(leaderId)).toList();

        awaitReady(leaderId);

        var port = viaFollower
                   ? cluster.status().nodes().stream().filter(n -> !n.id().equals(leaderId)).findFirst().orElseThrow().mgmtPort()
                   : cluster.getLeaderManagementPort().or(-1);

        if (viaFollower) {
            awaitViewOf(followers.getFirst(), leaderId);
        }

        var answer = postDrain(leaderId, port);

        System.out.println("T3348 leader size=" + CLUSTER_SIZE + " via=" + (viaFollower ? "follower" : "leader") + " port=" + port + " leader=" + leaderId + " route=" + answer);

        var halfApplied = new ArrayList<String>();
        var last = "";
        var deadline = System.currentTimeMillis() + OBSERVE_WINDOW_MS;
        var start = System.currentTimeMillis();

        while (System.currentTimeMillis() < deadline) {
            var selfState = leader.membershipFsm().memberStates().getOrDefault(leaderNodeId, "<untracked>");
            var drain = procedure.state();
            var leaderNow = cluster.currentLeader().or("<none>");
            var present = cluster.getNode(leaderId).isPresent();
            var views = followers.stream()
                                 .map(f -> f.self().id() + "=" + f.membershipFsm().memberStates().getOrDefault(leaderNodeId, "<untracked>"))
                                 .toList();
            var line = "selfState=" + selfState + " drain=" + drain + " leaderNow=" + leaderNow + " present=" + present + " followerViews=" + views;

            if (!line.equals(last)) {
                System.out.println("T3348 t+" + (System.currentTimeMillis() - start) + "ms " + line);
                last = line;
            }

            if (selfState.toUpperCase().contains("DEPART") && drain == DrainProcedure.DrainState.INACTIVE) {
                halfApplied.add("t+" + (System.currentTimeMillis() - start) + "ms " + line);
            }

            sleepQuietly(SAMPLE_MS);
        }

        System.out.println("T3348 leader final: " + last + " halfAppliedSamples=" + halfApplied.size());
        assertThat(halfApplied)
            .as("#3348: the leader was DEPARTING on itself while its own DrainProcedure was INACTIVE; route answered " + answer)
            .isEmpty();
    }

    private void start() {
        cluster = EmberTestPorts.startedCluster(PORTS,
                                                basePort -> emberCluster(CLUSTER_SIZE,
                                                                         basePort,
                                                                         basePort + MGMT_OFFSET,
                                                                         basePort + APP_HTTP_OFFSET,
                                                                         "ldrain"),
                                                START_BOUND);
        var deadline = System.currentTimeMillis() + LEADER_BUDGET_MS;

        while (System.currentTimeMillis() < deadline && cluster.getLeaderManagementPort().isEmpty()) {
            sleepQuietly(500L);
        }

        assertThat(cluster.getLeaderManagementPort().isPresent()).as("a leader is elected").isTrue();
    }

    /// The route answers 404 "Node lifecycle not found" until the leader's readiness view reports the target READY
    /// (see EmberPlannedDepartureAlertsTest#drain). For the leader's own id the view may never carry it: wait the
    /// budget, record the outcome, and POST once either way.
    private void awaitReady(String nodeId) {
        var leader = cluster.getNode(cluster.currentLeader().unwrap()).unwrap();
        BooleanSupplier ready = () -> leader.metricsCollector().reportedStates().get(nodeId(nodeId)) == NodeReportedState.READY;
        var deadline = System.currentTimeMillis() + READY_BUDGET_MS;

        while (System.currentTimeMillis() < deadline && !ready.getAsBoolean()) {
            sleepQuietly(500L);
        }

        System.out.println("T3348 readiness view of " + nodeId + " on the leader: " + leader.metricsCollector().reportedStates().get(nodeId(nodeId)));
    }

    /// A follower's own readiness view of the leader (the route reads the receiving node's view).
    private static void awaitViewOf(AetherNode viewer, String nodeId) {
        var deadline = System.currentTimeMillis() + READY_BUDGET_MS;

        while (System.currentTimeMillis() < deadline && viewer.metricsCollector().reportedStates().get(nodeId(nodeId)) != NodeReportedState.READY) {
            sleepQuietly(500L);
        }

        System.out.println("T3348 readiness view of " + nodeId + " on follower " + viewer.self().id() + ": " + viewer.metricsCollector().reportedStates().get(nodeId(nodeId)));
    }

    private String postDrain(String nodeId, int port) {
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

    /// Breadth-first walk over the node's object graph (project classes only) for its one DrainProcedure.
    private static DrainProcedure drainProcedureOf(AetherNode node) {
        var seen = new IdentityHashMap<Object, Boolean>();
        var queue = new ArrayDeque<Object>();
        var depths = new IdentityHashMap<Object, Integer>();

        queue.add(node);
        depths.put(node, 0);

        while (!queue.isEmpty()) {
            var current = queue.poll();

            if (current instanceof DrainProcedure found) {
                return found;
            }

            var depth = depths.get(current);

            if (depth >= 6) {
                continue;
            }

            for (var type = current.getClass(); type != null && type.getName().startsWith("org.pragmatica"); type = type.getSuperclass()) {
                for (var field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                        continue;
                    }

                    try {
                        field.setAccessible(true);
                        var value = field.get(current);

                        if (value != null && value.getClass().getName().startsWith("org.pragmatica") && seen.put(value, true) == null) {
                            depths.put(value, depth + 1);
                            queue.add(value);
                        }
                    } catch (ReflectiveOperationException | RuntimeException _) {
                        // not readable: skip this edge
                    }
                }
            }
        }

        throw new AssertionError("no DrainProcedure reachable from node " + node.self().id());
    }

    private static NodeId nodeId(String id) {
        return NodeId.nodeId(id).unwrap();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
