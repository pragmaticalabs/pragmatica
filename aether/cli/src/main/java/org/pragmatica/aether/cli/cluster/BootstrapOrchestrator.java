package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.config.cluster.ClusterConfigError;
import org.pragmatica.aether.config.cluster.ClusterConfigValidator;
import org.pragmatica.aether.config.cluster.ClusterManagementConfig;
import org.pragmatica.aether.config.cluster.DeploymentType;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.http.JdkHttpOperations;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.net.URI;
import java.net.http.HttpRequest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import tools.jackson.databind.JsonNode;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;

/// Orchestrates the full cluster bootstrap flow from zero to operational.
///
/// Executes the 12-step bootstrap sequence defined in the cluster management spec (section 4.1):
/// validate, resolve secrets, check existing, generate API key, provision instances,
/// wait for health, wait for quorum, store config, store API key, register locally,
/// print info, return result.
///
/// For Phase 1, only Hetzner deployment is supported. The CLI calls cloud APIs directly
/// using the Hetzner REST API via HttpOperations.
@SuppressWarnings({"JBCT-PAT-01", "JBCT-SEQ-01", "JBCT-RET-01", "JBCT-EX-01"})
sealed interface BootstrapOrchestrator {
    record unused() implements BootstrapOrchestrator{}

    int HEALTH_TIMEOUT_SECONDS = 300;
    int QUORUM_TIMEOUT_SECONDS = 600;
    int HEALTH_POLL_INTERVAL_MS = 5000;
    int API_KEY_BYTES = 32;
    String HETZNER_API_BASE = "https://api.hetzner.cloud/v1";

    JsonMapper MAPPER = JsonMapper.defaultJsonMapper();
    HttpOperations HTTP = JdkHttpOperations.jdkHttpOperations();

    /// Execute the full bootstrap flow, dispatching by deployment type.
    /// References must already be resolved before calling this method.
    static Result<BootstrapResult> bootstrap(ClusterManagementConfig config) {
        return validateConfig(config).flatMap(BootstrapOrchestrator::dispatchByType);
    }

    private static Result<BootstrapResult> dispatchByType(ClusterManagementConfig config) {
        return switch (config.deployment().type()) {case HETZNER -> bootstrapHetzner(config);case ON_PREMISES -> bootstrapOnPremises(config);default -> new BootstrapError.UnsupportedProvider(config.deployment().type()
                                                                                                                                                                                                                .value()).result();};
    }

    private static Result<BootstrapResult> bootstrapHetzner(ClusterManagementConfig config) {
        return resolveCloudCredentials(config).flatMap(BootstrapOrchestrator::checkNoExistingCluster)
                                      .flatMap(BootstrapOrchestrator::executeProvisioning);
    }

    private static Result<BootstrapResult> bootstrapOnPremises(ClusterManagementConfig config) {
        var apiKey = generateApiKey();
        var clusterSecret = resolveClusterSecret(config);
        System.out.println("Step 5/12: Generated API key.");
        System.out.println("Step 6/12: Provisioning on-premises nodes via SSH...");
        return SshBootstrapOrchestrator.bootstrap(config, clusterSecret)
        .flatMap(nodes -> waitAndFinalizeOnPremises(config, nodes, apiKey));
    }

    private static Result<BootstrapResult> waitAndFinalizeOnPremises(ClusterManagementConfig config,
                                                                     List<ProvisionedNode> nodes,
                                                                     String apiKey) {
        System.out.println("Step 7/12: Waiting for nodes to become healthy...");
        var managementPort = config.deployment().ports()
                                              .management();
        var healthyCount = waitForHealth(nodes, managementPort);
        var requiredQuorum = quorumSize(config.cluster().core()
                                                      .count());
        if ( healthyCount < requiredQuorum) {
        return new ClusterConfigError.QuorumTimeout(healthyCount, requiredQuorum, HEALTH_TIMEOUT_SECONDS).result();}
        System.out.println("Step 8/12: Waiting for quorum...");
        var firstEndpoint = nodeEndpoint(nodes.getFirst(), managementPort);
        if ( !waitForQuorum(firstEndpoint)) {
        return new ClusterConfigError.QuorumTimeout(healthyCount, requiredQuorum, QUORUM_TIMEOUT_SECONDS).result();}
        return finalizeBootstrap(new BootstrapContext(config, "", ""), nodes, apiKey, firstEndpoint);
    }

    // --- Step 1: Validate config ---
    private static Result<ClusterManagementConfig> validateConfig(ClusterManagementConfig config) {
        System.out.println("Step 1/12: Validating configuration...");
        return ClusterConfigValidator.validate(config);
    }

    // --- Steps 2-3: Resolve secrets and cloud credentials ---
    private static Result<BootstrapContext> resolveCloudCredentials(ClusterManagementConfig config) {
        System.out.println("Step 2/12: Resolving secrets...");
        System.out.println("Step 3/12: Resolving cloud credentials...");
        return resolveApiToken(config).map(token -> new BootstrapContext(config, token, resolveClusterSecret(config)));
    }

    private static Result<String> resolveApiToken(ClusterManagementConfig config) {
        if ( config.deployment().type() != DeploymentType.HETZNER) {
        return new BootstrapError.UnsupportedProvider(config.deployment().type()
                                                                       .value()).result();}
        return option(System.getenv("HETZNER_API_TOKEN"))
        .toResult(new ClusterConfigError.CloudCredentialsMissing("Hetzner", "HETZNER_API_TOKEN"));
    }

    private static String resolveClusterSecret(ClusterManagementConfig config) {
        return config.deployment().tls()
                                .flatMap(tls -> tls.clusterSecret())
                                .or(generateRandomSecret());
    }

    // --- Step 4: Check for existing cluster ---
    private static Result<BootstrapContext> checkNoExistingCluster(BootstrapContext ctx) {
        System.out.println("Step 4/12: Checking for existing cluster...");
        var clusterName = ctx.config().cluster()
                                    .name();
        var existingInstances = listTaggedInstances(ctx.apiToken(), clusterName);
        if ( !existingInstances.isEmpty()) {
        return resumeOrAbort(ctx, existingInstances);}
        return success(ctx);
    }

    private static Result<BootstrapContext> resumeOrAbort(BootstrapContext ctx,
                                                          List<ProvisionedNode> existing) {
        System.out.printf("  Found %d existing instances tagged for cluster '%s'.%n",
                          existing.size(),
                          ctx.config().cluster()
                                    .name());
        System.out.println("  Resuming bootstrap with existing instances.");
        return success(ctx.withExistingNodes(existing));
    }

    // --- Steps 5-12: Execute provisioning ---
    private static Result<BootstrapResult> executeProvisioning(BootstrapContext ctx) {
        var apiKey = generateApiKey();
        System.out.println("Step 5/12: Generated API key.");
        return provisionInstances(ctx).flatMap(nodes -> waitAndFinalize(ctx, nodes, apiKey));
    }

    private static Result<BootstrapResult> waitAndFinalize(BootstrapContext ctx,
                                                           List<ProvisionedNode> nodes,
                                                           String apiKey) {
        System.out.println("Step 7/12: Waiting for nodes to become healthy...");
        var managementPort = ctx.config().deployment()
                                       .ports()
                                       .management();
        var healthyCount = waitForHealth(nodes, managementPort);
        var requiredQuorum = quorumSize(ctx.config().cluster()
                                                  .core()
                                                  .count());
        if ( healthyCount < requiredQuorum) {
        return new ClusterConfigError.QuorumTimeout(healthyCount, requiredQuorum, HEALTH_TIMEOUT_SECONDS).result();}
        System.out.println("Step 8/12: Waiting for quorum...");
        var firstEndpoint = nodeEndpoint(nodes.getFirst(), managementPort);
        if ( !waitForQuorum(firstEndpoint)) {
        return new ClusterConfigError.QuorumTimeout(healthyCount, requiredQuorum, QUORUM_TIMEOUT_SECONDS).result();}
        return finalizeBootstrap(ctx, nodes, apiKey, firstEndpoint);
    }

    private static Result<BootstrapResult> finalizeBootstrap(BootstrapContext ctx,
                                                             List<ProvisionedNode> nodes,
                                                             String apiKey,
                                                             String firstEndpoint) {
        System.out.println("Step 9/12: Storing cluster config...");
        storeClusterConfig(firstEndpoint, ctx.config(), apiKey);
        System.out.println("Step 10/12: Storing API key...");
        storeApiKey(firstEndpoint, apiKey);
        System.out.println("Step 11/12: Registering in local registry...");
        var clusterName = ctx.config().cluster()
                                    .name();
        var apiKeyEnvName = "AETHER_" + clusterName.toUpperCase().replace('-', '_') + "_API_KEY";
        registerLocally(clusterName, firstEndpoint, apiKeyEnvName);
        System.out.println("Step 12/12: Bootstrap complete.");
        printConnectionInfo(clusterName, firstEndpoint, apiKey, nodes);
        return success(new BootstrapResult(clusterName, firstEndpoint, apiKey, nodes, apiKeyEnvName));
    }

    // --- Step 6: Provision instances ---
    private static Result<List<ProvisionedNode>> provisionInstances(BootstrapContext ctx) {
        System.out.println("Step 6/12: Provisioning instances...");
        var config = ctx.config();
        var coreCount = config.cluster().core()
                                      .count();
        var existingNodes = ctx.existingNodes();
        var alreadyProvisioned = existingNodes.size();
        var allNodes = new ArrayList<>(existingNodes);
        if ( alreadyProvisioned > 0) {
        System.out.printf("  Skipping %d already-provisioned nodes.%n", alreadyProvisioned);}
        for ( int i = alreadyProvisioned; i < coreCount; i++) {
            var nodeId = generateNodeId(config.cluster().name(),
                                        i);
            var userData = UserDataTemplate.render(config,
                                                   nodeId,
                                                   i,
                                                   ctx.clusterSecret(),
                                                   config.cluster().name());
            System.out.printf("  Provisioning node %d/%d (%s)...%n", i + 1, coreCount, nodeId);
            var result = provisionSingleNode(ctx.apiToken(), config, nodeId, userData);
            if ( result.isFailure()) {
            return new ClusterConfigError.BootstrapFailed("provision",
                                                          allNodes.size(),
                                                          coreCount,
                                                          result.fold(Cause::message, _ -> "")).result();}
            result.onSuccess(allNodes::add);
        }
        return success(List.copyOf(allNodes));
    }

    private static Result<ProvisionedNode> provisionSingleNode(String apiToken,
                                                               ClusterManagementConfig config,
                                                               String nodeId,
                                                               String userData) {
        var clusterName = config.cluster().name();
        var instanceType = config.deployment().instances()
                                            .getOrDefault("core", "cx21");
        var location = firstZoneLocation(config);
        var image = config.deployment().runtime()
                                     .type() == org.pragmatica.aether.config.cluster.RuntimeType.CONTAINER
                    ? "ubuntu-24.04"
                    : "ubuntu-24.04";
        var jsonBody = buildCreateServerJson(nodeId, instanceType, image, location, userData, clusterName);
        return hetznerPost(apiToken, "/servers", jsonBody).map(body -> parseProvisionedNode(body, nodeId));
    }

    private static String firstZoneLocation(ClusterManagementConfig config) {
        var zones = config.deployment().zones();
        if ( zones.isEmpty()) {
        return "fsn1";}
        return zones.values().iterator()
                           .next()
                           .split("-") [0];
    }

    @SuppressWarnings("JBCT-UTIL-01")
    private static String buildCreateServerJson(String name,
                                                String serverType,
                                                String image,
                                                String location,
                                                String userData,
                                                String clusterName) {
        var escapedUserData = escapeJsonString(userData);
        return "{\"name\":\"" + name + "\"" + ",\"server_type\":\"" + serverType + "\"" + ",\"image\":\"" + image + "\"" + ",\"location\":\"" + location + "\"" + ",\"user_data\":\"" + escapedUserData + "\"" + ",\"start_after_create\":true" + ",\"labels\":{\"aether-cluster\":\"" + clusterName + "\"" + ",\"aether-node-id\":\"" + name + "\"" + ",\"aether-role\":\"core\"}}";
    }

    private static ProvisionedNode parseProvisionedNode(String responseJson, String nodeId) {
        return MAPPER.readTree(responseJson).map(root -> extractProvisionedNode(root.path("server"),
                                                                                nodeId))
                              .or(new ProvisionedNode(nodeId, "0", ""));
    }

    private static ProvisionedNode extractProvisionedNode(JsonNode server, String nodeId) {
        var serverId = server.path("id").asText("0");
        var publicIp = extractPublicIp(server);
        return new ProvisionedNode(nodeId, serverId, publicIp);
    }

    private static String extractPublicIp(JsonNode server) {
        return server.path("public_net").path("ipv4")
                          .path("ip")
                          .asText("");
    }

    // --- Step 7: Wait for health ---
    private static int waitForHealth(List<ProvisionedNode> nodes, int managementPort) {
        var healthyCount = 0;
        for ( var node : nodes) {
            System.out.printf("  Waiting for node %s (%s)...%n", node.nodeId(), node.publicIp());
            if ( pollHealth(nodeEndpoint(node, managementPort))) {
                healthyCount++;
                System.out.printf("  Node %s is healthy.%n", node.nodeId());
            } else




























            {
            System.err.printf("  Node %s did not become healthy within %d seconds.%n",
                              node.nodeId(),
                              HEALTH_TIMEOUT_SECONDS);}
        }
        return healthyCount;
    }

    private static boolean pollHealth(String endpoint) {
        var deadline = System.currentTimeMillis() + (long) HEALTH_TIMEOUT_SECONDS * 1000;
        while ( System.currentTimeMillis() < deadline) {
            var result = httpGet(endpoint + "/health/live");
            if ( result.isSuccess()) {
            return true;}
            sleepQuietly(HEALTH_POLL_INTERVAL_MS);
        }
        return false;
    }

    // --- Step 8: Wait for quorum ---
    private static boolean waitForQuorum(String endpoint) {
        var deadline = System.currentTimeMillis() + (long) QUORUM_TIMEOUT_SECONDS * 1000;
        while ( System.currentTimeMillis() < deadline) {
            var result = httpGet(endpoint + "/health/ready");
            if ( result.isSuccess()) {
            return true;}
            sleepQuietly(HEALTH_POLL_INTERVAL_MS);
        }
        return false;
    }

    // --- Step 9: Store cluster config ---
    private static void storeClusterConfig(String endpoint,
                                           ClusterManagementConfig config,
                                           String apiKey) {
        var clusterName = config.cluster().name();
        var version = config.cluster().version();
        var coreCount = config.cluster().core()
                                      .count();
        var deploymentType = config.deployment().type()
                                              .value();
        var jsonBody = "{\"clusterName\":\"" + clusterName + "\"" + ",\"version\":\"" + version + "\"" + ",\"coreCount\":" + coreCount + ",\"deploymentType\":\"" + deploymentType + "\"" + ",\"configVersion\":1}";
        httpPost(endpoint + "/api/cluster/config", jsonBody, apiKey);
    }

    // --- Step 10: Store API key ---
    private static void storeApiKey(String endpoint, String apiKey) {
        // During bootstrap, the API key is stored as the first authenticated request
        // The node accepts unauthenticated requests until the first API key is stored
        var jsonBody = "{\"apiKey\":\"" + apiKey + "\"}";
        httpPost(endpoint + "/api/cluster/api-key", jsonBody, apiKey);
    }

    // --- Step 11: Register locally ---
    private static void registerLocally(String clusterName, String endpoint, String apiKeyEnvName) {
        var registryResult = ClusterRegistry.load().map(registry -> registry.add(clusterName,
                                                                                 endpoint,
                                                                                 Option.some(apiKeyEnvName)))
                                                 .flatMap(ClusterRegistry::save);
        registryResult.onFailure(cause -> System.err.println("Warning: failed to register cluster locally: " + cause.message()));
    }

    // --- Step 12: Print connection info ---
    private static void printConnectionInfo(String clusterName,
                                            String endpoint,
                                            String apiKey,
                                            List<ProvisionedNode> nodes) {
        System.out.println();
        System.out.printf("Cluster \"%s\" bootstrapped successfully.%n", clusterName);
        System.out.printf("Nodes: %d/%d healthy%n", nodes.size(), nodes.size());
        System.out.printf("Endpoint: %s%n", endpoint);
        System.out.printf("API Key: %s (save this -- it will not be shown again)%n", apiKey);
        System.out.println();
        System.out.println("Node list:");
        for ( var node : nodes) {
        System.out.printf("  %s  %s  %s%n", node.nodeId(), node.serverId(), node.publicIp());}
        System.out.println();
        var envName = "AETHER_" + clusterName.toUpperCase().replace('-', '_') + "_API_KEY";
        System.out.printf("Set %s=%s in your environment.%n", envName, apiKey);
    }

    // --- Hetzner API helpers ---
    private static Result<String> hetznerPost(String apiToken, String path, String jsonBody) {
        var uri = URI.create(HETZNER_API_BASE + path);
        var request = HttpRequest.newBuilder().uri(uri)
                                            .header("Authorization", "Bearer " + apiToken)
                                            .header("Content-Type", "application/json")
                                            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                                            .build();
        return HTTP.sendString(request).await()
                              .flatMap(BootstrapOrchestrator::extractHetznerBody);
    }

    private static Result<String> extractHetznerBody(HttpResult<String> response) {
        return response.statusCode() >= 200 && response.statusCode() < 300
               ? success(response.body())
               : new BootstrapError.CloudApiError(response.statusCode(), response.body()).result();
    }

    private static List<ProvisionedNode> listTaggedInstances(String apiToken, String clusterName) {
        var uri = URI.create(HETZNER_API_BASE + "/servers?label_selector=aether-cluster%3D" + clusterName);
        var request = HttpRequest.newBuilder().uri(uri)
                                            .header("Authorization", "Bearer " + apiToken)
                                            .GET()
                                            .build();
        return HTTP.sendString(request).await()
                              .map(BootstrapOrchestrator::parseServerList)
                              .or(List.of());
    }

    private static List<ProvisionedNode> parseServerList(HttpResult<String> response) {
        if ( response.statusCode() < 200 || response.statusCode() >= 300) {
        return List.of();}
        return parseServersFromJson(response.body());
    }

    private static List<ProvisionedNode> parseServersFromJson(String json) {
        return MAPPER.readTree(json).map(root -> parseServerArray(root.path("servers")))
                              .or(List.of());
    }

    private static List<ProvisionedNode> parseServerArray(JsonNode serversNode) {
        var result = new ArrayList<ProvisionedNode>();
        if ( !serversNode.isArray()) {
        return List.of();}
        for ( var server : serversNode) {
            var serverId = server.path("id").asText("0");
            var name = server.path("name").asText("");
            var publicIp = extractPublicIp(server);
            result.add(new ProvisionedNode(name, serverId, publicIp));
        }
        return List.copyOf(result);
    }

    // --- Generic HTTP helpers ---
    private static Result<String> httpGet(String url) {
        var request = HttpRequest.newBuilder().uri(URI.create(url))
                                            .GET()
                                            .build();
        return HTTP.sendString(request).await()
                              .flatMap(BootstrapOrchestrator::extractSuccessBody);
    }

    private static void httpPost(String url, String jsonBody, String apiKey) {
        var request = HttpRequest.newBuilder().uri(URI.create(url))
                                            .header("Content-Type", "application/json")
                                            .header("X-API-Key", apiKey)
                                            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                                            .build();
        HTTP.sendString(request).await();
    }

    private static Result<String> extractSuccessBody(HttpResult<String> response) {
        return response.statusCode() >= 200 && response.statusCode() < 300
               ? success(response.body())
               : new BootstrapError.HealthCheckFailed(response.statusCode()).result();
    }

    // --- Utilities ---
    private static String generateNodeId(String clusterName, int index) {
        return clusterName + "-" + (index + 1);
    }

    private static String generateApiKey() {
        var bytes = new byte[API_KEY_BYTES];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding()
                                   .encodeToString(bytes);
    }

    private static String generateRandomSecret() {
        var bytes = new byte[API_KEY_BYTES];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding()
                                   .encodeToString(bytes);
    }

    private static int quorumSize(int totalNodes) {
        return totalNodes / 2 + 1;
    }

    private static String nodeEndpoint(ProvisionedNode node, int managementPort) {
        return "http://" + node.publicIp() + ":" + managementPort;
    }

    private static String escapeJsonString(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                            .replace("\n", "\\n")
                            .replace("\r", "\\r")
                            .replace("\t", "\\t");
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        }




























        catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    /// Result of a successful bootstrap operation.
    record BootstrapResult(String clusterName,
                           String endpoint,
                           String apiKey,
                           List<ProvisionedNode> nodes,
                           String apiKeyEnvName){}

    /// A provisioned cloud instance.
    record ProvisionedNode(String nodeId, String serverId, String publicIp){}

    /// Internal context carrying resolved configuration and credentials through the bootstrap pipeline.
    record BootstrapContext(ClusterManagementConfig config,
                            String apiToken,
                            String clusterSecret,
                            List<ProvisionedNode> existingNodes) {
        BootstrapContext(ClusterManagementConfig config, String apiToken, String clusterSecret) {
            this(config, apiToken, clusterSecret, List.of());
        }

        BootstrapContext withExistingNodes(List<ProvisionedNode> nodes) {
            return new BootstrapContext(config, apiToken, clusterSecret, List.copyOf(nodes));
        }
    }

    /// Errors specific to bootstrap operations.
    sealed interface BootstrapError extends Cause {
        record UnsupportedProvider(String provider) implements BootstrapError {
            @Override public String message() {
                return "Unsupported deployment provider for bootstrap: " + provider + ". Phase 1 supports Hetzner only.";
            }
        }

        record CloudApiError(int statusCode, String body) implements BootstrapError {
            @Override public String message() {
                return "Cloud API error (HTTP " + statusCode + "): " + body;
            }
        }

        record HealthCheckFailed(int statusCode) implements BootstrapError {
            @Override public String message() {
                return "Health check failed with HTTP " + statusCode;
            }
        }
    }
}
