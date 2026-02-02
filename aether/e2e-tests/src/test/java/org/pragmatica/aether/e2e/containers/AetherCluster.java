package org.pragmatica.aether.e2e.containers;

import org.pragmatica.lang.Option;
import org.testcontainers.containers.Network;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.awaitility.Awaitility.await;

/**
 * Helper for managing multi-node Aether clusters in E2E tests.
 *
 * <p>Provides cluster lifecycle management:
 * <ul>
 *   <li>Create and start N-node clusters</li>
 *   <li>Wait for quorum formation</li>
 *   <li>Kill and restart individual nodes</li>
 *   <li>Access any node for API operations</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * try (var cluster = AetherCluster.aetherCluster(3, projectRoot)) {
 *     cluster.start();
 *     cluster.awaitQuorum();
 *
 *     var response = cluster.anyNode().getStatus();
 *     // ... assertions
 *
 *     cluster.killNode("node-2");
 *     cluster.awaitQuorum();
 * }
 * }</pre>
 */
public class AetherCluster implements AutoCloseable {
    private static final Duration QUORUM_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

    private final List<AetherNodeContainer> nodes;
    private final Network network;
    private final Path projectRoot;
    private final Map<String, AetherNodeContainer> nodeMap;

    // Use predictable IPs for container-to-container communication
    // Podman's DNS doesn't reliably resolve network aliases, so we use explicit IPs
    // Use random second octet (100-254) to avoid subnet conflicts between test runs
    private static final java.util.Random RANDOM = new java.util.Random();
    private static final int IP_START = 2; // Gateway is .1, containers start at .2

    private final String subnetPrefix;

    private AetherCluster(int size, Path projectRoot) {
        this.projectRoot = projectRoot;
        // Generate unique subnet per cluster instance to avoid conflicts
        int secondOctet = 100 + RANDOM.nextInt(155); // 100-254
        int thirdOctet = RANDOM.nextInt(256); // 0-255
        this.subnetPrefix = "10." + secondOctet + "." + thirdOctet;
        System.out.println("[DEBUG] Using subnet: " + subnetPrefix + ".0/24");

        // Create network with unique subnet for predictable IPs
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

        // Build IP-based peer list
        var peerList = buildIpPeerList(size);
        System.out.println("[DEBUG] Using IP-based peer list: " + peerList);

        // Create nodes with predictable IPs and extra host entries
        for (int i = 1; i <= size; i++) {
            var nodeId = "node-" + i;
            var nodeIp = subnetPrefix + "." + (IP_START + i - 1);
            var node = AetherNodeContainer.aetherNode(nodeId, projectRoot, peerList)
                                          .withClusterNetwork(network);

            // Add extra host entries for all OTHER nodes
            for (int j = 1; j <= size; j++) {
                if (j != i) {
                    var otherNodeId = "node-" + j;
                    var otherIp = subnetPrefix + "." + (IP_START + j - 1);
                    node.withExtraHost(otherNodeId, otherIp);
                }
            }

            nodes.add(node);
            nodeMap.put(nodeId, node);
        }
    }

    private String buildIpPeerList(int size) {
        return IntStream.rangeClosed(1, size)
                        .mapToObj(i -> "node-" + i + ":" + subnetPrefix + "." + (IP_START + i - 1) + ":8090")
                        .collect(Collectors.joining(","));
    }

    /**
     * Creates a new cluster with the specified number of nodes.
     *
     * @param size number of nodes (typically 3 or 5 for quorum)
     * @param projectRoot path to project root (for Dockerfile context)
     * @return cluster instance (not yet started)
     */
    public static AetherCluster aetherCluster(int size, Path projectRoot) {
        if (size < 1) {
            throw new IllegalArgumentException("Cluster size must be at least 1");
        }
        return new AetherCluster(size, projectRoot);
    }

    /**
     * Starts all nodes in the cluster.
     * Uses hostname-based peer configuration since containers share a Docker network.
     */
    public void start() {
        System.out.println("[DEBUG] Starting cluster with " + nodes.size() + " nodes...");

        // Start nodes sequentially
        for (var node : nodes) {
            node.start();
            var ip = getContainerIp(node).or("unknown");
            System.out.println("[DEBUG] Node " + node.nodeId() + " started with IP: " + ip);
        }

        System.out.println("[DEBUG] All nodes started. Waiting for cluster formation...");
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

    /**
     * Starts all nodes in parallel for faster startup.
     * Use with caution - may cause cluster formation issues.
     */
    public void startParallel() {
        nodes.parallelStream().forEach(AetherNodeContainer::start);
    }

    /**
     * Waits for the cluster to reach quorum.
     *
     * @throws org.awaitility.core.ConditionTimeoutException if quorum not reached
     */
    public void awaitQuorum() {
        await().atMost(QUORUM_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(this::hasQuorum);
    }

    /**
     * Waits for all nodes to be healthy.
     */
    public void awaitAllHealthy() {
        await().atMost(QUORUM_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(this::allNodesHealthy);
    }

    /**
     * Waits for a slice to become ACTIVE, failing fast if it reaches FAILED state.
     *
     * @param artifact artifact to wait for (can be partial match)
     * @param timeout  maximum time to wait
     * @throws SliceDeploymentException if slice transitions to FAILED state
     * @throws org.awaitility.core.ConditionTimeoutException if timeout reached
     */
    public void awaitSliceActive(String artifact, Duration timeout) {
        await().atMost(timeout)
               .pollInterval(POLL_INTERVAL)
               .until(() -> checkSliceState(artifact));
    }

    /**
     * Waits for a slice to become ACTIVE on ALL nodes.
     * Use this for multi-instance deployments where the slice should be distributed.
     *
     * @param artifact artifact to wait for (can be partial match)
     * @param timeout  maximum time to wait
     * @throws SliceDeploymentException if slice transitions to FAILED state on any node
     */
    public void awaitSliceActiveOnAllNodes(String artifact, Duration timeout) {
        await().atMost(timeout)
               .pollInterval(POLL_INTERVAL)
               .until(() -> checkSliceStateOnAllNodes(artifact));
    }

    /**
     * Checks slice state on ALL nodes, throwing exception on FAILED, returning true when ALL are ACTIVE.
     */
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
    private static final Duration STUCK_STATE_THRESHOLD = Duration.ofMinutes(2);

    /**
     * Checks slice state, throwing exception on FAILED, returning true on ACTIVE.
     * Also detects when a slice is stuck in an intermediate state for too long.
     */
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

            // Detect stuck intermediate states (LOADING, ACTIVATING, etc.)
            if (isIntermediateState(state)) {
                var stuckSince = stuckStateStartTime.computeIfAbsent(artifact + ":" + state,
                    k -> System.currentTimeMillis());
                var stuckDuration = Duration.ofMillis(System.currentTimeMillis() - stuckSince);
                if (stuckDuration.compareTo(STUCK_STATE_THRESHOLD) > 0) {
                    var logs = getContainerLogs(node);
                    throw new SliceDeploymentException(
                        "Slice " + artifact + " stuck in " + state + " state for " +
                        stuckDuration.toSeconds() + "s. This indicates an infrastructure issue.\n" +
                        "\n=== Container Logs ===\n" + logs);
                }
            } else {
                // Clear tracking when state changes to non-intermediate
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

    /**
     * Gets the container logs, filtering for ERROR, WARN, and slice-related entries.
     */
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

    /**
     * Exception thrown when slice deployment fails.
     */
    public static class SliceDeploymentException extends RuntimeException {
        public SliceDeploymentException(String message) {
            super(message);
        }
    }

    /**
     * Waits for a leader to be elected.
     *
     * @throws org.awaitility.core.ConditionTimeoutException if leader not elected
     */
    public void awaitLeader() {
        await().atMost(QUORUM_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> leader().isPresent());
    }

    /**
     * Waits for a specific node count in the cluster.
     *
     * @param expectedCount expected number of active nodes
     */
    public void awaitNodeCount(int expectedCount) {
        await().atMost(QUORUM_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> activeNodeCount() == expectedCount);
    }

    /**
     * Returns any running node (for API operations).
     *
     * @return a running node
     * @throws IllegalStateException if no nodes are running
     */
    public AetherNodeContainer anyNode() {
        return nodes.stream()
                    .filter(AetherNodeContainer::isRunning)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No running nodes"));
    }

    /**
     * Returns a specific node by ID.
     *
     * @param nodeId node identifier
     * @return the node container
     * @throws NoSuchElementException if node not found
     */
    public AetherNodeContainer node(String nodeId) {
        var node = nodeMap.get(nodeId);
        if (node == null) {
            throw new NoSuchElementException("Node not found: " + nodeId);
        }
        return node;
    }

    /**
     * Returns all nodes in the cluster.
     */
    public List<AetherNodeContainer> nodes() {
        return Collections.unmodifiableList(nodes);
    }

    /**
     * Returns the current leader node (if determinable).
     *
     * @return leader node, or empty if not determinable
     */
    public Option<AetherNodeContainer> leader() {
        return Option.from(nodes.stream()
                    .filter(AetherNodeContainer::isRunning)
                    .filter(this::isLeader)
                    .findFirst());
    }

    /**
     * Kills a specific node.
     *
     * @param nodeId node to kill
     */
    public void killNode(String nodeId) {
        node(nodeId).stop();
    }

    /**
     * Restarts a previously killed node.
     *
     * @param nodeId node to restart
     */
    public void restartNode(String nodeId) {
        node(nodeId).start();
    }

    /**
     * Performs a rolling restart of all nodes.
     *
     * @param delayBetweenNodes delay between restarting each node
     */
    public void rollingRestart(Duration delayBetweenNodes) {
        for (var node : nodes) {
            node.stop();
            sleep(delayBetweenNodes);
            node.start();
            awaitQuorum();
        }
    }

    /**
     * Returns the number of running nodes.
     */
    public int runningNodeCount() {
        return (int) nodes.stream()
                          .filter(AetherNodeContainer::isRunning)
                          .count();
    }

    /**
     * Returns the cluster size (total nodes, running or not).
     */
    public int size() {
        return nodes.size();
    }

    @Override
    public void close() {
        nodes.forEach(AetherNodeContainer::stop);
        network.close();
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
        return nodes.stream()
                    .filter(AetherNodeContainer::isRunning)
                    .allMatch(node -> {
                        try {
                            var health = node.getHealth();
                            return !health.contains("\"error\"") && health.contains("\"ready\":true");
                        } catch (Exception e) {
                            return false;
                        }
                    });
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

    private void sleep(Duration duration) {
        try {
            TimeUnit.MILLISECONDS.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
