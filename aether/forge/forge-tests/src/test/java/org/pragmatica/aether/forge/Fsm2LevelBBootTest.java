// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterState;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeStatus;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;

/// Level B (phase-3 design 9.2, review M4): FSM2 nodes are booted, a slice is deployed, and **every input variant a checked-in list fixed before the
/// implementation** was stepped: `aether_deployment_fsm_inputs_total{fsm, input, result="applied"} > 0` on at least one node (a line marked `any` in the
/// list accepts any result label: that input can only arrive here in a state that drops it, and the count still proves the wire). The metric is emitted
/// per input by the FSMs themselves, so a wire that is dead (a route nobody registered, a driver nobody started) shows as a missing line, and it is
/// the production tripwire for the same thing. The list is `aether/node/src/test/resources/fsm2/levelb-inputs.txt`; an input missing from a run is
/// printed with the counts of all the others.
///
/// The scenario is the one T and E-01 walk (deploy, to SUCCEEDED and all ACTIVE). The list says which inputs that reaches and which it does not
/// (quorum loss, removals, undeploy are outside it, and listed there as not required); a scenario the list does not name is not asserted.
/// This test lives in `forge-tests` rather than `aether/node` because only here is a real slice fixture available to deploy.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fsm2LevelBBootTest {
    private static final int BASE_PORT = 28500;
    private static final int BASE_MGMT_PORT = 28600;
    private static final int BASE_APP_HTTP_PORT = 28700;
    private static final int NODES = 3;
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration DEPLOY_TIMEOUT = Duration.ofSeconds(90);
    private static final String BLUEPRINT_ID = "forge.test:levelb-deploy:1.0.0";
    private static final Path INPUT_LIST = Path.of("..", "..", "node", "src", "test", "resources", "fsm2", "levelb-inputs.txt");

    private final HttpOperations http = jdkHttpOperations();
    private EmberCluster cluster;

    @BeforeAll
    void setUp() {
        cluster = emberCluster(NODES, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "lvb");
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
    void everyInputOnTheCheckedInList_wasStepped_atLeastOnce() throws IOException {
        var id = BlueprintId.blueprintId(BLUEPRINT_ID).unwrap();
        var artifact = Artifact.artifact(TestArtifacts.ECHO_SLICE).unwrap();

        deployBlueprint();
        await().atMost(DEPLOY_TIMEOUT).pollInterval(Duration.ofMillis(250)).until(() -> leader().blueprintService()
                                                                                                  .outcome(id)
                                                                                                  .map(outcome -> outcome.status() == DeploymentOutcomeStatus.SUCCEEDED)
                                                                                                  .or(false));
        await().atMost(DEPLOY_TIMEOUT).pollInterval(Duration.ofMillis(250)).until(() -> cluster.allNodes().stream().allMatch(node -> node.invocationHandler().localSlice(artifact).isPresent()));
        // the leader's reconcile and its timers need a moment after SUCCEEDED
        await().pollDelay(Duration.ofSeconds(8)).atMost(Duration.ofSeconds(12)).until(() -> true);

        assertThat(leader().kvStore()
                           .getTyped(AetherKey.DeploymentEngineKey.deploymentEngineKey(), AetherValue.DeploymentEngineValue.class)
                           .map(AetherValue.DeploymentEngineValue::engine)).as("B20: the lineage mint wrote the FSM2 marker").isEqualTo(Option.some(DeploymentEngine.FSM2));
        assertThat(httpGet(cluster.getLeaderManagementPort().or(cluster.status().nodes().getFirst().mgmtPort()), "/api/v1/metrics/prometheus")).as("the FSM metrics are scraped from the management registry").contains("aether_deployment_fsm_inputs_total");
        var clusterModel = ((ClusterState.Active) leader().deploymentWiring().system().unwrap().clusterInterpreter().state()).model();

        assertThat(clusterModel.seams().coreView().isPresent()).as("W-33: the leader's cluster FSM was given a core view").isTrue();
        assertThat(clusterModel.seams().localAlive().isPresent()).as("and the local liveness").isTrue();

        var required = requiredInputs();
        var missing = new ArrayList<String>();
        var table = new TreeMap<String, Double>();

        for (var entry : required) {
            var applied = count(entry.fsm(), entry.input(), entry.anyResult());

            table.put(entry.fsm() + " " + entry.input(), applied);
            if (applied <= 0) {
                missing.add(entry.fsm() + " " + entry.input());
            }
        }
        System.out.println("LEVELB applied counts: " + table);

        assertThat(required).as("the checked-in list was read").hasSizeGreaterThanOrEqualTo(15);
        assertThat(missing).as("inputs on the list that no node stepped (a dead wire); counts " + table).isEmpty();
    }

    private record Entry(String fsm, String input, boolean anyResult) {}

    private static List<Entry> requiredInputs() throws IOException {
        if (!Files.exists(INPUT_LIST)) {
            throw new AssertionError("SETUP: the checked-in input list is missing at " + INPUT_LIST.toAbsolutePath());
        }

        return Files.readAllLines(INPUT_LIST, StandardCharsets.UTF_8)
                    .stream()
                    .filter(line -> !line.startsWith("#") && !line.isBlank())
                    .map(line -> line.split("\\s+"))
                    .map(parts -> new Entry(parts[0], parts[1], parts.length > 2 && parts[2].equals("any")))
                    .toList();
    }

    private double count(String fsm, String input, boolean anyResult) {
        return cluster.allNodes()
                      .stream()
                      .mapToDouble(node -> node.deploymentWiring()
                                               .meters()
                                               .find("aether_deployment_fsm_inputs_total")
                                               .tags("fsm", fsm, "input", input)
                                               .counters()
                                               .stream()
                                               .filter(counter -> anyResult || "applied".equals(counter.getId().getTag("result")))
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
