package org.pragmatica.aether.e2e.containers;

import org.pragmatica.lang.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Network;

import org.pragmatica.aether.e2e.TestEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.e2e.TestEnvironment.adapt;

/// Helper for managing multi-node Aether clusters in E2E tests.
///
///
/// Provides cluster lifecycle management:
///
///   - Create and start N-node clusters
///   - Wait for quorum formation
///   - Kill and restart individual nodes
///   - Access any node for API operations
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
    private static final Logger LOG = LoggerFactory.getLogger(AetherCluster.class);
    private static final Duration QUORUM_TIMEOUT = adapt(Duration.ofSeconds(120));
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

    // Local Maven repository path and test artifacts
    private static final Path M2_REPO_PATH = Path.of(System.getProperty("user.home"), ".m2", "repository");
    private static final String TEST_GROUP_PATH = "org/pragmatica-lite/aether/test";
    // Note: Uses slice artifact IDs (echo-slice-echo-service), not module artifact IDs (echo-slice)
    private static final String TEST_ARTIFACT_VERSION = System.getProperty("project.version", "0.15.1");
    private static final String[][] TEST_ARTIFACTS = {
        {"echo-slice-echo-service", TEST_ARTIFACT_VERSION},
        {"echo-slice-echo-service", "0.16.0"}
    };

    private final List<AetherNodeContainer> nodes;
    private final Network network;
    private final Path projectRoot;
    private final Map<String, AetherNodeContainer> nodeMap;

    // Use PID + counter for deterministic, collision-free subnets to avoid IP conflicts between test runs
    private static final java.util.concurrent.atomic.AtomicInteger SUBNET_COUNTER = new java.util.concurrent.atomic.AtomicInteger(0);
    private final String subnetPrefix;

    private String generateSubnet() {
        // Use PID + counter for deterministic, collision-free subnets
        long pid = ProcessHandle.current().pid();
        int counter = SUBNET_COUNTER.incrementAndGet();
        int second = (int) ((pid % 256));
        int third = counter % 256;
        return String.format("172.%d.%d", second, third);
    }

    private AetherCluster(int size, Path projectRoot) {
        this.projectRoot = projectRoot;
        // Generate unique subnet per cluster instance to avoid conflicts
        this.subnetPrefix = generateSubnet();
        System.out.println("[DEBUG] Using subnet: " + subnetPrefix + ".0/24");

        // Create network with unique subnet to isolate test clusters
        this.network = Network.builder()
                              .createNetworkCmdModifier(cmd -> {
                                  cmd.withIpam(new com.github.dockerjava.api.model.Network.Ipam()
                                      .withConfig(new com.github.dockerjava.api.model.Network.Ipam.Config()
                                          .withSubnet(subnetPrefix + ".0/24")
                                          .withGateway(subnetPrefix + ".1")));
                              })
                              .build();
        this.nodes = new ArrayList<>(size);
        this.nodeMap = new LinkedHashMap<>();

        // Build hostname-based peer list — Docker DNS resolves network aliases to actual IPs.
        // Previous IP-based approach was broken: parallelStream() startup caused Docker IPAM
        // to assign IPs in non-deterministic order, creating identity mismatches in consensus.
        var peerList = buildPeerList(size);
        System.out.println("[DEBUG] Using hostname-based peer list: " + peerList);

        // Create nodes — Docker network aliases provide DNS resolution between containers
        for (int i = 1; i <= size; i++) {
            var nodeId = "node-" + i;
            var node = AetherNodeContainer.aetherNode(nodeId, projectRoot, peerList)
                                          .withClusterNetwork(network);

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
    /// Uses hostname-based peer configuration since containers share a Docker network.
    public void start() {
        System.out.println("[DEBUG] Starting cluster with " + nodes.size() + " nodes in parallel...");

        // Start all nodes in parallel - each container is independent
        nodes.parallelStream().forEach(AetherNodeContainer::start);

        // Log IPs after all nodes are started
        for (var node : nodes) {
            var ip = getContainerIp(node).or("unknown");
            System.out.println("[DEBUG] Node " + node.nodeId() + " started with IP: " + ip);
        }

        System.out.println("[DEBUG] All nodes started. Waiting for cluster formation...");
    }

    /// Uploads test artifacts to DHT via Maven protocol.
    /// This must be called AFTER the cluster is healthy (leader elected, consensus working).
    /// Artifacts must be in DHT for slice deployment to work.
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
            } else {
                System.err.println("[WARN] Test artifact not found: " + jarPath);
            }
        }
        System.out.println("[DEBUG] Test artifacts upload complete");
    }

    private Option<String> getContainerIp(AetherNodeContainer node) {
        try {
            var networkSettings = node.getContainerInfo().getNetworkSettings();
            var networks = networkSettings.getNetworks();
            for (var networkEntry : networks.entrySet()) {
                var ip = networkEntry.getValue().getIpAddress();
                if (ip != null && !ip.isEmpty()) {
                    return Option.some(ip);
                }
            }
        } catch (Exception e) {
            System.err.println("[DEBUG] Failed to get IP for " + node.nodeId() + ": " + e.getMessage());
        }
        return Option.none();
    }


    /// Waits for the cluster to reach quorum.
    ///
    /// @throws org.awaitility.core.ConditionTimeoutException if quorum not reached
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

    /// Waits for a slice to become ACTIVE, failing fast if it reaches FAILED state.
    ///
    /// @param artifact artifact to wait for (can be partial match)
    /// @param timeout  maximum time to wait
    /// @throws SliceDeploymentException if slice transitions to FAILED state
    /// @throws org.awaitility.core.ConditionTimeoutException if timeout reached
    public void awaitSliceActive(String artifact, Duration timeout) {
        await().atMost(timeout)
               .pollInterval(POLL_INTERVAL)
               .until(() -> checkSliceState(artifact));
    }

    /// Waits for a slice to become ACTIVE on ALL nodes.
    /// Use this for multi-instance deployments where the slice should be distributed.
    ///
    /// @param artifact artifact to wait for (can be partial match)
    /// @param timeout  maximum time to wait
    /// @throws SliceDeploymentException if slice transitions to FAILED state on any node
    public void awaitSliceActiveOnAllNodes(String artifact, Duration timeout) {
        await().atMost(timeout)
               .pollInterval(POLL_INTERVAL)
               .until(() -> checkSliceStateOnAllNodes(artifact));
    }

    /// Checks slice state on ALL nodes, throwing exception on FAILED, returning true when ALL are ACTIVE.
    private boolean checkSliceStateOnAllNodes(String artifact) {
        for (var node : nodes) {
            if (!node.isRunning()) {
                continue; // Skip stopped nodes
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
                    return false; // Not yet ACTIVE on this node
                }
            } catch (SliceDeploymentException e) {
                throw e;
            } catch (Exception e) {
                System.out.println("[DEBUG] Error checking slice state on " + node.nodeId() + ": " + e.getMessage());
                return false;
            }
        }
        return true; // All nodes have ACTIVE state
    }

    // Track stuck states to detect infrastructure issues early
    private final Map<String, Long> stuckStateStartTime = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Duration STUCK_STATE_THRESHOLD = adapt(Duration.ofSeconds(60));

    /// Checks slice state, throwing exception on FAILED, returning true on ACTIVE.
    /// Also detects when a slice is stuck in an intermediate or NOT_FOUND state for too long.
    private boolean checkSliceState(String artifact) {
        try {
            var node = anyNode();
            var state = node.getSliceState(artifact);
            System.out.println("[DEBUG] Slice " + artifact + " state: " + state);

            if ("FAILED".equals(state)) {
                // Get more details about the failure
                var status = node.getSlicesStatus();
                // Get container logs for debugging
                var logs = getContainerLogs(node);
                throw new SliceDeploymentException(
                    "Slice " + artifact + " reached FAILED state.\nStatus: " + status +
                    "\n\n=== Container Logs ===\n" + logs);
            }

            // Detect stuck states (LOADING, ACTIVATING, NOT_FOUND, etc.)
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
                // Clear tracking when state changes to non-stuckable
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
        return "LOADING".equals(state) || "ACTIVATING".equals(state) ||
               "DEACTIVATING".equals(state) || "UNLOADING".equals(state);
    }

    private boolean isStuckableState(String state) {
        return isIntermediateState(state) || "NOT_FOUND".equals(state);
    }

    /// Gets the container logs, filtering for ERROR, WARN, and slice-related entries.
    private String getContainerLogs(AetherNodeContainer node) {
        try {
            var allLogs = node.getLogs();
            // Filter to show ERROR, WARN, and slice loading related lines
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
                       .collect(java.util.stream.Collectors.joining("\n"));
        } catch (Exception e) {
            return "Failed to get logs: " + e.getMessage();
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
                // Filter for deployment-related messages
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

    /// Waits for a leader to be elected.
    ///
    /// @throws org.awaitility.core.ConditionTimeoutException if leader not elected
    public void awaitLeader() {
        System.out.println("[DEBUG] Waiting for leader election...");
        try {
            await().atMost(QUORUM_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .until(() -> {
                       var leaderOpt = leader();
                       if (leaderOpt.isEmpty()) {
                           // Debug: log status from first running node
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
            // Dump container logs on timeout to help debug
            System.out.println("\n[DEBUG] ===== LEADER ELECTION TIMEOUT - DUMPING CONTAINER LOGS =====\n");
            dumpAllContainerLogs();
            throw e;
        }
    }

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
                // Filter for key consensus messages
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

    /// Waits for a specific node count in the cluster.
    ///
    /// @param expectedCount expected number of active nodes
    public void awaitNodeCount(int expectedCount) {
        await().atMost(QUORUM_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> activeNodeCount() == expectedCount);
    }

    /// Returns any running node (for API operations).
    ///
    /// @return a running node
    /// @throws IllegalStateException if no nodes are running
    public AetherNodeContainer anyNode() {
        return nodes.stream()
                    .filter(AetherNodeContainer::isRunning)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No running nodes"));
    }

    /// Returns a specific node by ID.
    ///
    /// @param nodeId node identifier
    /// @return the node container
    /// @throws NoSuchElementException if node not found
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
    ///
    /// @return leader node, or empty if not determinable
    public Option<AetherNodeContainer> leader() {
        return Option.from(nodes.stream()
                    .filter(AetherNodeContainer::isRunning)
                    .filter(this::isLeader)
                    .findFirst());
    }

    /// Kills a specific node.
    ///
    /// @param nodeId node to kill
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

    @Override
    public void close() {
        // Stop all nodes first
        for (var node : nodes) {
            try {
                node.stop();
            } catch (Exception e) {
                LOG.warn("Failed to stop node {}: {}", node.nodeId(), e.getMessage());
            }
        }
        // Defensive network cleanup - ensure network is removed even on errors
        try {
            network.close();
        } catch (Exception e) {
            LOG.warn("Failed to close network: {}", e.getMessage());
        }
    }

    // ===== Internal Methods =====

    private String buildPeerList(int size) {
        return IntStream.rangeClosed(1, size)
                        .mapToObj(i -> "node-" + i + ":node-" + i + ":8090")
                        .collect(Collectors.joining(","));
    }

    private boolean hasQuorum() {
        try {
            // Check cluster formation using /health endpoint which shows actual network connections
            // The /health endpoint includes connectedPeers count and ready state from consensus layer
            var health = anyNode().getHealth();
            var expectedNodeCount = runningNodeCount();
            // Parse ready state from health response: {"ready":true/false,...}
            boolean ready = health.contains("\"ready\":true");
            // Parse connectedPeers from health response: {"connectedPeers":N,...}
            int connectedPeers = 0;
            var matcher = java.util.regex.Pattern.compile("\"connectedPeers\":(\\d+)").matcher(health);
            if (matcher.find()) {
                connectedPeers = Integer.parseInt(matcher.group(1));
            }
            // Total nodes = connected peers + 1 (self)
            int totalNodes = connectedPeers + 1;
            // Log for debugging
            System.out.println("[DEBUG] hasQuorum check: health=" + health +
                               ", expectedNodeCount=" + expectedNodeCount +
                               ", connectedPeers=" + connectedPeers +
                               ", totalNodes=" + totalNodes +
                               ", ready=" + ready);
            // Quorum requires majority of expected nodes to be connected AND node must be ready
            return ready && totalNodes >= (expectedNodeCount / 2 + 1);
        } catch (Exception e) {
            System.out.println("[DEBUG] hasQuorum error: " + e.getMessage());
            return false;
        }
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
            var nodes = anyNode().getNodes();
            // Count node entries in JSON array
            return (int) nodes.chars()
                              .filter(ch -> ch == '{')
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
}
