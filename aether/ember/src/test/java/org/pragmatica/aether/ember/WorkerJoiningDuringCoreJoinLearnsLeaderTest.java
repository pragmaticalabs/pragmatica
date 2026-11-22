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
import java.util.IdentityHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.deployment.membership.ntt.DrainProcedure;
import org.pragmatica.aether.metrics.NodeReportedState;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.testing.PollUntil;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #3414. A worker that joins while a core is still joining never learns the leader: its `leader()` view stays empty, and the
/// worker then treats every leader ping as not authoritative (the ping authority check compares the sender against that view),
/// so a commanded drain, and every other leader command on the same path, is silently ignored.
///
/// Sequence: five seed cores; add one core (surplus over the configured five, so it is retired later); add one worker as soon
/// as the core's start resolves; wait until the worker is ready; then, within a bounded wait, the worker's leader view must
/// equal the cluster's leader, and the worker must accept a commanded drain. The control adds the worker with no core joining.
@PortBudget
class WorkerJoiningDuringCoreJoinLearnsLeaderTest {
    private static final int SEEDS = 5;
    private static final int SLOTS = 2 * SEEDS + 4;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_OFFSET = 80;
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(180).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);
    private static final long READY_BOUND_MS = 120_000L;
    private static final long LEADER_VIEW_BOUND_MS = 30_000L;
    private static final long DRAIN_BOUND_MS = 10_000L;
    private static final EmberTestPorts.Block PORTS = new EmberTestPorts.Block(EmberTestPorts.POOL_FIRST,
                                                                               EmberTestPorts.POOL_LAST,
                                                                               EmberTestPorts.POOL_STEP,
                                                                               SLOTS,
                                                                               MGMT_OFFSET,
                                                                               APP_OFFSET);

    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.stop().await(STOP_BOUND);
        }
    }

    /// The worker learns the leader although its leader state machine was Dormant when the committed leader arrived: entering
    /// Passive reads the committed leader key. Blast-radius check: with the leader known, the leader's pings are authoritative
    /// on the worker, so a commanded drain is accepted and runs (its drain procedure leaves INACTIVE within the bound).
    @Test
    @Timeout(600)
    void workerLeaderView_workerAddedWhileCoreJoins_equalsClusterLeader() {
        startSettledCluster();
        cluster.addNode().await(START_BOUND).unwrap();

        var worker = readyWorker();

        awaitCondition("the worker's leader view equals the cluster's leader", LEADER_VIEW_BOUND_MS, () -> knowsLeader(worker), () -> views(worker));

        var leaderNode = cluster.getNode(cluster.currentLeader().unwrap()).unwrap();

        awaitCondition("the leader sees the worker READY (the drain route admits only a READY node)", READY_BOUND_MS,
                       () -> leaderNode.metricsCollector().reportedStates().get(worker.self()) == NodeReportedState.READY,
                       () -> "leader view " + leaderNode.metricsCollector().reportedStates() + "; " + views(worker));

        var procedure = drainProcedureOf(worker);

        assertThat(procedure.state()).as("instrument: the worker's drain procedure is INACTIVE before the drain")
                                     .isEqualTo(DrainProcedure.DrainState.INACTIVE);

        var answer = postDrain(worker.self().id(), cluster.getLeaderManagementPort().or(-1));

        assertThat(answer.status()).as("the worker drain is admitted by the route, answered: " + answer).isEqualTo(200);
        awaitCondition("the worker's drain procedure leaves INACTIVE", DRAIN_BOUND_MS,
                       () -> procedure.state() != DrainProcedure.DrainState.INACTIVE,
                       () -> "drain procedure " + procedure.state() + "; " + views(worker));
    }

    @Test
    @Timeout(600)
    void workerLeaderView_noCoreJoining_equalsClusterLeader() {
        startSettledCluster();

        var worker = readyWorker();

        awaitCondition("the worker's leader view equals the cluster's leader", LEADER_VIEW_BOUND_MS, () -> knowsLeader(worker), () -> views(worker));
    }

    private void startSettledCluster() {
        cluster = EmberTestPorts.startedCluster(PORTS, WorkerJoiningDuringCoreJoinLearnsLeaderTest::seedCluster, START_BOUND);
        awaitCondition("a leader is elected", READY_BOUND_MS, () -> cluster.currentLeader().isPresent());
        awaitCondition("all seeds ready", READY_BOUND_MS, () -> cluster.allNodes().stream().allMatch(AetherNode::isReady));
    }

    private static EmberCluster seedCluster(int base) {
        var built = emberCluster(SEEDS, base, base + MGMT_OFFSET, base + APP_OFFSET, "wjl");

        built.withAdditionalNodeSlots(2).unwrap();

        return built;
    }

    private AetherNode readyWorker() {
        var worker = cluster.getNode(cluster.addWorkerNode().await(START_BOUND).unwrap().id()).unwrap();

        awaitCondition("the worker becomes ready", READY_BOUND_MS, worker::isReady);

        return worker;
    }

    private boolean knowsLeader(AetherNode worker) {
        var view = worker.leader().map(NodeId::id);
        var leader = cluster.currentLeader();

        return view.isPresent() && view.equals(leader);
    }

    private String views(AetherNode worker) {
        return "worker " + worker.self().id() + " sees " + worker.leader() + ", cluster leader " + cluster.currentLeader()
               + ", nodes " + cluster.nodeCount();
    }

    private void awaitCondition(String what, long boundMs, BooleanSupplier condition) {
        awaitCondition(what, boundMs, condition, () -> "");
    }

    private void awaitCondition(String what, long boundMs, BooleanSupplier condition, Supplier<String> detail) {
        var report = PollUntil.pollUntil(condition::getAsBoolean,
                                         Boolean::booleanValue,
                                         Duration.ofMillis(boundMs),
                                         POLL_INTERVAL);

        assertThat(report.met()).as(() -> report.describe(what) + "; " + detail.get())
                                .isTrue();
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

    private static Answer postDrain(String nodeId, int port) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/nodes/drain/" + nodeId))
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

    /// Breadth-first walk over the node's object graph (project classes only) for its one DrainProcedure, which the
    /// `AetherNode` interface does not expose (same walk as `EmberLeaderDrainTest`).
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
}
