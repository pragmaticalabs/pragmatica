package org.pragmatica.aether.e2e.containers;

import org.pragmatica.lang.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.e2e.TestEnvironment.adapt;

/// Helper for managing multi-node Aether clusters in E2E tests.
///
///
/// All containers run on a shared bridge network with standard internal ports.
/// No port allocation needed — containers use identical ports (8080 management,
/// 8090 cluster, 8190 SWIM) and communicate via DNS (container aliases).
/// Tests access management API via Testcontainers' random mapped ports.
///
///
///
/// Usage:
/// ```{@code
/// try (var cluster = AetherCluster.aetherCluster(5, projectRoot)) {
///     cluster.start();
///     cluster.awaitQuorum();
///
///     var response = cluster.anyNode().getStatus();
///     // ... assertions
///
///     cluster.killNode("node-2");
///     cluster.awaitQuorum();
/// }
/// }```
public class AetherCluster implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(AetherCluster.class);
    private static final Duration QUORUM_TIMEOUT = adapt(Duration.ofSeconds(90));
    private static final Duration ON_DUTY_TIMEOUT = adapt(Duration.ofSeconds(120));
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

    // Local Maven repository path and test artifacts
    private static final Path M2_REPO_PATH = Path.of(System.getProperty("user.home"), ".m2", "repository");
    private static final String TEST_GROUP_PATH = "org/pragmatica-lite/aether/test";
    // Note: Uses slice artifact IDs (echo-slice-echo-service), not module artifact IDs (echo-slice)
    private static final String TEST_ARTIFACT_VERSION = System.getProperty("project.version", "0.20.0");
    /// Synthetic new version for rolling update tests — same JAR repackaged with bumped patch version.
    public static final String ROLLING_UPDATE_NEW_VERSION = bumpPatchVersion(TEST_ARTIFACT_VERSION);
    private static final String TEST_ARTIFACT_ID = "echo-slice-echo-service";
    private static final String[][] TEST_ARTIFACTS = {
        {TEST_ARTIFACT_ID, TEST_ARTIFACT_VERSION}
    };

    private final List<AetherNodeContainer> nodes;
    private final Path projectRoot;
    private final Map<String, AetherNodeContainer> nodeMap;
    private final Network network;
    private int nextNodeIndex;

    private AetherCluster(int size, Path projectRoot) {
        this.projectRoot = projectRoot;
        this.network = Network.newNetwork();
        this.nodes = new ArrayList<>(size);
        this.nodeMap = new LinkedHashMap<>();
        this.nextNodeIndex = size + 1;

        var peerList = buildPeerList(size);
        System.out.println("[DEBUG] Peer list (bridge networking): " + peerList);

        for (int i = 1; i <= size; i++) {
            var nodeId = "node-" + i;
            var node = AetherNodeContainer.aetherNode(nodeId, projectRoot, peerList, network);
            nodes.add(node);
            nodeMap.put(nodeId, node);
        }
    }

    /// Creates a new cluster with the specified number of nodes.
    ///
    /// @param size number of nodes (typically 3 or 5 for quorum)
    /// @param projectRoot path to project root (for Dockerfile context)
    /// @return cluster instance (not yet started)
    public static AetherCluster aetherCluster(int size, Path projectRoot) {
        if (size < 1) {
            throw new IllegalArgumentException("Cluster size must be at least 1");
        }
        return new AetherCluster(size, projectRoot);
    }

    /// Starts all nodes in the cluster in parallel for faster startup.
    public void start() {
        System.out.println("[DEBUG] Starting cluster with " + nodes.size() + " nodes in parallel (bridge networking)...");
        nodes.parallelStream().forEach(AetherNodeContainer::start);

        for (var node : nodes) {
            System.out.println("[DEBUG] Node " + node.nodeId() +
                               " started (management: " + node.managementUrl() +
                               ", cluster: " + node.clusterAddress() + ")");
        }

        System.out.println("[DEBUG] All nodes started. Waiting for cluster formation...");
    }

    /// Uploads test artifacts to DHT via Maven protocol.
    /// This must be called AFTER the cluster is healthy (leader elected, consensus working).
    public void uploadTestArtifacts() {
        System.out.println("[DEBUG] Uploading test artifacts to DHT...");
        var leaderNode = leader().or(this::anyNode);

        for (var artifact : TEST_ARTIFACTS) {
            var artifactId = artifact[0];
            var version = artifact[1];
            var jarFileName = artifactId + "-" + version + ".jar";
            var jarPath = M2_REPO_PATH.resolve(TEST_GROUP_PATH).resolve(artifactId).resolve(version).resolve(jarFileName);

            if (Files.exists(jarPath)) {
                var success = leaderNode.uploadArtifact(TEST_GROUP_PATH, artifactId, version, jarPath);
                if (!success) {
                    System.err.println("[WARN] Failed to upload artifact: " + jarPath);
                }
                // Also upload repackaged JAR under synthetic new version for rolling update tests
                if (version.equals(TEST_ARTIFACT_VERSION)) {
                    uploadRepackagedArtifact(leaderNode, artifactId, jarPath);
                }
            } else {
                System.err.println("[WARN] Test artifact not found: " + jarPath);
            }
        }
        System.out.println("[DEBUG] Test artifacts upload complete");
    }

    /// Waits for the cluster to reach quorum.
    public void awaitQuorum() {
        await().atMost(QUORUM_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(this::hasQuorum);
    }

    /// Waits for all nodes to be healthy.
    public void awaitAllHealthy() {
        await().atMost(QUORUM_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(this::allNodesHealthy);
    }

    /// Waits for the cluster to fully converge: all nodes agree on the same leader
    /// and see the expected number of connected peers.
    public void awaitClusterConverged() {
        System.out.println("[DEBUG] Waiting for cluster convergence...");
        try {
            await().atMost(QUORUM_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .until(this::clusterConverged);
        } catch (org.awaitility.core.ConditionTimeoutException e) {
            System.out.println("\n[DEBUG] ===== CONVERGENCE TIMEOUT - DUMPING CONTAINER LOGS =====\n");
            dumpAllContainerLogs();
            throw e;
        }
    }

    /// Waits for all nodes to register ON_DUTY lifecycle state.
    /// CDM will not allocate slices to nodes that are not ON_DUTY.
    public void awaitAllNodesOnDuty() {
        System.out.println("[DEBUG] Waiting for all nodes to register ON_DUTY lifecycle...");
        var expectedCount = (int) nodes.stream().filter(AetherNodeContainer::isRunning).count();
        await().atMost(ON_DUTY_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> checkAllNodesOnDuty(expectedCount));
    }

    /// Waits for HTTP routes to contain a specific substring.
    /// Routes are propagated via DHT and may arrive after slice becomes ACTIVE.
    public void awaitRoutesContain(String routeSubstring, Duration timeout) {
        await().atMost(timeout)
               .pollInterval(POLL_INTERVAL)
               .ignoreExceptions()
               .until(() -> {
                   var routes = anyNode().getRoutes();
                   return routes.contains(routeSubstring);
               });
    }

    /// Waits for a slice to be fully undeployed (removed from cluster-wide status).
    public void awaitSliceUndeployed(String artifact, Duration timeout) {
        var unloadingStart = new long[]{0};
        var stuckThreshold = STUCK_STATE_THRESHOLD;

        try {
            await().atMost(timeout)
                   .pollInterval(POLL_INTERVAL)
                   .ignoreExceptions()
                   .until(() -> {
                       var status = anyNode().getSlicesStatus();
                       System.out.println("[DEBUG] Cleanup check, cluster slices status: " + status);
                       if (!status.contains(artifact)) {
                           return true;
                       }
                       if (status.contains("UNLOAD")) {
                           if (unloadingStart[0] == 0) {
                               unloadingStart[0] = System.currentTimeMillis();
                           } else if (System.currentTimeMillis() - unloadingStart[0] > stuckThreshold.toMillis()) {
                               System.out.println("[DEBUG] UNLOADING stuck > " + stuckThreshold.toSeconds() +
                                                  "s, accepting and proceeding");
                               return true;
                           }
                       } else {
                           unloadingStart[0] = 0;
                           leader().onPresent(l -> {
                               var result = l.undeploy(artifact);
                               System.out.println("[DEBUG] Undeploy attempt: " + result);
                           });
                       }
                       return false;
                   });
        } catch (Exception e) {
            System.out.println("[DEBUG] Timed out waiting for slice removal: " + e.getMessage());
        }
    }

    /// Waits for a slice to become ACTIVE, failing fast if it reaches FAILED state.
    public void awaitSliceActive(String artifact, Duration timeout) {
        await().atMost(timeout)
               .pollInterval(POLL_INTERVAL)
               .until(() -> checkSliceState(artifact));
    }

    /// Waits for a slice to become ACTIVE on ALL nodes.
    public void awaitSliceActiveOnAllNodes(String artifact, Duration timeout) {
        await().atMost(timeout)
               .pollInterval(POLL_INTERVAL)
               .until(() -> checkSliceStateOnAllNodes(artifact));
    }

    /// Waits for a leader to be elected.
    public void awaitLeader() {
        System.out.println("[DEBUG] Waiting for leader election...");
        try {
            await().atMost(QUORUM_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .until(() -> {
                       var leaderOpt = leader();
                       if (leaderOpt.isEmpty()) {
                           nodes.stream()
                                .filter(AetherNodeContainer::isRunning)
                                .findFirst()
                                .ifPresent(node -> {
                                    try {
                                        System.out.println("[DEBUG] awaitLeader: no leader yet, status=" + node.getStatus());
                                    } catch (Exception e) {
                                        System.out.println("[DEBUG] awaitLeader: failed to get status: " + e.getMessage());
                                    }
                                });
                           return false;
                       }
                       leaderOpt.onPresent(leader ->
                           System.out.println("[DEBUG] Leader elected: " + leader.nodeId()));
                       return true;
                   });
        } catch (org.awaitility.core.ConditionTimeoutException e) {
            System.out.println("\n[DEBUG] ===== LEADER ELECTION TIMEOUT - DUMPING CONTAINER LOGS =====\n");
            dumpAllContainerLogs();
            throw e;
        }
    }

    /// Waits for a node to reach the expected lifecycle state.
    public void awaitNodeLifecycle(String nodeId, String expectedState, Duration timeout) {
        await().atMost(timeout)
               .pollInterval(POLL_INTERVAL)
               .ignoreExceptions()
               .until(() -> {
                   var lifecycle = anyNode().getNodeLifecycle(nodeId);
                   System.out.println("[DEBUG] awaitNodeLifecycle " + nodeId + ": " + lifecycle);
                   return lifecycle.contains("\"state\":\"" + expectedState + "\"");
               });
    }

    /// Waits for a specific node count in the cluster.
    public void awaitNodeCount(int expectedCount) {
        await().atMost(QUORUM_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> activeNodeCount() == expectedCount);
    }

    /// Returns any running node (for API operations).
    public AetherNodeContainer anyNode() {
        return nodes.stream()
                    .filter(AetherNodeContainer::isRunning)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No running nodes"));
    }

    /// Returns a specific node by ID.
    public AetherNodeContainer node(String nodeId) {
        var node = nodeMap.get(nodeId);
        if (node == null) {
            throw new NoSuchElementException("Node not found: " + nodeId);
        }
        return node;
    }

    /// Returns all nodes in the cluster.
    public List<AetherNodeContainer> nodes() {
        return Collections.unmodifiableList(nodes);
    }

    /// Returns the current leader node (if determinable).
    public Option<AetherNodeContainer> leader() {
        return Option.from(nodes.stream()
                    .filter(AetherNodeContainer::isRunning)
                    .filter(this::isLeader)
                    .findFirst());
    }

    /// Kills a specific node.
    public void killNode(String nodeId) {
        node(nodeId).stop();
    }

    /// Returns the number of running nodes.
    public int runningNodeCount() {
        return (int) nodes.stream()
                          .filter(AetherNodeContainer::isRunning)
                          .count();
    }

    /// Returns the cluster size (total nodes, running or not).
    public int size() {
        return nodes.size();
    }

    /// Returns the shared bridge network for this cluster.
    public Network network() {
        return network;
    }

    // ===== Network Operations =====

    /// Disconnects a node from cluster traffic using iptables, simulating a network partition.
    /// The container keeps running and management API remains accessible via port mapping.
    /// Only cluster port (8090) and SWIM port (8190) traffic is blocked.
    ///
    /// @param nodeId node to disconnect
    public void disconnectNode(String nodeId) {
        var container = node(nodeId);
        var clusterPort = String.valueOf(AetherNodeContainer.CLUSTER_PORT);
        var swimPort = String.valueOf(AetherNodeContainer.CLUSTER_PORT + 100);
        // Block all cluster and SWIM traffic (both TCP and UDP) — run as root
        execAsRoot(container, "iptables", "-A", "INPUT", "-p", "tcp", "--dport", clusterPort, "-j", "DROP");
        execAsRoot(container, "iptables", "-A", "OUTPUT", "-p", "tcp", "--dport", clusterPort, "-j", "DROP");
        execAsRoot(container, "iptables", "-A", "INPUT", "-p", "tcp", "--sport", clusterPort, "-j", "DROP");
        execAsRoot(container, "iptables", "-A", "OUTPUT", "-p", "tcp", "--sport", clusterPort, "-j", "DROP");
        execAsRoot(container, "iptables", "-A", "INPUT", "-p", "udp", "--dport", swimPort, "-j", "DROP");
        execAsRoot(container, "iptables", "-A", "OUTPUT", "-p", "udp", "--dport", swimPort, "-j", "DROP");
        execAsRoot(container, "iptables", "-A", "INPUT", "-p", "udp", "--sport", swimPort, "-j", "DROP");
        execAsRoot(container, "iptables", "-A", "OUTPUT", "-p", "udp", "--sport", swimPort, "-j", "DROP");
        System.out.println("[DEBUG] Disconnected " + nodeId + " from cluster network (iptables)");
    }

    /// Reconnects a previously disconnected node by removing iptables rules.
    ///
    /// @param nodeId node to reconnect
    public void reconnectNode(String nodeId) {
        var container = node(nodeId);
        execAsRoot(container, "iptables", "-F");
        System.out.println("[DEBUG] Reconnected " + nodeId + " to cluster network (iptables)");
    }

    /// Executes a command inside a container as root user via Docker API.
    private static void execAsRoot(AetherNodeContainer container, String... command) {
        try {
            var dockerClient = org.testcontainers.DockerClientFactory.instance().client();
            var execCreate = dockerClient.execCreateCmd(container.getContainerId())
                                         .withCmd(command)
                                         .withUser("root")
                                         .withAttachStdout(true)
                                         .withAttachStderr(true)
                                         .exec();
            var result = dockerClient.execStartCmd(execCreate.getId())
                                     .start()
                                     .awaitCompletion();
            var inspectResult = dockerClient.inspectExecCmd(execCreate.getId()).exec();
            var exitCode = inspectResult.getExitCodeLong();
            if (exitCode != null && exitCode != 0) {
                throw new RuntimeException("Command failed with exit code " + exitCode +
                                           ": " + String.join(" ", command));
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to exec as root: " + String.join(" ", command), e);
        }
    }

    /// Adds a new node to the running cluster.
    /// The new node bootstraps by connecting to existing peers.
    ///
    /// @return the new node container (already started)
    public AetherNodeContainer addNode() {
        var newNodeId = "node-" + nextNodeIndex++;
        // Build peer list from all nodes (existing + new)
        var allPeers = nodes.stream()
            .map(AetherNodeContainer::clusterAddress)
            .collect(Collectors.joining(","));
        var newNodeAddress = newNodeId + ":" + newNodeId + ":" + AetherNodeContainer.CLUSTER_PORT;
        var peerList = allPeers + "," + newNodeAddress;

        var newNode = AetherNodeContainer.aetherNode(newNodeId, projectRoot, peerList, network);
        newNode.start();
        nodes.add(newNode);
        nodeMap.put(newNodeId, newNode);

        System.out.println("[DEBUG] Added new node " + newNodeId + " to cluster");
        return newNode;
    }

    // ===== Log Dump Helpers =====

    /// Dumps full logs from all running containers for debugging.
    public void dumpAllContainerLogs() {
        for (var node : nodes) {
            if (!node.isRunning()) {
                System.out.println("[DEBUG] Node " + node.nodeId() + " is not running");
                continue;
            }
            try {
                System.out.println("\n===== LOGS FOR " + node.nodeId() + " =====");
                var logs = node.getLogs();
                logs.lines()
                    .filter(line -> line.contains("quorum") ||
                                   line.contains("sync") ||
                                   line.contains("Sync") ||
                                   line.contains("activated") ||
                                   line.contains("leader") ||
                                   line.contains("Leader") ||
                                   line.contains("ERROR") ||
                                   line.contains("WARN") ||
                                   line.contains("connect") ||
                                   line.contains("Connect") ||
                                   line.contains("processViewChange"))
                    .forEach(System.out::println);
                System.out.println("===== END LOGS FOR " + node.nodeId() + " =====\n");
            } catch (Exception ex) {
                System.out.println("[DEBUG] Failed to get logs for " + node.nodeId() + ": " + ex.getMessage());
            }
        }
    }

    /// Dumps deployment-related logs from all containers for debugging stuck slices.
    public void dumpAllDeploymentLogs() {
        for (var node : nodes) {
            if (!node.isRunning()) {
                System.out.println("[DEBUG] Node " + node.nodeId() + " is not running");
                continue;
            }
            try {
                System.out.println("\n===== DEPLOYMENT LOGS FOR " + node.nodeId() + " =====");
                var logs = node.getLogs();
                logs.lines()
                    .filter(line -> line.contains("ERROR") ||
                                   line.contains("WARN") ||
                                   line.contains("leader") ||
                                   line.contains("Leader") ||
                                   line.contains("became") ||
                                   line.contains("activat") ||
                                   line.contains("Activat") ||
                                   line.contains("slice") ||
                                   line.contains("Slice") ||
                                   line.contains("deploy") ||
                                   line.contains("Deploy") ||
                                   line.contains("LOAD") ||
                                   line.contains("Loading") ||
                                   line.contains("target") ||
                                   line.contains("Target") ||
                                   line.contains("allocat") ||
                                   line.contains("Allocat") ||
                                   line.contains("artifact") ||
                                   line.contains("Artifact") ||
                                   line.contains("repository") ||
                                   line.contains("Repository") ||
                                   line.contains("repositories") ||
                                   line.contains("BUILTIN") ||
                                   line.contains("builtin") ||
                                   line.contains("LOCAL") ||
                                   line.contains("resolv") ||
                                   line.contains("Resolv") ||
                                   line.contains("DHT") ||
                                   line.contains("dht") ||
                                   line.contains("ValuePut") ||
                                   line.contains("issuing") ||
                                   line.contains("Issuing") ||
                                   line.contains("reconcil") ||
                                   line.contains("Reconcil") ||
                                   line.contains("blueprint") ||
                                   line.contains("Blueprint") ||
                                   line.contains("Exception") ||
                                   line.contains("NotFound") ||
                                   line.contains("not found") ||
                                   line.contains("Starting Aether"))
                    .forEach(System.out::println);
                System.out.println("===== END LOGS FOR " + node.nodeId() + " =====\n");
            } catch (Exception ex) {
                System.out.println("[DEBUG] Failed to get logs for " + node.nodeId() + ": " + ex.getMessage());
            }
        }
    }

    /// Exception thrown when slice deployment fails.
    public static class SliceDeploymentException extends RuntimeException {
        public SliceDeploymentException(String message) {
            super(message);
        }
    }

    @Override
    public void close() {
        for (var node : nodes) {
            try {
                node.stop();
            } catch (Exception e) {
                log.warn("Failed to stop node {}: {}", node.nodeId(), e.getMessage());
            }
        }
        try {
            network.close();
        } catch (Exception e) {
            log.warn("Failed to close network: {}", e.getMessage());
        }
    }

    // ===== Internal Methods =====

    private String buildPeerList(int size) {
        return IntStream.rangeClosed(1, size)
                        .mapToObj(i -> {
                            var nodeId = "node-" + i;
                            return nodeId + ":" + nodeId + ":" + AetherNodeContainer.CLUSTER_PORT;
                        })
                        .collect(Collectors.joining(","));
    }

    private boolean hasQuorum() {
        try {
            var health = anyNode().getHealth();
            var expectedNodeCount = runningNodeCount();
            boolean ready = health.contains("\"ready\":true");
            int connectedPeers = 0;
            var matcher = java.util.regex.Pattern.compile("\"connectedPeers\":(\\d+)").matcher(health);
            if (matcher.find()) {
                connectedPeers = Integer.parseInt(matcher.group(1));
            }
            int totalNodes = connectedPeers + 1;
            System.out.println("[DEBUG] hasQuorum check: health=" + health +
                               ", expectedNodeCount=" + expectedNodeCount +
                               ", connectedPeers=" + connectedPeers +
                               ", totalNodes=" + totalNodes +
                               ", ready=" + ready);
            return ready && totalNodes >= (expectedNodeCount / 2 + 1);
        } catch (Exception e) {
            System.out.println("[DEBUG] hasQuorum error: " + e.getMessage());
            return false;
        }
    }

    private boolean clusterConverged() {
        var runningNodes = nodes.stream()
            .filter(AetherNodeContainer::isRunning)
            .toList();

        if (runningNodes.isEmpty()) {
            return false;
        }

        var expectedPeers = runningNodes.size() - 1;
        var leaderPattern = java.util.regex.Pattern.compile("\"leader\":\"([^\"]+)\"");
        var peersPattern = java.util.regex.Pattern.compile("\"connectedPeers\":(\\d+)");

        var converged = true;
        String consensusLeader = null;
        var summary = new StringBuilder("[DEBUG] Convergence:");
        for (var node : runningNodes) {
            try {
                var status = node.getStatus();
                var health = node.getHealth();

                var leaderMatcher = leaderPattern.matcher(status);
                String nodeLeader = leaderMatcher.find() ? leaderMatcher.group(1) : "NONE";
                var peersMatcher = peersPattern.matcher(health);
                int peers = peersMatcher.find() ? Integer.parseInt(peersMatcher.group(1)) : -1;

                summary.append(" ").append(node.nodeId()).append("(leader=").append(nodeLeader)
                       .append(",peers=").append(peers).append(")");

                if ("NONE".equals(nodeLeader)) {
                    converged = false;
                } else if (consensusLeader == null) {
                    consensusLeader = nodeLeader;
                } else if (!consensusLeader.equals(nodeLeader)) {
                    converged = false;
                }
                if (peers < expectedPeers) {
                    converged = false;
                }
            } catch (Exception e) {
                summary.append(" ").append(node.nodeId()).append("(ERROR=").append(e.getMessage()).append(")");
                converged = false;
            }
        }
        System.out.println(summary);
        if (converged) {
            System.out.println("[DEBUG] Cluster converged: leader=" + consensusLeader +
                ", all " + runningNodes.size() + " nodes agree, " + expectedPeers + " peers each");
        }
        return converged;
    }

    private boolean allNodesHealthy() {
        var allHealthy = true;
        for (var node : nodes) {
            if (!node.isRunning()) {
                continue;
            }
            try {
                var health = node.getHealth();
                var isHealthy = !health.contains("\"error\"") && health.contains("\"ready\":true");
                if (!isHealthy) {
                    System.out.println("[DEBUG] Node " + node.nodeId() + " NOT healthy: " + health);
                    allHealthy = false;
                }
            } catch (Exception e) {
                System.out.println("[DEBUG] Node " + node.nodeId() + " health check failed: " + e.getMessage());
                allHealthy = false;
            }
        }
        if (allHealthy) {
            System.out.println("[DEBUG] All nodes healthy");
        }
        return allHealthy;
    }

    private int activeNodeCount() {
        try {
            var nodesJson = anyNode().getNodes();
            // NodesResponse is {"nodes":["node-1","node-2",...]} — count "node-" occurrences
            return (int) java.util.regex.Pattern.compile("\"node-")
                                                .matcher(nodesJson)
                                                .results()
                                                .count();
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean isLeader(AetherNodeContainer node) {
        try {
            var status = node.getStatus();
            return status.contains("\"isLeader\":true") ||
                   status.contains("\"leader\":\"" + node.nodeId() + "\"");
        } catch (Exception e) {
            return false;
        }
    }

    // Track stuck states to detect infrastructure issues early
    private final Map<String, Long> stuckStateStartTime = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Duration STUCK_STATE_THRESHOLD = adapt(Duration.ofSeconds(30));

    private boolean checkSliceStateOnAllNodes(String artifact) {
        for (var node : nodes) {
            if (!node.isRunning()) {
                continue;
            }
            try {
                var state = node.getSliceState(artifact);
                System.out.println("[DEBUG] Node " + node.nodeId() + " slice " + artifact + " state: " + state);

                if ("FAILED".equals(state)) {
                    var status = node.getSlicesStatus();
                    var logs = getContainerLogs(node);
                    throw new SliceDeploymentException(
                        "Slice " + artifact + " reached FAILED state on " + node.nodeId() +
                        ".\nStatus: " + status + "\n\n=== Container Logs ===\n" + logs);
                }
                if (!"ACTIVE".equals(state)) {
                    return false;
                }
            } catch (SliceDeploymentException e) {
                throw e;
            } catch (Exception e) {
                System.out.println("[DEBUG] Error checking slice state on " + node.nodeId() + ": " + e.getMessage());
                return false;
            }
        }
        return true;
    }

    private boolean checkSliceState(String artifact) {
        try {
            var node = anyNode();
            var state = node.getSliceState(artifact);
            System.out.println("[DEBUG] Slice " + artifact + " state: " + state);

            if ("FAILED".equals(state)) {
                var status = node.getSlicesStatus();
                var logs = getContainerLogs(node);
                throw new SliceDeploymentException(
                    "Slice " + artifact + " reached FAILED state.\nStatus: " + status +
                    "\n\n=== Container Logs ===\n" + logs);
            }

            if (isStuckableState(state)) {
                var stuckSince = stuckStateStartTime.computeIfAbsent(artifact + ":" + state,
                    k -> System.currentTimeMillis());
                var stuckDuration = Duration.ofMillis(System.currentTimeMillis() - stuckSince);
                if (stuckDuration.compareTo(STUCK_STATE_THRESHOLD) > 0) {
                    System.out.println("\n[ERROR] Slice stuck in " + state + " - dumping all container logs:\n");
                    dumpAllDeploymentLogs();
                    throw new SliceDeploymentException(
                        "Slice " + artifact + " stuck in " + state + " state for " +
                        stuckDuration.toSeconds() + "s. This indicates an infrastructure issue.\n" +
                        "Check logs above for details.");
                }
            } else {
                stuckStateStartTime.keySet().removeIf(k -> k.startsWith(artifact + ":"));
            }

            return "ACTIVE".equals(state);
        } catch (SliceDeploymentException e) {
            throw e;
        } catch (Exception e) {
            System.out.println("[DEBUG] Error checking slice state: " + e.getMessage());
            return false;
        }
    }

    private boolean isIntermediateState(String state) {
        return state != null && (
            state.startsWith("LOADING") || state.startsWith("ACTIVAT") ||
            state.startsWith("DEACTIVAT") || state.startsWith("UNLOAD"));
    }

    private boolean isStuckableState(String state) {
        return isIntermediateState(state) || "NOT_FOUND".equals(state);
    }

    private String getContainerLogs(AetherNodeContainer node) {
        try {
            var allLogs = node.getLogs();
            return allLogs.lines()
                       .filter(line -> line.contains("ERROR") ||
                                      line.contains("WARN") ||
                                      line.contains("slice") ||
                                      line.contains("Slice") ||
                                      line.contains("FAILED") ||
                                      line.contains("Loading") ||
                                      line.contains("Artifact") ||
                                      line.contains("ClassLoader") ||
                                      line.contains("Exception"))
                       .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            return "Failed to get logs: " + e.getMessage();
        }
    }

    private int onDutyRetryCount = 0;

    private boolean checkAllNodesOnDuty(int expectedCount) {
        try {
            var lifecycles = anyNode().getAllNodeLifecycles();
            var onDutyCount = countOccurrences(lifecycles, "\"ON_DUTY\"");
            if (onDutyCount < expectedCount) {
                onDutyRetryCount++;
                System.out.println("[DEBUG] ON_DUTY: " + onDutyCount + "/" + expectedCount + " — " + lifecycles);
                // Dump container logs once after ~20 seconds of failure to diagnose consensus issues
                if (onDutyRetryCount == 10) {
                    dumpConsensusLogs();
                }
                return false;
            }
            System.out.println("[DEBUG] All " + expectedCount + " nodes ON_DUTY");
            onDutyRetryCount = 0;
            return true;
        } catch (Exception e) {
            System.out.println("[DEBUG] ON_DUTY check failed: " + e.getMessage());
            return false;
        }
    }

    private void dumpConsensusLogs() {
        System.out.println("[DIAG] === Container logs (last 50 lines) ===");
        for (var node : nodes) {
            if (!node.isRunning()) {
                continue;
            }
            try {
                var allLogs = node.getLogs();
                var lines = allLogs.lines().toList();
                var start = Math.max(0, lines.size() - 50);
                System.out.println("[DIAG] --- " + node.nodeId() + " (total " + lines.size() + " lines) ---");
                for (int i = start; i < lines.size(); i++) {
                    System.out.println("[DIAG] " + lines.get(i));
                }
            } catch (Exception e) {
                System.out.println("[DIAG] Failed to get logs for " + node.nodeId() + ": " + e.getMessage());
            }
        }
        System.out.println("[DIAG] === End container logs ===");
    }

    private static int countOccurrences(String text, String search) {
        var count = 0;
        var idx = 0;
        while ((idx = text.indexOf(search, idx)) != -1) {
            count++;
            idx += search.length();
        }
        return count;
    }

    private static String bumpPatchVersion(String version) {
        var parts = version.split("\\.");
        var patch = Integer.parseInt(parts[2]) + 1;
        return parts[0] + "." + parts[1] + "." + patch;
    }

    private static void uploadRepackagedArtifact(AetherNodeContainer leader, String artifactId, Path originalJar) {
        try {
            var repackaged = repackageJarWithVersion(originalJar, ROLLING_UPDATE_NEW_VERSION);
            var success = leader.uploadArtifactBytes(TEST_GROUP_PATH, artifactId, ROLLING_UPDATE_NEW_VERSION, repackaged);
            if (!success) {
                System.err.println("[WARN] Failed to upload repackaged artifact for rolling update");
            }
        } catch (Exception e) {
            System.err.println("[WARN] Failed to repackage artifact for rolling update: " + e.getMessage());
        }
    }



    private static byte[] repackageJarWithVersion(Path originalJar, String targetVersion) throws Exception {
        var jarBytes = Files.readAllBytes(originalJar);
        try (var jis = new JarInputStream(new ByteArrayInputStream(jarBytes))) {
            var manifest = jis.getManifest();
            var attrs = manifest.getMainAttributes();
            var artifactStr = attrs.getValue("Slice-Artifact");
            if (artifactStr != null) {
                var lastColon = artifactStr.lastIndexOf(':');
                if (lastColon >= 0) {
                    var newArtifact = artifactStr.substring(0, lastColon + 1) + targetVersion;
                    attrs.putValue("Slice-Artifact", newArtifact);
                    System.out.println("[DEBUG] Repackaging JAR: " + artifactStr + " → " + newArtifact);
                }
            }

            var baos = new ByteArrayOutputStream();
            try (var jos = new JarOutputStream(baos, manifest)) {
                var buffer = new byte[8192];
                JarEntry entry;
                while ((entry = jis.getNextJarEntry()) != null) {
                    jos.putNextEntry(new JarEntry(entry.getName()));
                    int len;
                    while ((len = jis.read(buffer)) > 0) {
                        jos.write(buffer, 0, len);
                    }
                    jos.closeEntry();
                }
            }
            return baos.toByteArray();
        }
    }
}
