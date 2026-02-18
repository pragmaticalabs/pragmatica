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

import org.pragmatica.cloud.hetzner.HetznerClient;
import org.pragmatica.cloud.hetzner.api.Server;
import org.pragmatica.cloud.hetzner.api.Server.CreateServerRequest;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.cloud.CloudNode.cloudNode;

/// N-server Hetzner cloud cluster for integration testing.
/// Manages the full lifecycle: SSH key creation, server provisioning,
/// JAR deployment, node startup, health verification, and cleanup.
public final class HetznerCloudCluster implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HetznerCloudCluster.class);
    private static final String SERVER_PREFIX = "aether-test-";
    private static final String SERVER_TYPE = "cx22";
    private static final String IMAGE = "ubuntu-24.04";
    private static final String LOCATION = "fsn1";
    private static final int CLUSTER_PORT = 8090;

    private static final String CLOUD_INIT_SCRIPT = """
        #!/bin/bash
        set -euo pipefail
        apt-get update -qq
        apt-get install -y -qq wget
        mkdir -p /opt/java /opt/aether
        cd /opt/java
        wget -q https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jdk/hotspot/normal/eclipse -O jdk.tar.gz
        tar xzf jdk.tar.gz --strip-components=1
        ln -sf /opt/java/bin/java /usr/local/bin/java
        """;

    private final HetznerClient client;
    private final int size;
    private final Path jarPath;
    private final List<CloudNode> nodes = new ArrayList<>();
    private final List<Long> serverIds = new ArrayList<>();
    private SshKeyManager sshKeyManager;

    private HetznerCloudCluster(HetznerClient client, int size, Path jarPath) {
        this.client = client;
        this.size = size;
        this.jarPath = jarPath;
    }

    /// Factory method for creating a cloud cluster.
    public static HetznerCloudCluster hetznerCloudCluster(HetznerClient client, int size, Path jarPath) {
        return new HetznerCloudCluster(client, size, jarPath);
    }

    /// Returns the list of provisioned cloud nodes.
    public List<CloudNode> nodes() {
        return List.copyOf(nodes);
    }

    /// Provisions the full cluster: SSH key, servers, Java install, JAR deploy, and node start.
    public void provision() {
        createSshKey();
        createServers();
        waitForServersRunning();
        waitForSshConnectivity();
        verifyJavaInstalled();
        deployJars();
        startAllNodes();
    }

    /// Waits until all nodes report healthy via /api/health.
    public void awaitAllHealthy(Duration timeout) {
        await().atMost(timeout)
               .pollInterval(Duration.ofSeconds(5))
               .untilAsserted(this::assertAllNodesHealthy);
    }

    /// Waits until at least one node reports a leader via /api/status.
    public void awaitLeader(Duration timeout) {
        await().atMost(timeout)
               .pollInterval(Duration.ofSeconds(5))
               .untilAsserted(this::assertLeaderElected);
    }

    @Override
    public void close() {
        terminateServers();
        cleanupSshKey();
        log.info("Cloud cluster cleanup complete. Server IDs were: {}", serverIds);
    }

    // --- Leaf: create SSH key ---

    private void createSshKey() {
        log.info("Creating ephemeral SSH key for cloud test...");
        sshKeyManager = SshKeyManager.sshKeyManager(client, SERVER_PREFIX + "key-" + System.currentTimeMillis());
        log.info("SSH key created with Hetzner ID: {}", sshKeyManager.hetznerKeyId());
    }

    // --- Leaf: create N servers in parallel ---

    private void createServers() {
        log.info("Creating {} servers...", size);
        var futures = IntStream.range(0, size)
            .mapToObj(this::createSingleServer)
            .toList();

        for (var future : futures) {
            var server = requireSuccess(future.await(), "Server creation failed");
            serverIds.add(server.id());
            log.info("Server {} created: id={}, name={}", serverIds.size() - 1, server.id(), server.name());
        }
    }

    private org.pragmatica.lang.Promise<Server> createSingleServer(int index) {
        var name = SERVER_PREFIX + index + "-" + System.currentTimeMillis();

        var request = CreateServerRequest.createServerRequest(
            name, SERVER_TYPE, IMAGE,
            List.of(sshKeyManager.hetznerKeyId()),
            List.of(), List.of(),
            LOCATION, CLOUD_INIT_SCRIPT, true);

        return client.createServer(request);
    }

    // --- Leaf: poll until all servers report running ---

    private void waitForServersRunning() {
        log.info("Waiting for all servers to reach 'running' state...");

        await().atMost(Duration.ofMinutes(3))
               .pollInterval(Duration.ofSeconds(5))
               .untilAsserted(this::assertAllServersRunning);

        populateNodes();
        log.info("All {} servers are running", size);
    }

    private void assertAllServersRunning() {
        for (var serverId : serverIds) {
            var server = requireAssertSuccess(client.getServer(serverId).await(), "Failed to get server " + serverId);

            if (!"running".equals(server.status())) {
                throw new AssertionError("Server " + serverId + " is " + server.status() + ", not running");
            }
        }
    }

    private void populateNodes() {
        nodes.clear();

        for (int i = 0; i < serverIds.size(); i++) {
            var serverId = serverIds.get(i);
            var server = requireSuccess(client.getServer(serverId).await(), "Failed to get server");

            var ip = server.publicNet().ipv4().ip();
            nodes.add(cloudNode("node-" + i, ip, serverId, sshKeyManager.privateKeyPath()));
        }
    }

    // --- Leaf: wait for SSH on all nodes ---

    private void waitForSshConnectivity() {
        log.info("Waiting for SSH connectivity on all nodes (cloud-init may take ~2-3 min)...");

        for (var node : nodes) {
            requireSuccess(
                RemoteCommandRunner.waitForSsh(node.publicIp(), sshKeyManager.privateKeyPath(), Duration.ofMinutes(5)),
                "SSH wait failed for " + node.publicIp());
        }

        log.info("SSH connectivity established on all {} nodes", size);
    }

    // --- Leaf: verify Java installed ---

    private void verifyJavaInstalled() {
        log.info("Verifying Java installation on all nodes...");

        for (var node : nodes) {
            var output = requireSuccess(
                RemoteCommandRunner.ssh(node.publicIp(), "java -version", sshKeyManager.privateKeyPath()),
                "Java not installed on " + node.publicIp());

            log.info("[{}] Java verified: {}", node.nodeId(), output.lines().findFirst().orElse(""));
        }
    }

    // --- Leaf: deploy JARs to all nodes ---

    private void deployJars() {
        log.info("Deploying aether-node.jar to all nodes...");

        for (var node : nodes) {
            requireSuccess(node.uploadJar(jarPath), "JAR upload failed for " + node.publicIp());
        }

        log.info("JAR deployed to all {} nodes", size);
    }

    // --- Leaf: start all nodes with peer list ---

    private void startAllNodes() {
        var peerList = buildPeerList();
        log.info("Starting all nodes with peer list: {}", peerList);

        for (var node : nodes) {
            requireSuccess(node.startNode(peerList), "Node start failed for " + node.nodeId());
        }

        log.info("All {} nodes started", size);
    }

    private String buildPeerList() {
        return nodes.stream()
                    .map(n -> n.nodeId() + ":" + n.publicIp() + ":" + CLUSTER_PORT)
                    .collect(Collectors.joining(","));
    }

    // --- Leaf: assert all nodes healthy ---

    private void assertAllNodesHealthy() {
        for (var node : nodes) {
            var health = requireAssertSuccess(node.getHealth(), "[" + node.nodeId() + "] Health check failed");

            if (!health.contains("\"ready\":true") && !health.contains("\"ready\": true")) {
                throw new AssertionError("[" + node.nodeId() + "] Not ready: " + health);
            }
        }
    }

    // --- Leaf: assert leader elected ---

    private void assertLeaderElected() {
        var anyLeaderFound = nodes.stream()
            .map(CloudNode::getStatus)
            .filter(Result::isSuccess)
            .map(result -> result.fold(cause -> "", s -> s))
            .anyMatch(HetznerCloudCluster::containsNonNullLeader);

        if (!anyLeaderFound) {
            throw new AssertionError("No leader elected on any node");
        }
    }

    private static boolean containsNonNullLeader(String body) {
        return body.contains("\"leader\"")
               && !body.contains("\"leader\":null")
               && !body.contains("\"leader\": null");
    }

    // --- Leaf: terminate all servers ---

    private void terminateServers() {
        for (var serverId : serverIds) {
            try {
                client.deleteServer(serverId).await();
                log.info("Terminated server {}", serverId);
            } catch (Exception e) {
                log.warn("Failed to terminate server {}: {}", serverId, e.getMessage());
            }
        }
    }

    // --- Leaf: cleanup SSH key ---

    private void cleanupSshKey() {
        if (sshKeyManager != null) {
            try {
                sshKeyManager.close();
            } catch (Exception e) {
                log.warn("Failed to cleanup SSH key: {}", e.getMessage());
            }
        }
    }

    // --- Utility: unwrap Result or throw IllegalStateException ---

    private static <T> T requireSuccess(Result<T> result, String context) {
        return result.fold(cause -> failWithState(context, cause), s -> s);
    }

    // --- Utility: unwrap Result or throw AssertionError ---

    private static <T> T requireAssertSuccess(Result<T> result, String context) {
        return result.fold(cause -> failWithAssert(context, cause), s -> s);
    }

    private static <T> T failWithState(String context, Cause cause) {
        throw new IllegalStateException(context + ": " + cause.message());
    }

    private static <T> T failWithAssert(String context, Cause cause) {
        throw new AssertionError(context + ": " + cause.message());
    }
}
