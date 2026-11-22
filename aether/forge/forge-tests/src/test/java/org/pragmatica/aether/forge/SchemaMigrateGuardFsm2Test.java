// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;

/// #3411: under FSM2 (the default engine) the `/schema/migrate` re-arm of a COMPLETED record must be refused while slices of the record's
/// owning blueprint are serving (#760 guard). The guard used to count `SliceNodeKey` rows, which no engine writes, so it
/// counted 0 and accepted the re-arm. Here a slice is brought to ACTIVE on three cores (FSM2 commits one `SliceObservedKey` row per core), a
/// COMPLETED record owned by its blueprint is committed, and the re-arm is sent to every node: each must answer the typed refusal and the record
/// must stay COMPLETED.
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SchemaMigrateGuardFsm2Test {
    /// Where the probe for a free block starts (EquivalencePorts bind-checks every port the cluster will take).
    private static final int FIRST_BLOCK = 33000;
    private static final int NODES = 3;
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration DEPLOY_TIMEOUT = Duration.ofSeconds(90);
    private static final TimeSpan REQUEST = TimeSpan.timeSpan(10).seconds();
    private static final String BLUEPRINT_ID = "forge.test:schema-guard:1.0.0";
    private static final String DATASOURCE = "guardds";
    private static final String REFUSAL = "is already COMPLETED and serving";

    private final HttpOperations http = jdkHttpOperations();
    private EmberCluster cluster;

    @BeforeAll
    void setUp() {
        var base = EquivalencePorts.freeBase(FIRST_BLOCK, NODES);

        cluster = emberCluster(NODES, base, base + EquivalencePorts.OFFSETS[1], base + EquivalencePorts.OFFSETS[2], "sg3411");
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
    void migrate_completedSchemaWithServingFsm2Slices_refusedOnEveryNode() {
        var artifact = Artifact.artifact(TestArtifacts.ECHO_SLICE).unwrap();
        var owner = BlueprintId.blueprintId(BLUEPRINT_ID).unwrap();

        deployBlueprint();
        await().atMost(DEPLOY_TIMEOUT).pollInterval(Duration.ofMillis(250)).until(() -> activeRows(artifact) == NODES);
        commitCompletedSchema(owner);

        for (var node : cluster.status().nodes()) {
            var response = post(node.mgmtPort(), "/api/v1/schema/migrate/" + DATASOURCE);
            assertThat(response).as("node %s: the re-arm of a COMPLETED schema serving %d ACTIVE slices", node.id(), NODES)
                                .contains(REFUSAL);
        }
        assertThat(schemaStatus()).as("a refused re-arm leaves the record COMPLETED").isEqualTo(SchemaStatus.COMPLETED);
    }

    private void commitCompletedSchema(BlueprintId owner) {
        var record = SchemaVersionValue.schemaVersionValue(DATASOURCE, 1, "V1__init", SchemaStatus.COMPLETED, TestArtifacts.ECHO_SLICE, owner);
        var put = new KVCommand.Put<AetherKey, AetherValue>(SchemaVersionKey.schemaVersionKey(DATASOURCE), record);

        assertThat(leader().<Object>apply(List.of(put)).await(REQUEST).isSuccess()).as("SETUP: commit the COMPLETED record").isTrue();
        await().atMost(DEPLOY_TIMEOUT)
               .until(() -> cluster.allNodes().stream().allMatch(node -> node.kvStore().get(SchemaVersionKey.schemaVersionKey(DATASOURCE)).isPresent()));
    }

    private SchemaStatus schemaStatus() {
        return leader().kvStore()
                       .get(SchemaVersionKey.schemaVersionKey(DATASOURCE))
                       .map(value -> ((SchemaVersionValue) value).status())
                       .or(SchemaStatus.UNKNOWN);
    }

    private long activeRows(Artifact artifact) {
        return cluster.allNodes().stream().filter(node -> isActive(node, artifact)).count();
    }

    private static boolean isActive(AetherNode node, Artifact artifact) {
        return node.kvStore()
                   .get(AetherKey.SliceObservedKey.sliceObservedKey(node.self(), artifact))
                   .map(value -> value instanceof SliceObservedValue row && row.state() == SliceObservedState.ACTIVE)
                   .or(false);
    }

    private AetherNode leader() {
        return cluster.currentLeader().flatMap(cluster::getNode).or(() -> cluster.allNodes().getFirst());
    }

    private boolean allNodesHealthy() {
        return cluster.status().nodes().stream().allMatch(node -> send(get(node.mgmtPort(), "/api/v1/health")).contains("\"quorum\":true"));
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

    private String post(int port, String path) {
        return send(HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path)).POST(HttpRequest.BodyPublishers.noBody())
                               .timeout(Duration.ofSeconds(10)).build());
    }

    private static HttpRequest get(int port, String path) {
        return HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path)).GET().timeout(Duration.ofSeconds(10)).build();
    }

    private String send(HttpRequest request) {
        return http.sendString(request).await().map(result -> result.statusCode() + " " + result.body()).or("request failed");
    }
}
