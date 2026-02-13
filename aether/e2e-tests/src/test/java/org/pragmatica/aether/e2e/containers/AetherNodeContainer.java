package org.pragmatica.aether.e2e.containers;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Future;

/// Testcontainer wrapper for Aether Node.
///
///
/// Provides programmatic control over Aether node instances for E2E testing.
/// Each container exposes:
///
///   - Management port (8080) - HTTP API for cluster management
///   - Cluster port (8090) - Internal cluster communication
///
///
///
/// Image selection strategy:
/// <ol>
///   - If AETHER_E2E_IMAGE env var is set, use that image (for CI with pre-built images)
///   - Otherwise, build from Dockerfile (cached for all test containers)
/// </ol>
public class AetherNodeContainer extends GenericContainer<AetherNodeContainer> {
    private static final int MANAGEMENT_PORT = 8080;
    private static final int CLUSTER_PORT = 8090;
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(60);
    private static final String IMAGE_NAME = "aether-node-e2e";
    private static final String E2E_IMAGE_ENV = "AETHER_E2E_IMAGE";

    // Cached image - built once, reused across all containers
    private static volatile Future<String> cachedImage;
    private static volatile Path cachedProjectRoot;
    private static volatile DockerImageName prebuiltImage;

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

    // Test artifact paths relative to Maven repository
    // Note: Uses slice artifact IDs (echo-slice-echo-service), not module artifact IDs (echo-slice)
    private static final String TEST_GROUP_PATH = "org/pragmatica-lite/aether/test";
    private static final String TEST_ARTIFACT_VERSION = System.getProperty("project.version", "0.16.0");
    private static final String[] TEST_ARTIFACTS = {
        "echo-slice-echo-service/" + TEST_ARTIFACT_VERSION + "/echo-slice-echo-service-" + TEST_ARTIFACT_VERSION + ".jar",
        "echo-slice-echo-service/0.16.0/echo-slice-echo-service-0.16.0.jar"
    };

    /// Creates a new Aether node container with the specified node ID.
    ///
    ///
    /// Image selection:
    ///
    ///   - If AETHER_E2E_IMAGE env var is set, uses that pre-built image
    ///   - Otherwise, builds from Dockerfile (cached for subsequent containers)
    ///
    ///
    /// @param nodeId unique identifier for this node
    /// @param projectRoot path to the project root (for Dockerfile context, ignored if using pre-built)
    /// @return configured container (not yet started)
    public static AetherNodeContainer aetherNode(String nodeId, Path projectRoot) {
        var container = createContainer(nodeId, projectRoot);
        container.withExposedPorts(MANAGEMENT_PORT, CLUSTER_PORT)
                 .withEnv("NODE_ID", nodeId)
                 .withEnv("CLUSTER_PORT", String.valueOf(CLUSTER_PORT))
                 .withEnv("MANAGEMENT_PORT", String.valueOf(MANAGEMENT_PORT))
                 .withEnv("JAVA_OPTS", "-Xmx256m -XX:+UseZGC")
                 .waitingFor(Wait.forHttp("/api/health")
                                 .forPort(MANAGEMENT_PORT)
                                 .forStatusCode(200)
                                 .withStartupTimeout(STARTUP_TIMEOUT))
                 .withNetworkAliases(nodeId);

        // Copy specific test artifacts into container
        // (more reliable than bind mount across different container runtimes)
        copyTestArtifacts(container);

        return container;
    }

    /// Copies test slice artifacts into the container's Maven repository.
    private static void copyTestArtifacts(AetherNodeContainer container) {
        for (var artifact : TEST_ARTIFACTS) {
            var hostPath = M2_REPO_PATH.resolve(TEST_GROUP_PATH).resolve(artifact);
            var containerPath = CONTAINER_M2_PATH + "/" + TEST_GROUP_PATH + "/" + artifact;

            if (Files.exists(hostPath)) {
                container.withCopyFileToContainer(
                    MountableFile.forHostPath(hostPath, 0644),
                    containerPath);
            } else {
                System.err.println("[WARN] Test artifact not found: " + hostPath);
            }
        }
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

        // Build and cache the image
        // Try multiple paths based on where projectRoot points:
        // - CI: repo root → aether/node/target/
        // - Local from e2e-tests: ../node/target/ (go up to aether/, then into node/)
        var jarPath = resolveExistingPath(projectRoot,
            "aether/node/target/aether-node.jar",   // CI: from repo root
            "../node/target/aether-node.jar",        // Local: from aether/e2e-tests/
            "node/target/aether-node.jar");          // Fallback
        var dockerfilePath = resolveExistingPath(projectRoot,
            "aether/docker/aether-node/Dockerfile",  // CI: from repo root
            "../docker/aether-node/Dockerfile",      // Local: from aether/e2e-tests/
            "docker/aether-node/Dockerfile");
        var configPath = resolveExistingPath(projectRoot,
            "aether/docker/aether-node/aether.toml",  // CI: from repo root
            "../docker/aether-node/aether.toml",      // Local: from aether/e2e-tests/
            "docker/aether-node/aether.toml");

        if (jarPath == null) {
            throw new IllegalStateException(
                "aether-node.jar not found. Tried:\n  " +
                projectRoot.resolve("aether/node/target/aether-node.jar") + "\n  " +
                projectRoot.resolve("../node/target/aether-node.jar").normalize() + "\n  " +
                projectRoot.resolve("node/target/aether-node.jar") +
                "\nRun 'mvn package -pl aether/node' first.");
        }

        // Build image once with caching disabled (deleteOnExit=false keeps it cached)
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
    ///
    /// @param root base path to resolve against
    /// @param candidates paths to try in order
    /// @return first existing path or null if none exist
    private static Path resolveExistingPath(Path root, String... candidates) {
        for (var candidate : candidates) {
            var path = root.resolve(candidate);
            if (Files.exists(path)) {
                return path;
            }
        }
        return null;
    }

    /// Creates a node container configured to join an existing cluster.
    ///
    /// @param nodeId unique identifier for this node
    /// @param projectRoot path to the project root
    /// @param peers comma-separated peer addresses (format: nodeId:host:port,...)
    /// @return configured container
    public static AetherNodeContainer aetherNode(String nodeId, Path projectRoot, String peers) {
        var container = aetherNode(nodeId, projectRoot);
        container.withEnv("CLUSTER_PEERS", peers);
        return container;
    }

    /// Configures this container to use the specified network.
    public AetherNodeContainer withClusterNetwork(Network network) {
        withNetwork(network);
        return this;
    }

    /// Returns the node ID for this container.
    public String nodeId() {
        return nodeId;
    }

    /// Returns the mapped management port on the host.
    public int managementPort() {
        return getMappedPort(MANAGEMENT_PORT);
    }

    /// Returns the mapped cluster port on the host.
    public int clusterPort() {
        return getMappedPort(CLUSTER_PORT);
    }

    /// Returns the management API base URL.
    public String managementUrl() {
        return "http://" + getHost() + ":" + managementPort();
    }

    /// Returns the internal cluster address for peer configuration.
    public String clusterAddress() {
        return nodeId + ":" + getNetworkAliases().getFirst() + ":" + CLUSTER_PORT;
    }

    // ===== API Helpers =====

    /// Fetches the node health status.
    ///
    /// @return health response JSON
    public String getHealth() {
        return get("/api/health");
    }

    /// Fetches the cluster status.
    ///
    /// @return status response JSON
    public String getStatus() {
        return get("/api/status");
    }

    /// Fetches the list of active nodes.
    ///
    /// @return nodes response JSON
    public String getNodes() {
        return get("/api/nodes");
    }

    /// Fetches the list of deployed slices.
    ///
    /// @return slices response JSON
    public String getSlices() {
        return get("/api/slices");
    }

    /// Fetches cluster metrics.
    ///
    /// @return metrics response JSON
    public String getMetrics() {
        return get("/api/metrics");
    }

    /// Deploys a slice to the cluster with retry logic.
    /// Consensus operations may occasionally timeout during cluster formation.
    ///
    /// @param artifact artifact coordinates (group:artifact:version)
    /// @param instances number of instances
    /// @return deployment response JSON
    public String deploy(String artifact, int instances) {
        var body = "{\"artifact\":\"" + artifact + "\",\"instances\":" + instances + "}";
        return postWithRetry("/api/deploy", body, 3, Duration.ofSeconds(2));
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
    ///
    /// @param artifact artifact coordinates
    /// @param instances target instance count
    /// @return scale response JSON
    public String scale(String artifact, int instances) {
        var body = "{\"artifact\":\"" + artifact + "\",\"instances\":" + instances + "}";
        return post("/api/scale", body);
    }

    /// Undeploys a slice from the cluster.
    ///
    /// @param artifact artifact coordinates
    /// @return undeploy response JSON
    public String undeploy(String artifact) {
        var body = "{\"artifact\":\"" + artifact + "\"}";
        return post("/api/undeploy", body);
    }

    /// Applies a blueprint to the cluster.
    ///
    /// @param blueprint blueprint content (TOML format)
    /// @return apply response JSON
    public String applyBlueprint(String blueprint) {
        return post("/api/blueprint", blueprint);
    }

    // ===== Artifact Upload (Maven Protocol) =====

    /// Uploads an artifact to the DHT via Maven protocol.
    /// This is required for slice deployment to work - artifacts must be in DHT, not local filesystem.
    ///
    /// @param groupPath group path with slashes (e.g., "org/pragmatica-lite/aether/test")
    /// @param artifactId artifact ID
    /// @param version version
    /// @param jarPath path to local jar file
    /// @return true if upload succeeded
    public boolean uploadArtifact(String groupPath, String artifactId, String version, Path jarPath) {
        try {
            var jarContent = Files.readAllBytes(jarPath);
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
    ///
    /// @param path API path
    /// @param body binary content
    /// @return response body
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
    ///
    /// @param path API path
    /// @return response bytes, or empty array on error
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
    ///
    /// @param groupPath group path with slashes
    /// @param artifactId artifact ID
    /// @param version version string
    /// @return artifact info JSON
    public String getArtifactInfo(String groupPath, String artifactId, String version) {
        return get("/repository/info/" + groupPath + "/" + artifactId + "/" + version);
    }

    /// Downloads an artifact JAR via Maven protocol.
    ///
    /// @param groupPath group path with slashes
    /// @param artifactId artifact ID
    /// @param version version string
    /// @return JAR bytes, or empty array on error
    public byte[] downloadArtifact(String groupPath, String artifactId, String version) {
        var path = "/repository/" + groupPath + "/" + artifactId + "/" + version +
                   "/" + artifactId + "-" + version + ".jar";
        return getBinary(path);
    }

    // ===== HTTP Helpers =====

    /// Performs a GET request to the management API.
    ///
    /// @param path API path (e.g., "/health")
    /// @return response body JSON
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
    ///
    /// @param path API path (e.g., "/deploy")
    /// @param body request body JSON
    /// @return response body JSON
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
    ///
    /// @param path API path (e.g., "/thresholds/cpu")
    /// @return response body JSON
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
    ///
    /// @return metrics in Prometheus text format
    public String getPrometheusMetrics() {
        return get("/api/metrics/prometheus");
    }

    /// Fetches invocation metrics for all methods.
    ///
    /// @return invocation metrics JSON
    public String getInvocationMetrics() {
        return get("/api/invocation-metrics");
    }

    /// Fetches invocation metrics with optional filtering.
    ///
    /// @param artifact artifact filter (partial match, null to skip)
    /// @param method method filter (exact match, null to skip)
    /// @return filtered invocation metrics JSON
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
    ///
    /// @return slow invocations JSON
    public String getSlowInvocations() {
        return get("/api/invocation-metrics/slow");
    }

    /// Fetches current threshold strategy configuration.
    ///
    /// @return strategy configuration JSON
    public String getInvocationStrategy() {
        return get("/api/invocation-metrics/strategy");
    }

    // ===== Threshold & Alert API =====

    /// Fetches all configured thresholds.
    ///
    /// @return thresholds JSON
    public String getThresholds() {
        return get("/api/thresholds");
    }

    /// Sets an alert threshold for a metric.
    ///
    /// @param metric metric name
    /// @param warning warning threshold
    /// @param critical critical threshold
    /// @return response JSON
    public String setThreshold(String metric, double warning, double critical) {
        var body = "{\"metric\":\"" + metric + "\",\"warning\":" + warning + ",\"critical\":" + critical + "}";
        return post("/api/thresholds", body);
    }

    /// Deletes a threshold for a metric.
    ///
    /// @param metric metric name
    /// @return response JSON
    public String deleteThreshold(String metric) {
        return delete("/api/thresholds/" + metric);
    }

    /// Fetches all alerts (active and history).
    ///
    /// @return alerts JSON
    public String getAlerts() {
        return get("/api/alerts");
    }

    /// Fetches active alerts only.
    ///
    /// @return active alerts JSON
    public String getActiveAlerts() {
        return get("/api/alerts/active");
    }

    /// Fetches alert history.
    ///
    /// @return alert history JSON
    public String getAlertHistory() {
        return get("/api/alerts/history");
    }

    /// Clears all alerts.
    ///
    /// @return response JSON
    public String clearAlerts() {
        return post("/api/alerts/clear", "{}");
    }

    // ===== TTM API =====

    /// Fetches TTM (Tiny Time Mixers) status.
    ///
    /// @return TTM status JSON
    public String getTtmStatus() {
        return get("/api/ttm/status");
    }

    // ===== Controller API =====

    /// Fetches controller configuration.
    ///
    /// @return controller config JSON
    public String getControllerConfig() {
        return get("/api/controller/config");
    }

    /// Fetches controller status (enabled/disabled).
    ///
    /// @return controller status JSON
    public String getControllerStatus() {
        return get("/api/controller/status");
    }

    /// Updates controller configuration.
    ///
    /// @param config configuration JSON
    /// @return response JSON
    public String setControllerConfig(String config) {
        return post("/api/controller/config", config);
    }

    /// Triggers immediate controller evaluation.
    ///
    /// @return evaluation result JSON
    public String triggerControllerEvaluation() {
        return post("/api/controller/evaluate", "{}");
    }

    // ===== Rolling Update API =====

    /// Starts a rolling update.
    ///
    /// @param oldVersion old artifact version
    /// @param newVersion new artifact version
    /// @return response JSON with update ID
    public String startRollingUpdate(String oldVersion, String newVersion) {
        var body = "{\"oldVersion\":\"" + oldVersion + "\",\"newVersion\":\"" + newVersion + "\"}";
        return post("/api/rolling-update/start", body);
    }

    /// Gets all active rolling updates.
    ///
    /// @return active updates JSON
    public String getRollingUpdates() {
        return get("/api/rolling-updates");
    }

    /// Gets status of a specific rolling update.
    ///
    /// @param updateId update identifier
    /// @return update status JSON
    public String getRollingUpdateStatus(String updateId) {
        return get("/api/rolling-update/" + updateId);
    }

    /// Adjusts traffic routing during rolling update.
    ///
    /// @param updateId update identifier
    /// @param oldWeight weight for old version
    /// @param newWeight weight for new version
    /// @return response JSON
    public String setRollingUpdateRouting(String updateId, int oldWeight, int newWeight) {
        var body = "{\"oldWeight\":" + oldWeight + ",\"newWeight\":" + newWeight + "}";
        return post("/api/rolling-update/" + updateId + "/routing", body);
    }

    /// Completes a rolling update.
    ///
    /// @param updateId update identifier
    /// @return response JSON
    public String completeRollingUpdate(String updateId) {
        return post("/api/rolling-update/" + updateId + "/complete", "{}");
    }

    /// Rolls back a rolling update.
    ///
    /// @param updateId update identifier
    /// @return response JSON
    public String rollbackRollingUpdate(String updateId) {
        return post("/api/rolling-update/" + updateId + "/rollback", "{}");
    }

    // ===== Slice Status API =====

    /// Fetches detailed slice status with health per instance.
    ///
    /// @return slice status JSON
    public String getSlicesStatus() {
        return get("/api/slices/status");
    }

    /// Checks if a slice is in FAILED state.
    ///
    /// @param artifact artifact to check (partial match on name)
    /// @return true if the slice is in FAILED state
    public boolean isSliceFailed(String artifact) {
        return "FAILED".equals(getSliceState(artifact));
    }

    /// Checks if a slice is in ACTIVE state.
    ///
    /// @param artifact artifact to check (partial match on name)
    /// @return true if the slice is in ACTIVE state
    public boolean isSliceActive(String artifact) {
        return "ACTIVE".equals(getSliceState(artifact));
    }

    /// Gets the current state of a slice.
    ///
    /// @param artifact artifact to check (partial match on name)
    /// @return state string or "UNKNOWN" if not found
    public String getSliceState(String artifact) {
        var status = getSlicesStatus();
        if (!status.contains(artifact)) {
            return "NOT_FOUND";
        }
        // Extract state for the artifact
        // Simple parsing - look for pattern after the artifact
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
    ///
    /// @param httpMethod HTTP method (GET, POST, etc.)
    /// @param path       Route path (e.g., "/api/orders")
    /// @param body       Request body (for POST/PUT)
    /// @return response body or error JSON
    public String invokeSlice(String httpMethod, String path, String body) {
        return switch (httpMethod.toUpperCase()) {
            case "GET" -> get(path);
            case "POST" -> post(path, body);
            case "DELETE" -> delete(path);
            default -> "{\"error\":\"Unsupported HTTP method: " + httpMethod + "\"}";
        };
    }

    /// Invokes a slice method with GET.
    ///
    /// @param path Route path
    /// @return response body
    public String invokeGet(String path) {
        return get(path);
    }

    /// Invokes a slice method with POST.
    ///
    /// @param path Route path
    /// @param body Request body JSON
    /// @return response body
    public String invokePost(String path, String body) {
        return post(path, body);
    }

    // ===== Routes API =====

    /// Fetches all registered routes.
    ///
    /// @return routes JSON
    public String getRoutes() {
        return get("/api/routes");
    }
}
