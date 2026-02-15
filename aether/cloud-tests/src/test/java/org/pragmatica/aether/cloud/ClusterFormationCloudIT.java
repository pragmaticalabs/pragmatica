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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.cloud.hetzner.HetznerClient;
import org.pragmatica.cloud.hetzner.HetznerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/// Cloud integration test for Aether cluster formation on Hetzner.
/// Provisions a 5-node cluster on real cloud infrastructure and verifies
/// quorum formation, leader election, health, and node visibility.
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(value = 10, unit = TimeUnit.MINUTES)
class ClusterFormationCloudIT {

    private static final Logger LOG = LoggerFactory.getLogger(ClusterFormationCloudIT.class);
    private static final int CLUSTER_SIZE = 5;

    private static HetznerCloudCluster cluster;

    @BeforeAll
    static void setUp() {
        var token = System.getenv("HETZNER_TOKEN");
        Assumptions.assumeTrue(token != null && !token.isBlank(), "HETZNER_TOKEN not set, skipping cloud tests");

        var projectBasedir = System.getProperty("project.basedir", ".");
        var jarPath = Path.of(projectBasedir, "aether", "node", "target", "aether-node.jar");
        Assumptions.assumeTrue(Files.exists(jarPath), "aether-node.jar not found at " + jarPath + ", skipping cloud tests");

        LOG.info("Starting cloud cluster formation test with {} nodes", CLUSTER_SIZE);
        LOG.info("Using JAR: {}", jarPath);

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
                LOG.warn("Cluster cleanup failed: {}", e.getMessage());
            }
        }
    }

    @Test
    @Order(1)
    void fiveNodeCluster_formsQuorum() {
        cluster.awaitAllHealthy(Duration.ofMinutes(3));

        var anyReady = cluster.nodes().stream()
            .map(CloudNode::getHealth)
            .anyMatch(result -> result.fold(cause -> false, ClusterFormationCloudIT::containsReadyTrue));

        assertThat(anyReady).as("At least one node should report ready:true").isTrue();
    }

    @Test
    @Order(2)
    void cluster_electsLeader() {
        cluster.awaitLeader(Duration.ofMinutes(3));

        var anyLeader = cluster.nodes().stream()
            .map(CloudNode::getStatus)
            .anyMatch(result -> result.fold(cause -> false, ClusterFormationCloudIT::containsNonNullLeader));

        assertThat(anyLeader).as("At least one node should report a leader").isTrue();
    }

    @Test
    @Order(3)
    void cluster_allNodesHealthy() {
        cluster.awaitAllHealthy(Duration.ofMinutes(3));

        for (var node : cluster.nodes()) {
            var health = node.getHealth()
                             .fold(cause -> "", body -> body);

            assertThat(health)
                .as("Node %s should be ready", node.nodeId())
                .contains("\"ready\"");

            assertThat(containsReadyTrue(health))
                .as("Node %s should report ready:true", node.nodeId())
                .isTrue();
        }
    }

    @Test
    @Order(4)
    void cluster_leaderConsistent_acrossNodes() {
        cluster.awaitLeader(Duration.ofMinutes(3));

        var leaders = new HashSet<String>();

        for (var node : cluster.nodes()) {
            var status = node.getStatus()
                             .fold(cause -> "", body -> body);

            var leader = extractLeaderValue(status);

            if (leader != null && !leader.isEmpty()) {
                leaders.add(leader);
            }
        }

        assertThat(leaders)
            .as("All nodes reporting a leader should agree on the same leader ID")
            .hasSize(1);
    }

    @Test
    @Order(5)
    void cluster_allNodesVisible_toEachMember() {
        cluster.awaitAllHealthy(Duration.ofMinutes(3));

        for (var queryNode : cluster.nodes()) {
            var nodesResponse = queryNode.getNodes()
                                         .fold(cause -> "", body -> body);

            for (var expectedNode : cluster.nodes()) {
                assertThat(nodesResponse)
                    .as("Node %s should see node %s", queryNode.nodeId(), expectedNode.nodeId())
                    .contains(expectedNode.nodeId());
            }
        }
    }

    // --- Leaf: check if health response contains ready:true ---

    private static boolean containsReadyTrue(String body) {
        return body.contains("\"ready\":true") || body.contains("\"ready\": true");
    }

    // --- Leaf: check if status contains a non-null leader ---

    private static boolean containsNonNullLeader(String body) {
        return body.contains("\"leader\"")
               && !body.contains("\"leader\":null")
               && !body.contains("\"leader\": null")
               && !body.contains("\"leader\":\"\"")
               && !body.contains("\"leader\": \"\"");
    }

    // --- Leaf: extract leader value from status JSON ---

    private static String extractLeaderValue(String json) {
        var leaderIdx = json.indexOf("\"leader\"");

        if (leaderIdx < 0) {
            return null;
        }

        var colonIdx = json.indexOf(':', leaderIdx);

        if (colonIdx < 0) {
            return null;
        }

        var valueStart = colonIdx + 1;

        while (valueStart < json.length() && (json.charAt(valueStart) == ' ' || json.charAt(valueStart) == '"')) {
            valueStart++;
        }

        var valueEnd = valueStart;

        while (valueEnd < json.length() && json.charAt(valueEnd) != '"' && json.charAt(valueEnd) != ',' && json.charAt(valueEnd) != '}') {
            valueEnd++;
        }

        if (valueEnd <= valueStart) {
            return null;
        }

        var value = json.substring(valueStart, valueEnd).trim();

        return "null".equals(value) ? null : value;
    }
}
