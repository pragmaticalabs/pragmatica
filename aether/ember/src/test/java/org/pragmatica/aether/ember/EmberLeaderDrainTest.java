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
/// pins that the leader drain is REFUSED at the route and nothing is half-applied: the answer is the typed
/// `409` leader-cannot-be-drained refusal (asserted, status and body text), on the leader's port, on a follower's port, and on the NEW leader after a leader
/// change; and at no 100 ms sample over a window longer than the departure timeout is the leader DEPARTING on itself while
/// its own `DrainProcedure` is INACTIVE (`T3348` lines on stdout, one per observable change).
///
/// Why the refusal is pinned and not only the samples: the readiness view never carries the leader's own row (the view is
/// built from pongs and the leader never pings itself), so before the typed refusal the route answered `404 Node lifecycle
/// not found` for the leader. The latent half-apply is REAL: with a
/// route that treats self as READY (verifier mutant, v3356) the leader goes DEPARTING with an INACTIVE drain at 149 of 150
/// samples and returns to Member at the departure timeout. The leader check in front of the readiness lookup is the ONLY guard; a future fix that adds the leader's own
/// row to the view must ship together with a drain that runs on the leader, and must keep this test.
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
    private static final int LEADER_CHANGE_SIZE = 4;
    private static final String REFUSAL_BODY_FORMAT = "Cannot drain node %s: it is the current leader, and a leader is not drained. Move leadership first: stop this node's process from outside the cluster (the service manager or the container runtime) so the cluster elects another leader, then drain this node once it is no longer the leader. `aether nodes shutdown` is refused for the leader too.";
    private static final String SHUTDOWN_REFUSAL_PREFIX_FORMAT = "Cannot shut down node %s: it is the current leader, and a leader is not shut down through the API. Move leadership first: stop this node's process from outside the cluster (the service manager or the container runtime) so the cluster elects another leader;";
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
        start(CLUSTER_SIZE);
        var leaderId = cluster.currentLeader().unwrap();
        var follower = cluster.allNodes().stream().map(n -> n.self().id()).filter(id -> !id.equals(leaderId)).findFirst().orElseThrow();
        var drainee = cluster.getNode(follower).unwrap();
        var procedure = drainProcedureOf(drainee);

        awaitFollowersReady(cluster.getNode(leaderId).unwrap(), List.of(follower));

        var answer = postDrain(follower, cluster.getLeaderManagementPort().or(-1));

        System.out.println("T3348 control size=" + CLUSTER_SIZE + " route=" + answer);
        assertThat(answer.status()).as("control: the follower drain is admitted by the route (200), answered: " + answer).isEqualTo(200);

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

    /// The claim of #3348. The route REFUSES (409, typed leader-cannot-be-drained) and at no sample is the leader
    /// DEPARTING on itself while its own drain procedure is INACTIVE.
    @Test
    @Timeout(600)
    void leaderDrain_throughTheLeadersRoute_refusedAndNeverHalfApplies() {
        start(CLUSTER_SIZE);
        var leaderId = cluster.currentLeader().unwrap();
        var followers = followersOf(leaderId);

        awaitFollowersReady(cluster.getNode(leaderId).unwrap(), idsOf(followers));
        drainLeaderAndObserve(leaderId, cluster.getLeaderManagementPort().or(-1), followers, "leader");
    }

    /// The same drain posted to a FOLLOWER's management port: an operator may address any node.
    @Test
    @Timeout(600)
    void leaderDrain_throughAFollowersRoute_refusedAndNeverHalfApplies() {
        start(CLUSTER_SIZE);
        var leaderId = cluster.currentLeader().unwrap();
        var followers = followersOf(leaderId);
        var viaNode = followers.getFirst();
        var port = cluster.status().nodes().stream().filter(n -> n.id().equals(viaNode.self().id())).findFirst().orElseThrow().mgmtPort();

        awaitFollowersReady(cluster.getNode(leaderId).unwrap(), idsOf(followers));
        awaitFollowersReady(viaNode, idsOf(followers.stream().filter(n -> n != viaNode).toList()));
        drainLeaderAndObserve(leaderId, port, followers, "follower");
    }

    /// R-b (#3348): an API SHUTDOWN of the leader is refused with its own typed text and never half-applies, through the leader's route.
    @Test
    @Timeout(600)
    void leaderShutdown_throughTheLeadersRoute_refusedAndNeverHalfApplies() {
        start(CLUSTER_SIZE);
        var leaderId = cluster.currentLeader().unwrap();
        var followers = followersOf(leaderId);

        awaitFollowersReady(cluster.getNode(leaderId).unwrap(), idsOf(followers));
        refuseLeaderAndObserve("shutdown", leaderId, cluster.getLeaderManagementPort().or(-1), followers, "leader");
    }

    /// The same shutdown posted to a FOLLOWER's management port.
    @Test
    @Timeout(600)
    void leaderShutdown_throughAFollowersRoute_refusedAndNeverHalfApplies() {
        start(CLUSTER_SIZE);
        var leaderId = cluster.currentLeader().unwrap();
        var followers = followersOf(leaderId);
        var viaNode = followers.getFirst();
        var port = cluster.status().nodes().stream().filter(n -> n.id().equals(viaNode.self().id())).findFirst().orElseThrow().mgmtPort();

        awaitFollowersReady(cluster.getNode(leaderId).unwrap(), idsOf(followers));
        awaitFollowersReady(viaNode, idsOf(followers.stream().filter(n -> n != viaNode).toList()));
        refuseLeaderAndObserve("shutdown", leaderId, port, followers, "follower");
    }

    /// Control for R-b: a shutdown of a FOLLOWER still works (200, the node exits).
    @Test
    @Timeout(600)
    void followerShutdown_control_admittedAndNodeExits() {
        start(CLUSTER_SIZE);
        var leaderId = cluster.currentLeader().unwrap();
        var follower = cluster.allNodes().stream().map(n -> n.self().id()).filter(id -> !id.equals(leaderId)).findFirst().orElseThrow();

        awaitFollowersReady(cluster.getNode(leaderId).unwrap(), List.of(follower));

        var answer = post("shutdown", follower, cluster.getLeaderManagementPort().or(-1));
        var deadline = System.currentTimeMillis() + OBSERVE_WINDOW_MS;

        while (System.currentTimeMillis() < deadline && cluster.getNode(follower).isPresent()) {
            sleepQuietly(SAMPLE_MS);
        }

        System.out.println("T3348 shutdown control route=" + answer + " exited=" + cluster.getNode(follower).isEmpty());
        assertThat(answer.status()).as("control: the follower shutdown is admitted (200), answered: " + answer).isEqualTo(200);
        assertThat(cluster.getNode(follower).isEmpty()).as("control: the shut-down follower exited").isTrue();
    }

    /// After a leader change the NEW leader is refused the same way: its readiness view omits itself too.
    @Test
    @Timeout(900)
    void leaderDrain_afterLeaderChange_newLeaderRefusedAndNeverHalfApplies() {
        start(LEADER_CHANGE_SIZE);
        var firstLeader = cluster.currentLeader().unwrap();

        assertThat(cluster.killNode(firstLeader, false).await(STOP_BOUND).isSuccess()).as("the first leader was killed").isTrue();

        var newLeader = awaitNewLeader(firstLeader);
        var followers = followersOf(newLeader);

        awaitFollowersReady(cluster.getNode(newLeader).unwrap(), idsOf(followers));
        drainLeaderAndObserve(newLeader, cluster.getLeaderManagementPort().or(-1), followers, "new-leader");
    }

    private List<AetherNode> followersOf(String leaderId) {
        return cluster.allNodes().stream().filter(n -> !n.self().id().equals(leaderId)).toList();
    }

    private static List<String> idsOf(List<AetherNode> nodes) {
        return nodes.stream().map(n -> n.self().id()).toList();
    }

    private String awaitNewLeader(String previous) {
        var deadline = System.currentTimeMillis() + LEADER_BUDGET_MS;

        while (System.currentTimeMillis() < deadline
               && !cluster.currentLeader().filter(id -> !id.equals(previous) && cluster.getNode(id).isPresent()).isPresent()) {
            sleepQuietly(500L);
        }

        var found = cluster.currentLeader().filter(id -> !id.equals(previous));

        assertThat(found.isPresent()).as("a new leader after " + previous + " was killed").isTrue();
        return found.unwrap();
    }

    private void drainLeaderAndObserve(String leaderId, int port, List<AetherNode> followers, String via) {
        refuseLeaderAndObserve("drain", leaderId, port, followers, via);
    }

    private void refuseLeaderAndObserve(String operation, String leaderId, int port, List<AetherNode> followers, String via) {
        var leader = cluster.getNode(leaderId).unwrap();
        var leaderNodeId = nodeId(leaderId);
        var procedure = drainProcedureOf(leader);
        var answer = post(operation, leaderId, port);

        System.out.println("T3348 leader size=" + cluster.allNodes().size() + " via=" + via + " port=" + port + " leader=" + leaderId + " route=" + answer);

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
        assertThat(answer.status()).as("the route answered with an HTTP status (a connection failure is -1): " + answer).isEqualTo(409);
        assertThat(answer.body()).as("the refusal is the typed leader refusal for " + operation + ": " + answer)
                                 .contains((operation.equals("drain") ? REFUSAL_BODY_FORMAT : SHUTDOWN_REFUSAL_PREFIX_FORMAT).formatted(leaderId));
    }

    private void start(int size) {
        cluster = EmberTestPorts.startedCluster(PORTS,
                                                basePort -> emberCluster(size,
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

    /// A follower target is answered 404 "Node lifecycle not found" until the receiving node's readiness view reports the target READY.
    /// Wait (bounded; failing with a message) for what the drain needs: every given node READY in the viewer's own view.
    /// The leader's own row is NOT waited for: it never appears (the view is built from pongs and the leader never pongs itself).
    private static void awaitFollowersReady(AetherNode viewer, List<String> nodeIds) {
        BooleanSupplier ready = () -> nodeIds.stream()
                                             .allMatch(id -> viewer.metricsCollector().reportedStates().get(nodeId(id)) == NodeReportedState.READY);
        var deadline = System.currentTimeMillis() + READY_BUDGET_MS;

        while (System.currentTimeMillis() < deadline && !ready.getAsBoolean()) {
            sleepQuietly(500L);
        }

        System.out.println("T3348 readiness view of " + viewer.self().id() + ": " + viewer.metricsCollector().reportedStates());
        assertThat(ready.getAsBoolean()).as(nodeIds + " are READY in the view of " + viewer.self().id() + " within " + READY_BUDGET_MS + " ms").isTrue();
    }

    /// The HTTP answer of a drain POST; status -1 means no answer was received (connection failure).
    private record Answer(int status, String body) {
        static Answer answer(int status, String body) {
            return new Answer(status, body);
        }

        @Override
        public String toString() {
            return status + " " + body;
        }
    }

    private Answer postDrain(String nodeId, int port) {
        return post("drain", nodeId, port);
    }

    private Answer post(String operation, String nodeId, int port) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/nodes/" + operation + "/" + nodeId))
                                 .timeout(Duration.ofSeconds(30))
                                 .header("Content-Type", "application/json")
                                 .POST(HttpRequest.BodyPublishers.noBody())
                                 .build();

        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            return Answer.answer(response.statusCode(), response.body());
        } catch (Exception e) {
            return Answer.answer(-1, e.toString());
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
