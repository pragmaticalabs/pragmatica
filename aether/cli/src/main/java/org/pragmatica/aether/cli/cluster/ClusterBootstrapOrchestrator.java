package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.LoadBalancerMode;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.aether.config.cluster.SshConfig;
import org.pragmatica.aether.environment.CloudProviderSupport;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.NodeAddress;
import org.pragmatica.aether.environment.NodeGroupConfig;
import org.pragmatica.aether.environment.ProvisionedNode;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.pragmatica.aether.cli.cluster.BootstrapPhase.CLUSTER_FORMATION;
import static org.pragmatica.aether.cli.cluster.BootstrapPhase.COLLECT_ADDRESSES;
import static org.pragmatica.aether.cli.cluster.BootstrapPhase.DEPLOY_RUNTIME;
import static org.pragmatica.aether.cli.cluster.BootstrapPhase.POST_BOOTSTRAP;
import static org.pragmatica.aether.cli.cluster.BootstrapPhase.PROVISION;
import static org.pragmatica.aether.cli.cluster.BootstrapPhase.VALIDATE;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


/// Six-phase cluster bootstrap orchestrator. Section 8.
///
/// Phases:
/// 1. Validate -- static config validation (already done before entry, re-confirmed here)
/// 2. Provision -- create infrastructure per source type
/// 3. Collect Addresses -- gather node addresses from all sources
/// 4. Deploy Runtime -- install and start Aether on each node
/// 5. Cluster Formation -- wait for quorum, generate API key, store config
/// 6. Post-Bootstrap -- activate LBs, register locally, print info
///
/// This is the orchestration skeleton. Actual I/O calls (cloud APIs, SSH, Docker)
/// are deferred -- method bodies log intent and return placeholder results.
@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"}) public sealed interface ClusterBootstrapOrchestrator {
    record unused() implements ClusterBootstrapOrchestrator{}

    int API_KEY_BYTES = 32;

    int POLL_INTERVAL_MS = 5000;

    long DEFAULT_TIMEOUT_MS = 300_000;

    static Result<BootstrapResult> bootstrap(ClusterBootstrapConfig config) {
        return bootstrap(config, false);
    }

    static Result<BootstrapResult> bootstrap(ClusterBootstrapConfig config, boolean resume) {
        return phaseValidate(config).flatMap(ClusterBootstrapOrchestrator::phaseProvision)
                            .flatMap(ClusterBootstrapOrchestrator::phaseCollectAddresses)
                            .flatMap(ClusterBootstrapOrchestrator::phaseDeployRuntime)
                            .flatMap(ClusterBootstrapOrchestrator::phaseClusterFormation)
                            .flatMap(ClusterBootstrapOrchestrator::phasePostBootstrap);
    }

    private static Result<BootstrapContext> phaseValidate(ClusterBootstrapConfig config) {
        logPhase(VALIDATE, "Validating bootstrap configuration");
        var clusterName = config.cluster().name();
        var configHash = Integer.toHexString(config.hashCode());
        var state = BootstrapState.initialState(clusterName,
                                                configHash,
                                                Instant.now().toString());
        return success(BootstrapContext.bootstrapContext(config, state, List.of(), List.of()));
    }

    private static Result<BootstrapContext> phaseProvision(BootstrapContext ctx) {
        logPhase(PROVISION,
                 "Provisioning infrastructure for %d source(s)",
                 ctx.config().sources()
                           .size());
        var allNodes = new ArrayList<ProvisionedNode>();
        var mgmtPort = ctx.config().operations().ports().management();
        for (var entry : ctx.config().sources()
                                   .entrySet()) {
            var result = provisionSource(entry.getKey(), entry.getValue(), mgmtPort);
            if (result.isFailure()) {return result.map(_ -> ctx);}
            var _ = result.onSuccess(allNodes::addAll);
        }
        return success(ctx.withNodes(List.copyOf(allNodes)));
    }

    @SuppressWarnings("JBCT-PAT-01") private static Result<List<ProvisionedNode>> provisionSource(String sourceName,
                                                                                                  SourceProfile source,
                                                                                                  int managementPort) {
        return switch (source.type()){
            case CLOUD -> provisionCloudSource(sourceName, source);
            case DOCKER -> provisionDockerSource(sourceName, source);
            case SSH -> provisionSshSource(sourceName, source);
            case FORGE -> provisionForgeSource(sourceName, source, managementPort);
        };
    }

    @SuppressWarnings("JBCT-PAT-01") private static Result<List<ProvisionedNode>> provisionCloudSource(String sourceName,
                                                                                                       SourceProfile source) {
        return ProviderResolver.resolveCloudCompute(source)
                                                   .flatMap(compute -> provisionWithCompute(compute, sourceName, source));
    }

    @SuppressWarnings("JBCT-PAT-01") private static Result<List<ProvisionedNode>> provisionDockerSource(String sourceName,
                                                                                                        SourceProfile source) {
        return ProviderResolver.resolveDockerCompute()
                                                    .flatMap(compute -> provisionWithCompute(compute, sourceName, source));
    }

    @SuppressWarnings({"JBCT-PAT-01", "JBCT-EX-01"}) private static Result<List<ProvisionedNode>> provisionWithCompute(ComputeProvider compute,
                                                                                                                       String sourceName,
                                                                                                                       SourceProfile source) {
        var allNodes = new ArrayList<ProvisionedNode>();
        var roleOrder = List.of(NodeRole.CORE, NodeRole.WORKER, NodeRole.SPOT);
        for (var role : roleOrder) {
            var roleTable = option(source.roles().get(role));
            var result = roleTable.flatMap(rt -> rt.count())
                                          .map(count -> provisionRoleGroup(compute, sourceName, role, count, source));
            if (result.isPresent()) {
                var provisionResult = result.unwrap();
                if (provisionResult.isFailure()) {return provisionResult;}
                var _ = provisionResult.onSuccess(allNodes::addAll);
            }
        }
        return success(List.copyOf(allNodes));
    }

    @SuppressWarnings("JBCT-EX-01") private static Result<List<ProvisionedNode>> provisionRoleGroup(ComputeProvider compute,
                                                                                                    String sourceName,
                                                                                                    NodeRole role,
                                                                                                    int count,
                                                                                                    SourceProfile source) {
        logProvisionRole(sourceName, source.type(), role, Option.some(count));
        var instanceType = source.roles().containsKey(role)
                          ? source.roles().get(role)
                                        .instanceType()
                                        .or("default")
                          : "default";
        var zone = source.zone().or("default");
        var group = NodeGroupConfig.nodeGroupConfig(sourceName, role.value(), count, instanceType, zone, Map.of());
        return CloudProviderSupport.provisionVia(compute, group).await();
    }

    @SuppressWarnings("JBCT-PAT-01") private static Result<List<ProvisionedNode>> provisionSshSource(String sourceName,
                                                                                                     SourceProfile source) {
        var nodes = new ArrayList<ProvisionedNode>();
        for (var entry : source.roles().entrySet()) {
            var role = entry.getKey();
            entry.getValue().hosts()
                          .onPresent(hosts -> addSshNodes(nodes, sourceName, role, hosts));
        }
        logProvisionRole(sourceName,
                         source.type(),
                         NodeRole.CORE,
                         Option.some(nodes.size()));
        return success(List.copyOf(nodes));
    }

    @Contract private static void addSshNodes(List<ProvisionedNode> nodes,
                                              String sourceName,
                                              NodeRole role,
                                              List<String> hosts) {
        for (int i = 0;i <hosts.size();i++) {
            var nodeId = sourceName + "-" + role.value() + "-" + i;
            nodes.add(ProvisionedNode.provisionedNode(nodeId, "ssh", hosts.get(i)));
        }
    }

    /// §5.1.5 Forge provisions virtual nodes for an in-process EmberCluster.
    /// The actual cluster is started by `aether forge` (ForgeServer) — not the CLI bootstrap.
    /// The bootstrap creates node entries so that address collection, health polling, and
    /// config push work against the locally-running forge instance.
    @SuppressWarnings("JBCT-PAT-01") private static Result<List<ProvisionedNode>> provisionForgeSource(String sourceName,
                                                                                                       SourceProfile source,
                                                                                                       int managementPort) {
        System.out.println("  Forge source: nodes are virtual (in-process via EmberCluster)");
        System.out.println("  Start the forge binary separately: aether forge --config <forge.toml>");
        var nodes = new ArrayList<ProvisionedNode>();
        var counter = 0;
        var roleOrder = List.of(NodeRole.CORE, NodeRole.WORKER, NodeRole.SPOT);
        for (var role : roleOrder) {
            var count = option(source.roles().get(role)).flatMap(rt -> rt.count()).or(0);
            for (int i = 0; i < count; i++) {
                var nodeId = sourceName + "-" + role.value() + "-" + i;
                var nodePort = managementPort + counter;
                nodes.add(ProvisionedNode.provisionedNode(nodeId, "forge", "127.0.0.1"));
                counter++;
            }
            if (count > 0) {logProvisionRole(sourceName, source.type(), role, Option.some(count));}
        }
        return success(List.copyOf(nodes));
    }

    @Contract private static void logProvisionRole(String sourceName,
                                                   SourceType type,
                                                   NodeRole role,
                                                   Option<Integer> count) {
        count.onPresent(c -> System.out.printf("  [%s/%s] %s: provisioning %d node(s)%n",
                                               sourceName,
                                               type.value(),
                                               role.value(),
                                               c));
    }

    private static Result<BootstrapContext> phaseCollectAddresses(BootstrapContext ctx) {
        logPhase(COLLECT_ADDRESSES,
                 "Collecting addresses from %d provisioned node(s)",
                 ctx.nodes().size());
        var addresses = ctx.nodes().stream()
                                 .map(ClusterBootstrapOrchestrator::nodeToAddress)
                                 .toList();
        return success(ctx.withAddresses(addresses));
    }

    @SuppressWarnings("JBCT-PAT-01") private static Result<BootstrapContext> phaseDeployRuntime(BootstrapContext ctx) {
        logPhase(DEPLOY_RUNTIME,
                 "Deploying runtime to %d node(s)",
                 ctx.addresses().size());
        var clusterSecret = generateClusterSecret();
        for (var entry : ctx.config().sources()
                                   .entrySet()) {
            var sourceName = entry.getKey();
            var source = entry.getValue();
            var deployResult = deploySource(ctx, source, sourceName, clusterSecret);
            if (deployResult.isFailure()) {return deployResult.map(_ -> ctx);}
        }
        return success(ctx);
    }

    @SuppressWarnings("JBCT-PAT-01") private static Result<Unit> deploySource(BootstrapContext ctx,
                                                                              SourceProfile source,
                                                                              String sourceName,
                                                                              String clusterSecret) {
        return switch (source.type()){
            case CLOUD -> deployCloudSource(sourceName);
            case SSH -> deploySshSource(ctx, source, sourceName, clusterSecret);
            case FORGE -> deployForgeSource(sourceName);
            case DOCKER -> deployDockerSource(sourceName);
        };
    }

    private static Result<Unit> deployCloudSource(String sourceName) {
        System.out.printf("  [%s/cloud] Cloud-init already applied during provisioning%n", sourceName);
        return Result.unitResult();
    }

    private static Result<Unit> deployDockerSource(String sourceName) {
        System.out.printf("  [%s/docker] Containers already started during provisioning%n", sourceName);
        return Result.unitResult();
    }

    /// §5.1.5 Forge runtime is managed by the ForgeServer binary, not the CLI.
    /// The CLI waits for the forge to become reachable in Phase 5 (health polling).
    private static Result<Unit> deployForgeSource(String sourceName) {
        System.out.printf("  [%s/forge] Ember cluster managed by forge binary — skipping runtime deploy%n", sourceName);
        System.out.println("  Ensure 'aether forge' is running before cluster formation begins");
        return Result.unitResult();
    }

    @SuppressWarnings({"JBCT-PAT-01", "JBCT-EX-01"}) private static Result<Unit> deploySshSource(BootstrapContext ctx,
                                                                                                 SourceProfile source,
                                                                                                 String sourceName,
                                                                                                 String clusterSecret) {
        var sshConfig = buildSshConfig(source);
        var allNodeIps = ctx.addresses().stream()
                                      .map(NodeAddress::publicIp)
                                      .toList();
        var clusterName = ctx.config().cluster()
                                    .name();
        var nodeIndex = 0;
        for (var node : ctx.nodes()) {
            if (!node.serverId().equals("ssh")) {
                nodeIndex++;
                continue;
            }
            var nodeConfig = NodeConfigTemplate.render(ctx.config(), node.nodeId(), nodeIndex, clusterSecret, allNodeIps);
            var result = deploySshNode(node, nodeConfig, sshConfig, clusterName);
            if (result.isFailure()) {return result;}
            nodeIndex++;
        }
        System.out.printf("  [%s/ssh] Deployed runtime to SSH nodes%n", sourceName);
        return Result.unitResult();
    }

    @SuppressWarnings("JBCT-EX-01") private static Result<Unit> deploySshNode(ProvisionedNode node,
                                                                              String nodeConfig,
                                                                              SshConfig sshConfig,
                                                                              String clusterName) {
        return writeNodeConfigToTemp(node.nodeId(),
                                     nodeConfig).flatMap(tempPath -> scpConfigToNode(tempPath,
                                                                                     node.publicIp(),
                                                                                     sshConfig))
                                    .flatMap(_ -> startRuntimeViaSsh(node.publicIp(),
                                                                     sshConfig,
                                                                     clusterName,
                                                                     node.nodeId()));
    }

    private static Result<Path> writeNodeConfigToTemp(String nodeId, String content) {
        return Result.lift(e -> new BootstrapError.DeploymentFailed(nodeId,
                                                                    "Failed to write temp config: " + e.getMessage()),
                           () -> {
                               var tempFile = Files.createTempFile("aether-" + nodeId, ".toml");
                               Files.writeString(tempFile, content);
                               return tempFile;
                           });
    }

    private static Result<Unit> scpConfigToNode(Path localPath, String host, SshConfig sshConfig) {
        return RemoteCommandRunner.scp(localPath.toString(), host, "/opt/aether/config/aether.toml", sshConfig);
    }

    private static Result<Unit> startRuntimeViaSsh(String host,
                                                   SshConfig sshConfig,
                                                   String clusterName,
                                                   String nodeId) {
        var startCommand = "mkdir -p /opt/aether/config && docker pull ghcr.io/pragmaticalabs/aether-node:latest" + " && docker run -d --name aether-node --restart unless-stopped --network host" + " -e AETHER_NODE_ID=" + nodeId + " -l aether-cluster=" + clusterName + " -v /opt/aether/config:/config:ro" + " ghcr.io/pragmaticalabs/aether-node:latest --config /config/aether.toml";
        return RemoteCommandRunner.ssh(host, startCommand, sshConfig).mapToUnit();
    }

    private static SshConfig buildSshConfig(SourceProfile source) {
        var user = source.user().or("root");
        var keyPath = source.key().or("~/.ssh/id_rsa");
        var port = source.sshPort().or(22);
        return SshConfig.sshConfig(user, keyPath, port);
    }

    private static String generateClusterSecret() {
        var bytes = new byte[API_KEY_BYTES];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding()
                                   .encodeToString(bytes);
    }

    private static String buildPeerList(List<NodeAddress> addresses) {
        return String.join(",",
                           addresses.stream().map(NodeAddress::publicIp)
                                           .toList());
    }

    private static NodeAddress nodeToAddress(ProvisionedNode node) {
        return NodeAddress.nodeAddress(node.nodeId(), node.publicIp(), none());
    }

    private static Result<BootstrapContext> phaseClusterFormation(BootstrapContext ctx) {
        logPhase(CLUSTER_FORMATION, "Establishing cluster quorum");
        var apiKey = generateApiKey();
        System.out.printf("  API key generated (%d bytes, Base64 URL-encoded)%n", API_KEY_BYTES);
        var managementPort = ctx.config().operations()
                                       .ports()
                                       .management();
        var healthTimeoutMs = parseDurationMs(ctx.config().operations()
                                                        .timeouts()
                                                        .healthCheck());
        var quorumTimeoutMs = parseDurationMs(ctx.config().operations()
                                                        .timeouts()
                                                        .quorumFormation());
        var requiredCores = ctx.config().derivedCoreCount();
        return waitForHealth(ctx.addresses(),
                             managementPort,
                             healthTimeoutMs).flatMap(_ -> waitForQuorum(ctx.addresses(),
                                                                         managementPort,
                                                                         quorumTimeoutMs,
                                                                         requiredCores))
                            .map(_ -> finalizeClusterFormation(ctx, apiKey));
    }

    private static BootstrapContext finalizeClusterFormation(BootstrapContext ctx, String apiKey) {
        var updatedCtx = ctx.withApiKey(apiKey);
        storeClusterConfig(updatedCtx);
        storeApiKey(updatedCtx, apiKey);
        return updatedCtx;
    }

    @SuppressWarnings("JBCT-EX-01") private static Result<Unit> waitForHealth(List<NodeAddress> addresses,
                                                                              int managementPort,
                                                                              long timeoutMs) {
        if (addresses.isEmpty()) {return Result.unitResult();}
        var endpoint = addresses.getFirst().publicIp();
        var url = "http://" + endpoint + ":" + managementPort + "/health/live";
        System.out.printf("  Waiting for health check at %s (timeout: %ds)%n", url, timeoutMs / 1000);
        var deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() <deadline) {
            var response = httpGet(url);
            if (response.isSuccess()) {
                System.out.println("  Health check passed");
                return Result.unitResult();
            }
            sleepQuietly(POLL_INTERVAL_MS);
        }
        return new BootstrapError.QuorumNotEstablished(0, 1).result();
    }

    @SuppressWarnings("JBCT-EX-01") private static Result<Unit> waitForQuorum(List<NodeAddress> addresses,
                                                                              int managementPort,
                                                                              long timeoutMs,
                                                                              int requiredCores) {
        if (addresses.isEmpty()) {return Result.unitResult();}
        var endpoint = addresses.getFirst().publicIp();
        var url = "http://" + endpoint + ":" + managementPort + "/health/ready";
        System.out.printf("  Waiting for quorum at %s (need %d core(s), timeout: %ds)%n",
                          url,
                          requiredCores,
                          timeoutMs / 1000);
        var deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() <deadline) {
            var response = httpGet(url);
            if (response.isSuccess()) {
                System.out.printf("  Quorum established (%d core(s) required)%n", requiredCores);
                return Result.unitResult();
            }
            sleepQuietly(POLL_INTERVAL_MS);
        }
        return new BootstrapError.QuorumNotEstablished(0, requiredCores).result();
    }

    @Contract private static void storeClusterConfig(BootstrapContext ctx) {
        if (ctx.addresses().isEmpty()) {return;}
        var endpoint = buildManagementEndpoint(ctx);
        var configJson = buildConfigJson(ctx.config());
        var result = httpPost(endpoint + "/api/cluster/config", configJson);
        var _ = result.onSuccess(_ -> System.out.println("  Cluster config stored in KV-Store"))
                                .onFailure(cause -> System.err.println("  Warning: failed to store config: " + cause.message()));
    }

    @Contract private static void storeApiKey(BootstrapContext ctx, String apiKey) {
        if (ctx.addresses().isEmpty()) {return;}
        var endpoint = buildManagementEndpoint(ctx);
        var keyJson = "{\"apiKey\":\"" + apiKey + "\"}";
        var result = httpPost(endpoint + "/api/cluster/api-key", keyJson);
        var _ = result.onSuccess(_ -> System.out.println("  API key stored"))
                                .onFailure(cause -> System.err.println("  Warning: failed to store API key: " + cause.message()));
    }

    private static String buildManagementEndpoint(BootstrapContext ctx) {
        var port = ctx.config().operations()
                             .ports()
                             .management();
        var ip = ctx.addresses().getFirst()
                              .publicIp();
        return "http://" + ip + ":" + port;
    }

    private static String buildConfigJson(ClusterBootstrapConfig config) {
        return "{\"clusterName\":\"" + config.cluster().name() + "\",\"version\":\"" + config.cluster().version() + "\"}";
    }

    private static Result<String> httpPost(String url, String body) {
        return ClusterHttpClient.postDirect(url, body);
    }

    private static Result<String> httpGet(String url) {
        return ClusterHttpClient.getDirect(url);
    }

    @SuppressWarnings("JBCT-EX-01") @Contract private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long parseDurationMs(String duration) {
        if (duration.endsWith("s")) {return parseNumericPrefix(duration) * 1000;}
        if (duration.endsWith("m")) {return parseNumericPrefix(duration) * 60_000;}
        if (duration.endsWith("h")) {return parseNumericPrefix(duration) * 3_600_000;}
        return DEFAULT_TIMEOUT_MS;
    }

    private static long parseNumericPrefix(String duration) {
        return Result.lift(() -> Long.parseLong(duration.substring(0,
                                                                   duration.length() - 1)))
        .or(DEFAULT_TIMEOUT_MS / 1000);
    }

    static String generateApiKey() {
        var bytes = new byte[API_KEY_BYTES];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding()
                                   .encodeToString(bytes);
    }

    private static Result<BootstrapResult> phasePostBootstrap(BootstrapContext ctx) {
        logPhase(POST_BOOTSTRAP, "Finalizing cluster setup");
        activateElectedLoadBalancers(ctx);
        registerClusterLocally(ctx);
        printConnectionInfo(ctx);
        return success(buildResult(ctx));
    }

    @Contract private static void activateElectedLoadBalancers(BootstrapContext ctx) {
        for (var source : ctx.config().sources()
                                    .values()) {if (source.loadBalancer() == LoadBalancerMode.ELECTED) {System.out.printf("  Activating elected load balancer for source '%s'%n",
                                                                                                                          source.name());}}
    }

    @Contract private static void registerClusterLocally(BootstrapContext ctx) {
        var clusterName = ctx.config().cluster()
                                    .name();
        var apiKeyEnvName = deriveApiKeyEnvName(clusterName);
        var endpoint = ctx.addresses().isEmpty()
                      ? "http://localhost:9090"
                      : "http://" + ctx.addresses().getFirst()
                                                 .publicIp();
        ClusterRegistry.load().map(registry -> registry.add(clusterName,
                                                            endpoint,
                                                            Option.some(apiKeyEnvName)))
                            .flatMap(ClusterRegistry::save)
                            .onFailure(cause -> System.err.println("Warning: failed to register cluster locally: " + cause.message()));
    }

    @Contract private static void printConnectionInfo(BootstrapContext ctx) {
        var clusterName = ctx.config().cluster()
                                    .name();
        System.out.println();
        System.out.printf("Cluster \"%s\" bootstrapped successfully.%n", clusterName);
        System.out.printf("Nodes: %d address(es) collected%n",
                          ctx.addresses().size());
        ctx.apiKey().onPresent(key -> System.out.printf("API Key Env: %s%n", deriveApiKeyEnvName(clusterName)));
    }

    private static BootstrapResult buildResult(BootstrapContext ctx) {
        var clusterName = ctx.config().cluster()
                                    .name();
        var endpoint = ctx.addresses().isEmpty()
                      ? "http://localhost:9090"
                      : "http://" + ctx.addresses().getFirst()
                                                 .publicIp();
        var apiKey = ctx.apiKey().or("");
        var apiKeyEnvName = deriveApiKeyEnvName(clusterName);
        return BootstrapResult.bootstrapResult(clusterName, endpoint, apiKey, ctx.nodes(), apiKeyEnvName);
    }

    private static String deriveApiKeyEnvName(String clusterName) {
        return "AETHER_" + clusterName.toUpperCase().replace('-', '_') + "_API_KEY";
    }

    @Contract private static void logPhase(BootstrapPhase phase, String message) {
        System.out.printf("[Phase %d/%d: %s] %s%n",
                          phase.ordinal() + 1,
                          BootstrapPhase.values().length,
                          phase.name(),
                          message);
    }

    @Contract private static void logPhase(BootstrapPhase phase, String format, Object arg) {
        logPhase(phase, String.format(format, arg));
    }

    record BootstrapResult(String clusterName,
                           String endpoint,
                           String apiKey,
                           List<ProvisionedNode> nodes,
                           String apiKeyEnvName) {
        static BootstrapResult bootstrapResult(String clusterName,
                                               String endpoint,
                                               String apiKey,
                                               List<ProvisionedNode> nodes,
                                               String apiKeyEnvName) {
            return new BootstrapResult(clusterName, endpoint, apiKey, List.copyOf(nodes), apiKeyEnvName);
        }
    }

    record BootstrapContext(ClusterBootstrapConfig config,
                            BootstrapState state,
                            List<ProvisionedNode> nodes,
                            List<NodeAddress> addresses,
                            Option<String> apiKey) {
        static BootstrapContext bootstrapContext(ClusterBootstrapConfig config,
                                                 BootstrapState state,
                                                 List<ProvisionedNode> nodes,
                                                 List<NodeAddress> addresses) {
            return new BootstrapContext(config, state, List.copyOf(nodes), List.copyOf(addresses), none());
        }

        BootstrapContext withNodes(List<ProvisionedNode> newNodes) {
            return new BootstrapContext(config, state, List.copyOf(newNodes), addresses, apiKey);
        }

        BootstrapContext withAddresses(List<NodeAddress> newAddresses) {
            return new BootstrapContext(config, state, nodes, List.copyOf(newAddresses), apiKey);
        }

        BootstrapContext withApiKey(String key) {
            return new BootstrapContext(config, state, nodes, addresses, Option.some(key));
        }
    }

    sealed interface BootstrapError extends Cause {
        record ProvisionFailed(String sourceName, String detail) implements BootstrapError {
            @Override public String message() {
                return "Provisioning failed for source '" + sourceName + "': " + detail;
            }
        }

        record AddressCollectionFailed(String sourceName, String detail) implements BootstrapError {
            @Override public String message() {
                return "Address collection failed for source '" + sourceName + "': " + detail;
            }
        }

        record DeploymentFailed(String nodeId, String detail) implements BootstrapError {
            @Override public String message() {
                return "Runtime deployment failed for node '" + nodeId + "': " + detail;
            }
        }

        record QuorumNotEstablished(int healthy, int required) implements BootstrapError {
            @Override public String message() {
                return "Quorum not established: " + healthy + "/" + required + " nodes healthy";
            }
        }
    }
}
