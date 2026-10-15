// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;

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
/// each core holds one current ACTIVE row of its own. The write rounds the FSMs themselves took (`submissions_total`, accepted) are counted and
/// bounded by the spec's budget (7.1): the leader's clean deploy of 3 to 4 rounds plus each node's 4, plus the publish. The design's O4
/// instrument (distinct notification revisions over the union key families, 9.5 criterion 4) is a phase-4 entry criterion and is not built here
/// `[guess: the FSMs' own accepted submissions are the rounds E-01 can bound without it]`.
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
    /// Leader 4 (3 to 4 typical) + three nodes x 4 + the publish 1 (7.1); a bound with no slack beyond the spec's own "typical".
    private static final double ROUNDS_BOUND = 4 + NODES * 4;

    private final HttpOperations http = jdkHttpOperations();
    private EmberCluster cluster;

    @BeforeAll
    void setUp() {
        cluster = emberCluster(NODES, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "e01");
        cluster.withDeploymentEngine(DeploymentEngine.FSM2);
        LifecycleAwait.settled("cluster start in setUp()", cluster, cluster.start());
        await().atMost(WAIT_TIMEOUT).pollInterval(Duration.ofMillis(500)).until(() -> cluster.currentLeader().isPresent());
        await().atMost(WAIT_TIMEOUT).pollInterval(Duration.ofMillis(500)).until(this::allNodesHealthy);
    }

    @AfterAll
    void tearDown() {
        if (cluster != null) {
            LifecycleAwait.bestEffort("cluster stop in tearDown()", cluster, cluster.stop());
        }
    }

    @Test
    void deployOneSliceByThree_succeedsOnce_threeGoals_threeCurrentActiveRows() {
        var artifact = Artifact.artifact(TestArtifacts.ECHO_SLICE).unwrap();
        var id = BlueprintId.blueprintId(BLUEPRINT_ID).unwrap();

        deployBlueprint();
        await().atMost(DEPLOY_TIMEOUT).pollInterval(Duration.ofMillis(250)).until(() -> succeeded(id));
        await().atMost(DEPLOY_TIMEOUT).pollInterval(Duration.ofMillis(250)).until(() -> activeRows(artifact) == NODES);
        var version = outcomeVersion(id);

        await().during(Duration.ofSeconds(8)).atMost(Duration.ofSeconds(12)).until(() -> succeeded(id) && outcomeVersion(id) == version);

        var kv = leader().kvStore();

        assertThat(kv.get(AetherKey.DeploymentApplyKey.deploymentApplyKey(id)).isEmpty()).as("SUCCEEDED completes the apply record (R-4.1.5)").isTrue();
        assertThat(activeGoals(artifact)).as("goals ACTIVE x 3").isEqualTo(NODES);
        assertThat(activeRows(artifact)).as("3 ACTIVE rows").isEqualTo(NODES);
        cluster.allNodes().forEach(node -> assertThat(currentRow(node, artifact)).as("a current row of " + node.self().id()).isTrue());
        var rounds = acceptedRounds();

        System.out.printf("E01 outcomeVersion=%d acceptedRounds=%.0f (bound %.0f)%n", version, rounds, ROUNDS_BOUND);
        assertThat(rounds).as("FSM2 write rounds, spec 7.1").isLessThanOrEqualTo(ROUNDS_BOUND);
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

    private double acceptedRounds() {
        return cluster.allNodes()
                      .stream()
                      .mapToDouble(node -> node.deploymentWiring()
                                               .meters()
                                               .find("aether_deployment_fsm_submissions_total")
                                               .tags("outcome", "accepted")
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
