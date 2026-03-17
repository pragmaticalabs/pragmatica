/*
 *  Copyright (c) 2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.pragmatica.aether.cloud;

import org.junit.jupiter.api.*;
import org.pragmatica.cloud.hetzner.HetznerClient;
import org.pragmatica.cloud.hetzner.HetznerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// Cloud integration test for slice deployment lifecycle on Hetzner.
/// Tests: upload artifact -> deploy -> invoke -> scale -> undeploy.
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(value = 15, unit = TimeUnit.MINUTES)
class SliceDeploymentCloudIT {

    private static final Logger log = LoggerFactory.getLogger(SliceDeploymentCloudIT.class);
    private static final int CLUSTER_SIZE = 3;
    private static final String TEST_ARTIFACT_VERSION = System.getProperty("project.version", "0.20.0");
    private static final String TEST_ARTIFACT = "org.pragmatica-lite.aether.test:echo-slice-echo-service:" + TEST_ARTIFACT_VERSION;
    private static final String BLUEPRINT_ID = "cloud.test:deploy:1.0.0";
    private static final Duration DEPLOY_TIMEOUT = Duration.ofMinutes(3);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(5);

    private static HetznerCloudCluster cluster;

    @BeforeAll
    static void setUp() {
        var token = System.getenv("HETZNER_TOKEN");
        Assumptions.assumeTrue(token != null && !token.isBlank(), "HETZNER_TOKEN not set, skipping cloud tests");

        var projectBasedir = System.getProperty("project.basedir", ".");
        var jarPath = Path.of(projectBasedir, "aether", "node", "target", "aether-node.jar");
        Assumptions.assumeTrue(Files.exists(jarPath), "aether-node.jar not found at " + jarPath);

        log.info("Starting slice deployment cloud test with {} nodes", CLUSTER_SIZE);

        var client = HetznerClient.hetznerClient(HetznerConfig.hetznerConfig(token));
        cluster = HetznerCloudCluster.hetznerCloudCluster(client, CLUSTER_SIZE, jarPath);
        cluster.provision();
    }

    @AfterAll
    static void tearDown() {
        if (cluster != null) {
            try {
                cluster.close();
            } catch (Exception e) {
                log.warn("Cluster cleanup failed: {}", e.getMessage());
            }
        }
    }

    @Test
    @Order(1)
    void cluster_formsQuorumAndHealthy() {
        cluster.awaitAllHealthy(Duration.ofMinutes(3));
        cluster.awaitLeader(Duration.ofMinutes(3));

        var anyReady = cluster.nodes().stream()
            .map(CloudNode::getHealth)
            .anyMatch(result -> result.fold(cause -> false, body -> containsReady(body)));

        assertThat(anyReady).as("At least one node should report ready:true").isTrue();
    }

    @Test
    @Order(2)
    void uploadArtifact_succeeds() {
        var projectBasedir = System.getProperty("project.basedir", ".");
        cluster.uploadTestArtifacts(Path.of(projectBasedir));

        var node = cluster.nodes().getFirst();
        var status = node.getSlicesStatus();
        assertThat(status.isSuccess()).as("Slices status endpoint should be accessible").isTrue();
    }

    @Test
    @Order(3)
    void deployEchoSlice_becomesActive() {
        var node = cluster.nodes().getFirst();
        var deployResult = node.deploy(TEST_ARTIFACT, 1);

        assertThat(deployResult.isSuccess())
            .as("Deploy should succeed: " + deployResult.fold(c -> c.message(), s -> s))
            .isTrue();

        await().atMost(DEPLOY_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> isSliceActive(TEST_ARTIFACT));
    }

    @Test
    @Order(4)
    void invokeEchoEndpoint_getsResponse() {
        var node = cluster.nodes().getFirst();
        var response = node.invokeGet("/echo/hello");

        assertThat(response.isSuccess())
            .as("Echo invoke should succeed: " + response.fold(c -> c.message(), s -> s))
            .isTrue();
    }

    @Test
    @Order(5)
    void scaleToThreeInstances_allActive() {
        var node = cluster.nodes().getFirst();
        var scaleResult = node.scale(TEST_ARTIFACT, 3);

        assertThat(scaleResult.isSuccess())
            .as("Scale should succeed: " + scaleResult.fold(c -> c.message(), s -> s))
            .isTrue();

        await().atMost(DEPLOY_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> isSliceActive(TEST_ARTIFACT));
    }

    @Test
    @Order(6)
    void invokeFromDifferentNode_getsResponse() {
        var anySuccess = cluster.nodes().stream()
            .map(node -> node.invokeGet("/echo/hello"))
            .anyMatch(result -> result.fold(cause -> false, body -> !body.contains("\"error\"")));

        assertThat(anySuccess).as("At least one node should successfully invoke the echo slice").isTrue();
    }

    @Test
    @Order(7)
    void undeploy_cleanRemoval() {
        var node = cluster.nodes().getFirst();
        var undeployResult = node.undeploy(BLUEPRINT_ID);

        assertThat(undeployResult.isSuccess())
            .as("Undeploy should succeed")
            .isTrue();

        await().atMost(DEPLOY_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> isSliceRemoved(TEST_ARTIFACT));
    }

    // --- Leaf: check if slice is ACTIVE ---

    private boolean isSliceActive(String artifact) {
        return cluster.nodes().stream()
            .map(CloudNode::getSlicesStatus)
            .anyMatch(result -> result.fold(cause -> false, body -> containsActiveArtifact(body, artifact)));
    }

    // --- Leaf: check if slice is removed ---

    private boolean isSliceRemoved(String artifact) {
        return cluster.nodes().getFirst().getSlicesStatus()
            .fold(cause -> false, body -> !body.contains(artifact));
    }

    // --- Leaf: check if body contains ready:true ---

    private static boolean containsReady(String body) {
        return body.contains("\"ready\":true") || body.contains("\"ready\": true");
    }

    // --- Leaf: check if body contains active artifact ---

    private static boolean containsActiveArtifact(String body, String artifact) {
        return body.contains(artifact) && body.contains("ACTIVE");
    }
}
