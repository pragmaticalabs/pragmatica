package org.pragmatica.aether.e2e.containers;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Testcontainer wrapper for Aether Node.
 *
 * <p>Provides programmatic control over Aether node instances for E2E testing.
 * Each container exposes:
 * <ul>
 *   <li>Management port (8080) - HTTP API for cluster management</li>
 *   <li>Cluster port (8090) - Internal cluster communication</li>
 * </ul>
 */
public class AetherNodeContainer extends GenericContainer<AetherNodeContainer> {
    private static final int MANAGEMENT_PORT = 8080;
    private static final int CLUSTER_PORT = 8090;
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(60);

    private final String nodeId;
    private final HttpClient httpClient;

    private AetherNodeContainer(ImageFromDockerfile image, String nodeId) {
        super(image);
        this.nodeId = nodeId;
        this.httpClient = HttpClient.newBuilder()
                                    .connectTimeout(Duration.ofSeconds(5))
                                    .build();
    }

    /**
     * Creates a new Aether node container with the specified node ID.
     *
     * @param nodeId unique identifier for this node
     * @param projectRoot path to the project root (for Dockerfile context)
     * @return configured container (not yet started)
     */
    public static AetherNodeContainer aetherNode(String nodeId, Path projectRoot) {
        var image = new ImageFromDockerfile("aether-node-test", false)
            .withDockerfile(projectRoot.resolve("docker/aether-node/Dockerfile"))
            .withFileFromPath(".", projectRoot);

        var container = new AetherNodeContainer(image, nodeId);
        container.withExposedPorts(MANAGEMENT_PORT, CLUSTER_PORT)
                 .withEnv("NODE_ID", nodeId)
                 .withEnv("CLUSTER_PORT", String.valueOf(CLUSTER_PORT))
                 .withEnv("MANAGEMENT_PORT", String.valueOf(MANAGEMENT_PORT))
                 .withEnv("JAVA_OPTS", "-Xmx256m -XX:+UseZGC")
                 .waitingFor(Wait.forHttp("/health")
                                 .forPort(MANAGEMENT_PORT)
                                 .forStatusCode(200)
                                 .withStartupTimeout(STARTUP_TIMEOUT))
                 .withNetworkAliases(nodeId);
        return container;
    }

    /**
     * Creates a node container configured to join an existing cluster.
     *
     * @param nodeId unique identifier for this node
     * @param projectRoot path to the project root
     * @param peers comma-separated peer addresses (format: nodeId:host:port,...)
     * @return configured container
     */
    public static AetherNodeContainer aetherNode(String nodeId, Path projectRoot, String peers) {
        var container = aetherNode(nodeId, projectRoot);
        container.withEnv("PEERS", peers);
        return container;
    }

    /**
     * Configures this container to use the specified network.
     */
    public AetherNodeContainer withClusterNetwork(Network network) {
        withNetwork(network);
        return this;
    }

    /**
     * Returns the node ID for this container.
     */
    public String nodeId() {
        return nodeId;
    }

    /**
     * Returns the mapped management port on the host.
     */
    public int managementPort() {
        return getMappedPort(MANAGEMENT_PORT);
    }

    /**
     * Returns the mapped cluster port on the host.
     */
    public int clusterPort() {
        return getMappedPort(CLUSTER_PORT);
    }

    /**
     * Returns the management API base URL.
     */
    public String managementUrl() {
        return "http://" + getHost() + ":" + managementPort();
    }

    /**
     * Returns the internal cluster address for peer configuration.
     */
    public String clusterAddress() {
        return nodeId + ":" + getNetworkAliases().getFirst() + ":" + CLUSTER_PORT;
    }

    // ===== API Helpers =====

    /**
     * Fetches the node health status.
     *
     * @return health response JSON
     */
    public String getHealth() {
        return get("/health");
    }

    /**
     * Fetches the cluster status.
     *
     * @return status response JSON
     */
    public String getStatus() {
        return get("/status");
    }

    /**
     * Fetches the list of active nodes.
     *
     * @return nodes response JSON
     */
    public String getNodes() {
        return get("/nodes");
    }

    /**
     * Fetches the list of deployed slices.
     *
     * @return slices response JSON
     */
    public String getSlices() {
        return get("/slices");
    }

    /**
     * Fetches cluster metrics.
     *
     * @return metrics response JSON
     */
    public String getMetrics() {
        return get("/metrics");
    }

    /**
     * Deploys a slice to the cluster.
     *
     * @param artifact artifact coordinates (group:artifact:version)
     * @param instances number of instances
     * @return deployment response JSON
     */
    public String deploy(String artifact, int instances) {
        var body = "{\"artifact\":\"" + artifact + "\",\"instances\":" + instances + "}";
        return post("/deploy", body);
    }

    /**
     * Scales a deployed slice.
     *
     * @param artifact artifact coordinates
     * @param instances target instance count
     * @return scale response JSON
     */
    public String scale(String artifact, int instances) {
        var body = "{\"artifact\":\"" + artifact + "\",\"instances\":" + instances + "}";
        return post("/scale", body);
    }

    /**
     * Undeploys a slice from the cluster.
     *
     * @param artifact artifact coordinates
     * @return undeploy response JSON
     */
    public String undeploy(String artifact) {
        var body = "{\"artifact\":\"" + artifact + "\"}";
        return post("/undeploy", body);
    }

    /**
     * Applies a blueprint to the cluster.
     *
     * @param blueprint blueprint content (TOML format)
     * @return apply response JSON
     */
    public String applyBlueprint(String blueprint) {
        return post("/blueprint", blueprint);
    }

    // ===== HTTP Helpers =====

    private String get(String path) {
        try {
            var request = HttpRequest.newBuilder()
                                     .uri(URI.create(managementUrl() + path))
                                     .GET()
                                     .timeout(Duration.ofSeconds(10))
                                     .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private String post(String path, String body) {
        try {
            var request = HttpRequest.newBuilder()
                                     .uri(URI.create(managementUrl() + path))
                                     .header("Content-Type", "application/json")
                                     .POST(HttpRequest.BodyPublishers.ofString(body))
                                     .timeout(Duration.ofSeconds(10))
                                     .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }
}
