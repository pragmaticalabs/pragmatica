// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;

/// E-01 (phase-3 design 9.3): deploy one slice with three instances on three cores under FSM2, and read the result **from the KV**, not from a
/// status route: the blueprint's outcome is SUCCEEDED and stays so, the apply record has been completed, the leader holds three ACTIVE goals, and
/// each core holds one current ACTIVE row of its own.
///
/// **Leader rounds are applies** (P3-G ruling Q1): spec 7 counts a round as one `apply(List)`, so the leader is read from
/// `applies_total{fsm=cluster}`, one per apply the interpreter sent (O4 proxy: the design's O4 counts committed batches, and one apply stands for
/// one only while NT-90 stays unverified). Its transactions (`submissions_total{fsm=cluster,outcome=submitted}`) are read beside it. **Node rounds
/// are counted at submission** (`submissions_total{fsm=node,outcome=submitted}`, one per write, and each write is one apply), never at settlement:
/// a clean deploy's row echo clears a node's write before its settlement is stepped, so an `accepted` count omits most node rounds by construction
/// (v-2204 F1). The leader's budget is 7.1 as restated by P3-G (ruling Q2, Q3): **4 applies plus drain splits**. The floor of 4 is causal (target,
/// LOAD, ACTIVATE, SUCCEEDED each derive from the previous stage's committed rows); a split is a goal stage whose rows land in more than one
/// drain. Splits are timing-dependent: they are measured (O4, P3-H), not bounded. The transactions are exactly `2 + 2·S·I`. Measured at the flip:
/// 4 applies in 4 of 11 runs, 5 in 7 (a later run read 7); 8 transactions in every run. The nodes' measured count meets 7.1 (4 per instance), so 7.1 is their enabled bound.
/// The design's O4 instrument (distinct notification revisions over the union key families, 9.5 criterion 4) is a phase-4 entry criterion and is not
/// built here.
///
/// One run per invocation: the evidence is the first N runs at a SHA, all reported.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fsm2DeployE01Test {
    private static final int BASE_PORT = 28000;
    private static final int BASE_MGMT_PORT = 28100;
    private static final int BASE_APP_HTTP_PORT = 28200;
    private static final int NODES = 3;
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration DEPLOY_TIMEOUT = Duration.ofSeconds(90);
    private static final String BLUEPRINT_ID = "forge.test:e01-deploy:1.0.0";
    private static final int SLICES = 1;
    /// Design 7.1 as restated by P3-G: the leader's clean deploy takes 4 applies plus drain splits, each node 4 rounds per instance.
    private static final double SPEC_LEADER_FLOOR = 4;
    private static final double SPEC_LEADER_TRANSACTIONS = 2 + 2 * SLICES * NODES;
    private static final double SPEC_NODE_ROUNDS = NODES * 4;

    private static final String EXPECTED_WARNS = "/fsm2/expected-warns-e01.txt";

    private final HttpOperations http = jdkHttpOperations();
    private EmberCluster cluster;
    private Fsm2Sensors sensors;

    @BeforeAll
    void setUp() {
        sensors = Fsm2Sensors.capture();
        cluster = emberCluster(NODES, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "e01");
        cluster.withDeploymentEngine(DeploymentEngine.FSM2);
        LifecycleAwait.settled("cluster start in setUp()", cluster, cluster.start());
        await().atMost(WAIT_TIMEOUT).pollInterval(Duration.ofMillis(500)).until(() -> cluster.currentLeader().isPresent());
        await().atMost(WAIT_TIMEOUT).pollInterval(Duration.ofMillis(500)).until(this::allNodesHealthy);
    }

    @AfterAll
    void tearDown() {
        sensors.close();
        if (cluster != null) {
            LifecycleAwait.bestEffort("cluster stop in tearDown()", cluster, cluster.stop());
        }
    }

    @Test
    void deployOneSliceByThree_succeedsOnce_threeGoals_threeCurrentActiveRows() {
        var artifact = Artifact.artifact(TestArtifacts.ECHO_SLICE).unwrap();
        var id = BlueprintId.blueprintId(BLUEPRINT_ID).unwrap();

        var version = deployOnce(artifact, id);

        var kv = leader().kvStore();

        assertThat(kv.get(AetherKey.DeploymentApplyKey.deploymentApplyKey(id)).isEmpty()).as("SUCCEEDED completes the apply record (R-4.1.5)").isTrue();
        assertThat(activeGoals(artifact)).as("goals ACTIVE x 3").isEqualTo(NODES);
        assertThat(activeRows(artifact)).as("3 ACTIVE rows").isEqualTo(NODES);
        cluster.allNodes().forEach(node -> assertThat(currentRow(node, artifact)).as("a current row of " + node.self().id()).isTrue());
        var leaderApplies = leaderApplies();
        var leaderTransactions = submitted("cluster");
        var nodeRounds = submitted("node");

        System.out.printf("E01 outcomeVersion=%d leaderApplies=%.0f (spec %.0f + drain splits, measured) leaderTransactions=%.0f (spec %.0f) nodeRounds=%.0f (spec %.0f)%n",
                          version, leaderApplies, SPEC_LEADER_FLOOR, leaderTransactions, SPEC_LEADER_TRANSACTIONS, nodeRounds, SPEC_NODE_ROUNDS);
        assertThat(nodeRounds).as("design 7.1: 4 rounds per instance, counted at submission").isLessThanOrEqualTo(SPEC_NODE_ROUNDS);
        assertThat(sensorViolations()).as("E-01 against fsm2/expected-warns-e01.txt and the O6 metrics (no fault: silent)").isEmpty();
    }

    /// 7.1 as restated by P3-G, the leader's assertion: exactly `2 + 2·S·I` transactions (a duplicate target batch makes it one more), and
    /// at least the 4 causal applies. Drain splits are timing-dependent and measured (O4, P3-H), not bounded here. Enabled at P3-G on 11 of 11 runs.
    @Test
    void leaderRounds_withinTheSpecBudget() {
        deployOnce(Artifact.artifact(TestArtifacts.ECHO_SLICE).unwrap(), BlueprintId.blueprintId(BLUEPRINT_ID).unwrap());

        assertThat(submitted("cluster")).as("design 7.1: leader, clean deploy, 2 + 2*S*I transactions").isEqualTo(SPEC_LEADER_TRANSACTIONS);
        assertThat(leaderApplies()).as("design 7.1: leader, clean deploy, at least the 4 causal stages").isGreaterThanOrEqualTo(SPEC_LEADER_FLOOR);
    }

    private long deployedVersion = -1;

    /// Deploys once per class and waits for the stable SUCCEEDED; returns the outcome version.
    private long deployOnce(Artifact artifact, BlueprintId id) {
        if (deployedVersion < 0) {
            deployBlueprint();
            await().atMost(DEPLOY_TIMEOUT).pollInterval(Duration.ofMillis(250)).until(() -> succeeded(id));
            await().atMost(DEPLOY_TIMEOUT).pollInterval(Duration.ofMillis(250)).until(() -> activeRows(artifact) == NODES);
            var version = outcomeVersion(id);

            await().during(Duration.ofSeconds(8)).atMost(Duration.ofSeconds(12)).until(() -> succeeded(id) && outcomeVersion(id) == version);
            deployedVersion = version;
        }

        return deployedVersion;
    }

    /// Design 9.5 O6: every WARN of the deployment FSM loggers is on the checked-in list, each entry's occurrences are in its range (summed over the
    /// nodes), and no submission is lost or settled unknown and no input is discarded as closed while the cluster runs.
    private List<String> sensorViolations() {
        var violations = new ArrayList<String>();
        var expectations = Fsm2Sensors.expectations(EXPECTED_WARNS);
        var warnLines = sensors.warnLines();

        warnLines.forEach(line -> System.out.println("E01 warn: " + line));
        warnLines.stream()
                 .filter(line -> expectations.stream().filter(e -> e.kind().equals("warn")).noneMatch(e -> e.matches(line)))
                 .forEach(line -> violations.add("WARN not on the expected list: " + line));
        for (var entry : expectations) {
            var count = switch (entry.kind()) {
                case "warn" -> warnLines.stream().filter(entry::matches).count();
                case "line" -> cluster.allNodes().stream().mapToLong(node -> sensors.sensorLines(node.self().id(), entry.name()).size()).sum();
                default -> (long) cluster.allNodes().stream().mapToDouble(node -> Fsm2Sensors.armCount(node, entry.name())).sum();
            };

            if (!entry.admits(count)) {
                violations.add("%s %s: %d, expected %d..%d".formatted(entry.kind(), entry.name(), count, entry.min(), entry.max()));
            }
        }
        for (var arm : List.of("lost", "settled_unknown")) {
            var count = cluster.allNodes().stream().mapToDouble(node -> Fsm2Sensors.armCount(node, arm)).sum();

            if (count != 0.0) {
                violations.add("submissions_total{outcome=%s}: %.0f, expected 0".formatted(arm, count));
            }
        }
        var closed = cluster.allNodes().stream().mapToDouble(this::closedInputs).sum();

        if (closed != 0.0) {
            violations.add("inputs_total{result=closed}: %.0f, expected 0 outside stop".formatted(closed));
        }

        return violations;
    }

    private double closedInputs(AetherNode node) {
        return node.deploymentWiring()
                   .meters()
                   .find("aether_deployment_fsm_inputs_total")
                   .tags("result", "closed")
                   .counters()
                   .stream()
                   .mapToDouble(counter -> counter.count())
                   .sum();
    }

    private boolean succeeded(BlueprintId id) {
        return leader().blueprintService().outcome(id).map(outcome -> outcome.status() == DeploymentOutcomeStatus.SUCCEEDED).or(false);
    }

    private long outcomeVersion(BlueprintId id) {
        return leader().blueprintService().outcome(id).map(outcome -> outcome.outcomeVersion()).or(-1L);
    }

    private long activeRows(Artifact artifact) {
        return cluster.allNodes().stream().filter(node -> row(node, artifact).map(row -> row.state() == SliceObservedState.ACTIVE).or(false)).count();
    }

    private long activeGoals(Artifact artifact) {
        return cluster.allNodes()
                      .stream()
                      .filter(node -> leader().kvStore()
                                              .getTyped(AetherKey.SliceDesiredKey.sliceDesiredKey(node.self(), artifact), SliceDesiredValue.class)
                                              .map(goal -> goal.goal() == SliceGoal.ACTIVE)
                                              .or(false))
                      .count();
    }

    private Option<SliceObservedValue> row(AetherNode node, Artifact artifact) {
        return leader().kvStore().getTyped(AetherKey.SliceObservedKey.sliceObservedKey(node.self(), artifact), SliceObservedValue.class);
    }

    /// A row is current when it names a known process: the boot token of the node's own process is non-zero (C8).
    private boolean currentRow(AetherNode node, Artifact artifact) {
        return row(node, artifact).map(row -> row.incarnation().bootToken() != 0L).or(false);
    }

    private double submitted(String fsm) {
        return cluster.allNodes().stream().mapToDouble(node -> Fsm2Sensors.armCount(node, fsm, "submitted")).sum();
    }

    /// The leader's `apply(List)` calls, summed over the nodes (only the leader's cluster FSM submits).
    private double leaderApplies() {
        return cluster.allNodes()
                      .stream()
                      .mapToDouble(node -> node.deploymentWiring()
                                               .meters()
                                               .find("aether_deployment_fsm_applies_total")
                                               .tags("fsm", "cluster")
                                               .counters()
                                               .stream()
                                               .mapToDouble(counter -> counter.count())
                                               .sum())
                      .sum();
    }

    private AetherNode leader() {
        return cluster.currentLeader().flatMap(cluster::getNode).or(() -> cluster.allNodes().getFirst());
    }

    private boolean allNodesHealthy() {
        return cluster.status().nodes().stream().allMatch(node -> httpGet(node.mgmtPort(), "/api/v1/health").contains("\"quorum\":true"));
    }

    private void deployBlueprint() {
        var blueprint = """
            id = "%s"

            [[slices]]
            artifact = "%s"
            instances = %d
            """.formatted(BLUEPRINT_ID, TestArtifacts.ECHO_SLICE, NODES);
        var leaderPort = cluster.getLeaderManagementPort().or(cluster.status().nodes().getFirst().mgmtPort());
        var response = send(HttpRequest.newBuilder()
                                       .uri(URI.create("http://localhost:" + leaderPort + "/api/v1/blueprints"))
                                       .header("Content-Type", "application/toml")
                                       .POST(HttpRequest.BodyPublishers.ofString(blueprint))
                                       .timeout(Duration.ofSeconds(10))
                                       .build());

        assertThat(response).describedAs("SETUP: blueprint deployment").doesNotContain("\"error\"").contains("\"status\":\"applied\"");
    }

    private String httpGet(int port, String path) {
        return send(HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path)).GET().timeout(Duration.ofSeconds(10)).build());
    }

    private String send(HttpRequest request) {
        return http.sendString(request).await().map(HttpResult::body).or("{\"error\":\"request failed\"}");
    }
}
