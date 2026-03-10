package org.pragmatica.aether.e2e.containers;

import com.github.dockerjava.api.model.HealthCheck;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.containers.BindMode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Future;

/// Testcontainer wrapper for Aether Node.
///
///
/// All containers run on a shared bridge network with standard internal ports.
/// Tests access management API via Testcontainers' random mapped ports.
/// Inter-container communication uses DNS (container aliases as hostnames).
public class AetherNodeContainer extends GenericContainer<AetherNodeContainer> {
    static final int MANAGEMENT_PORT = 8080;
    static final int CLUSTER_PORT = 8090;
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(120);
    private static final String IMAGE_NAME = "aether-node-e2e";
    private static final String E2E_IMAGE_ENV = "AETHER_E2E_IMAGE";

    /// Blueprint ID used by the deploy() convenience method for E2E tests.
    public static final String E2E_BLUEPRINT_ID = "e2e.test:deploy:1.0.0";

    // Cached image - built once, reused across all containers
    private static volatile Future<String> cachedImage;
    private static volatile Path cachedProjectRoot;
    private final String nodeId;
    private final HttpClient httpClient;

    private AetherNodeContainer(Future<String> image, String nodeId) {
        super(image);
        this.nodeId = nodeId;
        this.httpClient = HttpClient.newBuilder()
                                    .connectTimeout(Duration.ofSeconds(5))
                                    .build();
    }

    private AetherNodeContainer(DockerImageName imageName, String nodeId) {
        super(imageName);
        this.nodeId = nodeId;
        this.httpClient = HttpClient.newBuilder()
                                    .connectTimeout(Duration.ofSeconds(5))
                                    .build();
    }

    // Local Maven repository path - mounted into containers for artifact resolution
    private static final Path M2_REPO_PATH = Path.of(System.getProperty("user.home"), ".m2", "repository");
    private static final String CONTAINER_M2_PATH = "/home/aether/.m2/repository";

    /// Creates a node container configured for cluster testing on a shared bridge network.
    ///
    ///
    /// All containers use identical internal ports (8080 management, 8090 cluster).
    /// The management port is exposed with random mapping for test access.
    /// Inter-container communication uses DNS via network aliases.
    ///
    ///
    /// @param nodeId unique identifier for this node (also used as DNS hostname)
    /// @param projectRoot path to the project root (for Dockerfile context)
    /// @param peers comma-separated peer addresses (format: nodeId:nodeId:8090,...)
    /// @param network shared bridge network for the cluster
    /// @return configured container (not yet started)
    public static AetherNodeContainer aetherNode(String nodeId, Path projectRoot,
                                                  String peers, Network network) {
        var container = createContainer(nodeId, projectRoot);
        disableDockerHealthcheck(container);
        container.withExposedPorts(MANAGEMENT_PORT)
                 .withEnv("NODE_ID", nodeId)
                 .withEnv("CLUSTER_PORT", String.valueOf(CLUSTER_PORT))
                 .withEnv("MANAGEMENT_PORT", String.valueOf(MANAGEMENT_PORT))
                 .withEnv("CLUSTER_PEERS", peers)
                 .withEnv("JAVA_OPTS", "-Xmx256m -XX:+UseZGC")
                 .withNetwork(network)
                 .withNetworkAliases(nodeId)
                 .waitingFor(Wait.forHttp("/api/health")
                                 .forPort(MANAGEMENT_PORT)
                                 .forStatusCode(200)
                                 .withStartupTimeout(STARTUP_TIMEOUT));
        mountLocalMavenRepo(container);
        return container;
    }

    /// Mounts the local Maven repository into the container for artifact resolution.
    private static void mountLocalMavenRepo(AetherNodeContainer container) {
        if (Files.isDirectory(M2_REPO_PATH)) {
            container.withFileSystemBind(M2_REPO_PATH.toString(), CONTAINER_M2_PATH, BindMode.READ_ONLY);
        }
    }

    /// Disables the Docker HEALTHCHECK inherited from the Dockerfile.
    /// Testcontainers 1.21+ treats Docker HEALTHCHECK as a startup gate with a 5-second
    /// default timeout, which is too short for JVM startup. Disabling it lets the explicit
    /// Wait.forHttp() strategy handle readiness with proper timeouts.
    private static void disableDockerHealthcheck(AetherNodeContainer container) {
        container.withCreateContainerCmdModifier(cmd ->
            cmd.withHealthcheck(new HealthCheck().withTest(List.of("NONE"))));
    }

    private static AetherNodeContainer createContainer(String nodeId, Path projectRoot) {
        var prebuiltImageName = System.getenv(E2E_IMAGE_ENV);
        if (prebuiltImageName != null && !prebuiltImageName.isBlank()) {
            return new AetherNodeContainer(DockerImageName.parse(prebuiltImageName), nodeId);
        }
        return new AetherNodeContainer(getOrBuildImage(projectRoot), nodeId);
    }

    /// Gets the cached image or builds it if not yet available.
    /// Thread-safe - only one build will occur even with concurrent access.
    private static synchronized Future<String> getOrBuildImage(Path projectRoot) {
        // Return cached image if available and project root matches
        if (cachedImage != null && projectRoot.equals(cachedProjectRoot)) {
            return cachedImage;
        }

        var jarPath = resolveExistingPath(projectRoot,
            "aether/node/target/aether-node.jar",
            "../node/target/aether-node.jar",
            "node/target/aether-node.jar");
        var dockerfilePath = resolveExistingPath(projectRoot,
            "aether/docker/aether-node/Dockerfile",
            "../docker/aether-node/Dockerfile",
            "docker/aether-node/Dockerfile");
        var configPath = resolveExistingPath(projectRoot,
            "aether/docker/aether-node/aether.toml",
            "../docker/aether-node/aether.toml",
            "docker/aether-node/aether.toml");

        if (jarPath == null) {
            throw new IllegalStateException(
                "aether-node.jar not found. Tried:\n  " +
                projectRoot.resolve("aether/node/target/aether-node.jar") + "\n  " +
                projectRoot.resolve("../node/target/aether-node.jar").normalize() + "\n  " +
                projectRoot.resolve("node/target/aether-node.jar") +
                "\nRun 'mvn package -pl aether/node' first.");
        }

        var image = new ImageFromDockerfile(IMAGE_NAME, false)
            .withFileFromPath("Dockerfile", dockerfilePath)
            .withFileFromPath("aether-node.jar", jarPath)
            .withFileFromPath("aether.toml", configPath)
            .withBuildArg("JAR_PATH", "aether-node.jar")
            .withBuildArg("CONFIG_PATH", "aether.toml");

        cachedImage = image;
        cachedProjectRoot = projectRoot;
        return image;
    }

    /// Resolves the first existing path from the list of candidates.
    private static Path resolveExistingPath(Path root, String... candidates) {
        for (var candidate : candidates) {
            var path = root.resolve(candidate);
            if (Files.exists(path)) {
                return path;
            }
        }
        return null;
    }

    /// Returns the node ID for this container.
    public String nodeId() {
        return nodeId;
    }

    /// Returns the management API base URL using Testcontainers' mapped port.
    public String managementUrl() {
        return "http://" + getHost() + ":" + getMappedPort(MANAGEMENT_PORT);
    }

    /// Returns the cluster address for peer configuration.
    /// Uses container alias as DNS hostname for inter-container communication.
    public String clusterAddress() {
        return nodeId + ":" + nodeId + ":" + CLUSTER_PORT;
    }

    // ===== API Helpers =====

    /// Fetches the node health status.
    public String getHealth() {
        return get("/api/health");
    }

    /// Fetches the cluster status.
    public String getStatus() {
        return get("/api/status");
    }

    /// Fetches the list of active nodes.
    public String getNodes() {
        return get("/api/nodes");
    }

    /// Fetches the list of deployed slices.
    public String getSlices() {
        return get("/api/slices");
    }

    /// Fetches cluster metrics.
    public String getMetrics() {
        return get("/api/metrics");
    }

    /// Deploys a slice to the cluster via blueprint API with retry logic.
    /// Consensus operations may occasionally timeout during cluster formation.
    ///
    /// @param artifact artifact coordinates (group:artifact:version)
    /// @param instances number of instances
    /// @return deployment response JSON
    public String deploy(String artifact, int instances) {
        var blueprint = """
            id = "%s"

            [[slices]]
            artifact = "%s"
            instances = %d
            """.formatted(E2E_BLUEPRINT_ID, artifact, instances);
        return postWithRetry("/api/blueprint", blueprint, 5, Duration.ofSeconds(5));
    }

    /// POST request with retry logic for consensus operations.
    private String postWithRetry(String path, String body, int maxRetries, Duration retryDelay) {
        String lastResponse = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            lastResponse = post(path, body);
            if (!lastResponse.contains("\"error\"")) {
                return lastResponse;
            }
            System.out.println("[DEBUG] POST " + path + " attempt " + attempt + " failed: " + lastResponse);
            if (attempt < maxRetries) {
                try {
                    Thread.sleep(retryDelay.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return lastResponse;
    }

    /// Scales a deployed slice.
    public String scale(String artifact, int instances) {
        var body = "{\"artifact\":\"" + artifact + "\",\"instances\":" + instances + "}";
        return post("/api/scale", body);
    }

    /// Undeploys a slice from the cluster by deleting the E2E blueprint.
    public String undeploy(String artifact) {
        return delete("/api/blueprint/" + E2E_BLUEPRINT_ID);
    }

    /// Applies a blueprint to the cluster.
    public String applyBlueprint(String blueprint) {
        return post("/api/blueprint", blueprint);
    }

    /// Lists all applied blueprints.
    public String listBlueprints() {
        return get("/api/blueprints");
    }

    /// Deletes a specific blueprint and undeploys its slices.
    public String deleteBlueprint(String blueprintId) {
        return delete("/api/blueprint/" + blueprintId);
    }

    // ===== Artifact Upload (Maven Protocol) =====

    /// Uploads an artifact to the DHT via Maven protocol.
    public boolean uploadArtifact(String groupPath, String artifactId, String version, Path jarPath) {
        try {
            var jarContent = Files.readAllBytes(jarPath);
            return uploadArtifactBytes(groupPath, artifactId, version, jarContent);
        } catch (Exception e) {
            System.out.println("[DEBUG] Artifact upload error: " + e.getMessage());
            return false;
        }
    }

    /// Uploads pre-built artifact bytes to the DHT via Maven protocol.
    public boolean uploadArtifactBytes(String groupPath, String artifactId, String version, byte[] jarContent) {
        try {
            var remotePath = "/repository/" + groupPath + "/" + artifactId + "/" + version +
                             "/" + artifactId + "-" + version + ".jar";
            System.out.println("[DEBUG] Uploading artifact to " + remotePath + " (" + jarContent.length + " bytes)");
            var response = putBinary(remotePath, jarContent);
            var success = !response.contains("\"error\"");
            if (success) {
                System.out.println("[DEBUG] Artifact upload succeeded: " + remotePath);
            } else {
                System.out.println("[DEBUG] Artifact upload failed: " + response);
            }
            return success;
        } catch (Exception e) {
            System.out.println("[DEBUG] Artifact upload error: " + e.getMessage());
            return false;
        }
    }

    /// Performs a PUT request with binary body.
    public String putBinary(String path, byte[] body) {
        try {
            var request = HttpRequest.newBuilder()
                                     .uri(URI.create(managementUrl() + path))
                                     .header("Content-Type", "application/octet-stream")
                                     .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                                     .timeout(Duration.ofSeconds(60))
                                     .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    /// Performs a GET request returning binary content.
    public byte[] getBinary(String path) {
        try {
            var request = HttpRequest.newBuilder()
                                     .uri(URI.create(managementUrl() + path))
                                     .GET()
                                     .timeout(Duration.ofSeconds(60))
                                     .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 400) {
                return new byte[0];
            }
            return response.body();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    /// Fetches artifact metadata from the repository.
    public String getArtifactInfo(String groupPath, String artifactId, String version) {
        return get("/repository/info/" + groupPath + "/" + artifactId + "/" + version);
    }

    /// Downloads an artifact JAR via Maven protocol.
    public byte[] downloadArtifact(String groupPath, String artifactId, String version) {
        var path = "/repository/" + groupPath + "/" + artifactId + "/" + version +
                   "/" + artifactId + "-" + version + ".jar";
        return getBinary(path);
    }

    // ===== HTTP Helpers =====

    /// Performs a GET request to the management API.
    public String get(String path) {
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

    /// Performs a POST request to the management API.
    public String post(String path, String body) {
        try {
            var request = HttpRequest.newBuilder()
                                     .uri(URI.create(managementUrl() + path))
                                     .header("Content-Type", "application/json")
                                     .POST(HttpRequest.BodyPublishers.ofString(body))
                                     .timeout(Duration.ofSeconds(60))
                                     .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    /// Performs a DELETE request to the management API.
    public String delete(String path) {
        try {
            var request = HttpRequest.newBuilder()
                                     .uri(URI.create(managementUrl() + path))
                                     .DELETE()
                                     .timeout(Duration.ofSeconds(10))
                                     .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // ===== Metrics API =====

    /// Fetches Prometheus-formatted metrics.
    public String getPrometheusMetrics() {
        return get("/api/metrics/prometheus");
    }

    /// Fetches invocation metrics for all methods.
    public String getInvocationMetrics() {
        return get("/api/invocation-metrics");
    }

    /// Fetches invocation metrics with optional filtering.
    public String getInvocationMetrics(String artifact, String method) {
        var params = new StringBuilder();
        if (artifact != null) {
            params.append("artifact=").append(artifact);
        }
        if (method != null) {
            if (!params.isEmpty()) params.append("&");
            params.append("method=").append(method);
        }
        var path = params.isEmpty() ? "/api/invocation-metrics" : "/api/invocation-metrics?" + params;
        return get(path);
    }

    /// Fetches slow invocation records.
    public String getSlowInvocations() {
        return get("/api/invocation-metrics/slow");
    }

    /// Fetches current threshold strategy configuration.
    public String getInvocationStrategy() {
        return get("/api/invocation-metrics/strategy");
    }

    // ===== Threshold & Alert API =====

    /// Fetches all configured thresholds.
    public String getThresholds() {
        return get("/api/thresholds");
    }

    /// Sets an alert threshold for a metric.
    public String setThreshold(String metric, double warning, double critical) {
        var body = "{\"metric\":\"" + metric + "\",\"warning\":" + warning + ",\"critical\":" + critical + "}";
        return post("/api/thresholds", body);
    }

    /// Deletes a threshold for a metric.
    public String deleteThreshold(String metric) {
        return delete("/api/thresholds/" + metric);
    }

    /// Fetches all alerts (active and history).
    public String getAlerts() {
        return get("/api/alerts");
    }

    /// Fetches active alerts only.
    public String getActiveAlerts() {
        return get("/api/alerts/active");
    }

    /// Fetches alert history.
    public String getAlertHistory() {
        return get("/api/alerts/history");
    }

    /// Clears all alerts.
    public String clearAlerts() {
        return post("/api/alerts/clear", "{}");
    }

    // ===== TTM API =====

    /// Fetches TTM (Tiny Time Mixers) status.
    public String getTtmStatus() {
        return get("/api/ttm/status");
    }

    // ===== Controller API =====

    /// Fetches controller configuration.
    public String getControllerConfig() {
        return get("/api/controller/config");
    }

    /// Fetches controller status (enabled/disabled).
    public String getControllerStatus() {
        return get("/api/controller/status");
    }

    /// Updates controller configuration.
    public String setControllerConfig(String config) {
        return post("/api/controller/config", config);
    }

    /// Triggers immediate controller evaluation.
    public String triggerControllerEvaluation() {
        return post("/api/controller/evaluate", "{}");
    }

    // ===== Rolling Update API =====

    /// Starts a rolling update.
    public String startRollingUpdate(String oldVersion, String newVersion) {
        var body = "{\"oldVersion\":\"" + oldVersion + "\",\"newVersion\":\"" + newVersion + "\"}";
        return post("/api/rolling-update/start", body);
    }

    /// Gets all active rolling updates.
    public String getRollingUpdates() {
        return get("/api/rolling-updates");
    }

    /// Gets status of a specific rolling update.
    public String getRollingUpdateStatus(String updateId) {
        return get("/api/rolling-update/" + updateId);
    }

    /// Adjusts traffic routing during rolling update.
    public String setRollingUpdateRouting(String updateId, int oldWeight, int newWeight) {
        var body = "{\"oldWeight\":" + oldWeight + ",\"newWeight\":" + newWeight + "}";
        return post("/api/rolling-update/" + updateId + "/routing", body);
    }

    /// Completes a rolling update.
    public String completeRollingUpdate(String updateId) {
        return post("/api/rolling-update/" + updateId + "/complete", "{}");
    }

    /// Rolls back a rolling update.
    public String rollbackRollingUpdate(String updateId) {
        return post("/api/rolling-update/" + updateId + "/rollback", "{}");
    }

    // ===== Slice Status API =====

    /// Fetches detailed slice status with health per instance.
    public String getSlicesStatus() {
        return get("/api/slices/status");
    }

    /// Checks if a slice is in FAILED state.
    public boolean isSliceFailed(String artifact) {
        return "FAILED".equals(getSliceState(artifact));
    }

    /// Checks if a slice is in ACTIVE state.
    public boolean isSliceActive(String artifact) {
        return "ACTIVE".equals(getSliceState(artifact));
    }

    /// Gets the current state of a slice.
    public String getSliceState(String artifact) {
        var status = getSlicesStatus();
        if (!status.contains(artifact)) {
            return "NOT_FOUND";
        }
        var statePattern = java.util.regex.Pattern.compile(
            "\"artifact\":\"[^\"]*" + java.util.regex.Pattern.quote(artifact) + "[^\"]*\",\"state\":\"([A-Z_]+)\"");
        var matcher = statePattern.matcher(status);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "UNKNOWN";
    }

    // ===== Slice Invocation API =====

    /// Invokes a slice method via HTTP router.
    public String invokeSlice(String httpMethod, String path, String body) {
        return switch (httpMethod.toUpperCase()) {
            case "GET" -> get(path);
            case "POST" -> post(path, body);
            case "DELETE" -> delete(path);
            default -> "{\"error\":\"Unsupported HTTP method: " + httpMethod + "\"}";
        };
    }

    /// Invokes a slice method with GET.
    public String invokeGet(String path) {
        return get(path);
    }

    /// Invokes a slice method with POST.
    public String invokePost(String path, String body) {
        return post(path, body);
    }

    // ===== Node Lifecycle API =====

    /// Initiates drain on a node, transitioning it from ON_DUTY to DRAINING.
    public String drainNode(String nodeId) {
        return post("/api/node/drain/" + nodeId, "{}");
    }

    /// Activates a drained or decommissioned node, returning it to ON_DUTY.
    public String activateNode(String nodeId) {
        return post("/api/node/activate/" + nodeId, "{}");
    }

    /// Initiates remote shutdown of a node, transitioning it to SHUTTING_DOWN.
    public String shutdownNode(String nodeId) {
        return post("/api/node/shutdown/" + nodeId, "{}");
    }

    /// Fetches lifecycle state for a specific node.
    public String getNodeLifecycle(String nodeId) {
        return get("/api/node/lifecycle/" + nodeId);
    }

    /// Fetches lifecycle states for all nodes in the cluster.
    public String getAllNodeLifecycles() {
        return get("/api/nodes/lifecycle");
    }

    // ===== Routes API =====

    /// Fetches all registered routes.
    public String getRoutes() {
        return get("/api/routes");
    }
}
