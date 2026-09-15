// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;

import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Option;

import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;


/// #588 — a terminated worker must leave every operator-visible roster once membership has declared it
/// dead, not linger as an `UNKNOWN` ghost. A core node's death travels on `MembershipDecision.NodeRemoved`,
/// which the ClusterSync collector (`GET /api/v1/cluster/status` `nodes[]` is its pong roster) and the
/// transport topology (`GET /api/v1/status` `cluster.nodes[]`, discovery gossip, the dial set) both
/// consume to forget the node. A worker's death travels on the separate `WorkerLeaveDecision` channel
/// (#731), which reached only the deployment FSM — so a dead worker's last pong and its topology entry
/// were kept forever, and both status routes projected it as `derivedStatus: "UNKNOWN"` indefinitely.
///
/// The flow is the ticket's: a 3-core cluster with DEFAULT detection timeouts, one worker joins, is hard
/// killed (a terminated VM: connections drop, no leave), membership detects the death, and then every
/// roster the two status routes read from must drop it within one detection window. Presence BEFORE the
/// kill is asserted first so the post-kill absence cannot pass vacuously, and the FSM's own verdict is
/// awaited before the roster assertions so the test measures the roster lag, not detection.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TerminatedWorkerGhostTest {
    private static final Logger log = LoggerFactory.getLogger(TerminatedWorkerGhostTest.class);
    private static final int CORES = 3;
    private static final int BASE_PORT = 24800;
    private static final int BASE_MGMT_PORT = 24900;
    private static final int BASE_APP_HTTP_PORT = 25000;
    private static final Duration FORM_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration JOIN_TIMEOUT = Duration.ofSeconds(60);
    /// SWIM suspicion (10s) + NTT departure (15s), tripled for CI load — the same derivation
    /// `MembershipChaosCycleTest` uses for a hard-killed core.
    private static final Duration DETECTION_BUDGET = Duration.ofSeconds(75);
    /// Once the FSM has declared the worker dead, the rosters are updated by the same delta edge, so
    /// the lag is bounded by message routing; one detection window is a generous ceiling.
    private static final Duration ROSTER_BUDGET = Duration.ofSeconds(30);
    private static final Duration POLL = Duration.ofMillis(500);

    private final HttpOperations http = jdkHttpOperations();
    private EmberCluster cluster;

    @BeforeAll
    void setUp() {
        cluster = emberCluster(CORES, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "twg");
        LifecycleAwait.settled("cluster start in setUp()", cluster, cluster.start());
        await().atMost(FORM_TIMEOUT).pollInterval(POLL).until(() -> cluster.currentLeader()
                                                                           .isPresent());
        await().atMost(FORM_TIMEOUT)
             .pollInterval(POLL)
             .until(() -> leader().membershipFsm()
                                .coreCountedMembers()
                                .size() == CORES);
    }

    @AfterAll
    void tearDown() {
        Option.option(cluster).onPresent(c -> LifecycleAwait.bestEffort("cluster stop in tearDown()", c, c.stop()));
    }

    @Test
    void hardKilledWorker_leavesEveryStatusRoster_onceMembershipDeclaresItDead() {
        var workerId = LifecycleAwait.nodeSettled("addWorkerNode in hardKilledWorker_leavesEveryStatusRoster_onceMembershipDeclaresItDead()",
                                                  cluster,
                                                  cluster.addWorkerNode())
                                     .id();
        var worker = new NodeId(workerId);
        // Presence first — the positive control for every absence asserted below.
        await().atMost(JOIN_TIMEOUT)
             .pollInterval(POLL)
             .until(() -> pongRosterHas(worker)
                          && topologyHas(worker)
                          && statusRouteLists(workerId));
        log.info("TWG: worker {} present in pong roster, topology and /api/v1/cluster/status; leader={}",
                 workerId,
                 cluster.currentLeader().or("none"));
        LifecycleAwait.nodeSettled("hard kill of worker " + workerId, cluster, cluster.killNode(workerId, false));
        // Membership's own verdict: the leader's FSM no longer projects the worker as a live member.
        await().atMost(DETECTION_BUDGET).pollInterval(POLL).until(() -> !liveInFsm(worker));
        log.info("TWG: leader {} projects worker {} as {}",
                 leader().self().id(),
                 workerId,
                 leader().membershipFsm().memberStates().get(worker));
        awaitRostersForget(worker, workerId);
        // Logged before the assertions so a red run carries its own picture.
        log.info("TWG RESULT: pongRoster={} topology={} statusRoute={}",
                 pongRosterHas(worker),
                 topologyHas(worker),
                 statusRouteLists(workerId));
        assertThat(pongRosterHas(worker)).as("#588: a dead worker must leave the ClusterSync pong roster (the source of /api/v1/cluster/status nodes[])")
                  .isFalse();
        assertThat(topologyHas(worker)).as("#588: a dead worker must be pruned from the transport topology (the source of /api/v1/status cluster.nodes[])")
                  .isFalse();
        assertThat(statusRouteLists(workerId)).as("#588: GET /api/v1/cluster/status must not list a dead worker as an UNKNOWN ghost")
                  .isFalse();
    }

    /// Bounded wait; a timeout is not itself the failure — the assertions that follow name which
    /// roster still holds the ghost.
    private void awaitRostersForget(NodeId worker, String workerId) {
        try {
            await().atMost(ROSTER_BUDGET)
                 .pollInterval(POLL)
                 .until(() -> !pongRosterHas(worker)
                              && !topologyHas(worker)
                              && !statusRouteLists(workerId));
        } catch (ConditionTimeoutException timeout) {
            log.warn("TWG: rosters still hold worker {} {}s after membership declared it dead",
                     workerId,
                     ROSTER_BUDGET.toSeconds());
        }
    }

    private boolean liveInFsm(NodeId worker) {
        var state = leader().membershipFsm().memberStates().get(worker);

        return state != null && !"Dead".equals(state);
    }

    private boolean pongRosterHas(NodeId worker) {
        return leader().metricsCollector()
                     .allMetrics()
                     .containsKey(worker);
    }

    private boolean topologyHas(NodeId worker) {
        return leader().topologyManager()
                     .topology()
                     .contains(worker);
    }

    private boolean statusRouteLists(String workerId) {
        return cluster.getLeaderManagementPort()
                      .map(port -> get(port, "/api/v1/cluster/status"))
                      .map(body -> body.contains("\"" + workerId + "\""))
                      .or(false);
    }

    private AetherNode leader() {
        return cluster.currentLeader()
                      .flatMap(cluster::getNode)
                      .or(() -> cluster.allNodes()
                                       .getFirst());
    }

    private String get(int mgmtPort, String path) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + mgmtPort + path))
                                 .timeout(Duration.ofSeconds(10))
                                 .GET()
                                 .build();

        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or("");
    }
}
