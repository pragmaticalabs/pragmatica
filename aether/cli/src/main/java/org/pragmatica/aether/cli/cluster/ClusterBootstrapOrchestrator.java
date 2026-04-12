package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.LoadBalancerMode;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.aether.environment.NodeAddress;
import org.pragmatica.aether.environment.ProvisionedNode;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

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
@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"})
public sealed interface ClusterBootstrapOrchestrator {
    record unused() implements ClusterBootstrapOrchestrator {}

    int API_KEY_BYTES = 32;

    /// Bootstrap a cluster from validated config.
    static Result<BootstrapResult> bootstrap(ClusterBootstrapConfig config) {
        return bootstrap(config, false);
    }

    /// Bootstrap with resume support.
    static Result<BootstrapResult> bootstrap(ClusterBootstrapConfig config, boolean resume) {
        return phaseValidate(config)
            .flatMap(ClusterBootstrapOrchestrator::phaseProvision)
            .flatMap(ClusterBootstrapOrchestrator::phaseCollectAddresses)
            .flatMap(ClusterBootstrapOrchestrator::phaseDeployRuntime)
            .flatMap(ClusterBootstrapOrchestrator::phaseClusterFormation)
            .flatMap(ClusterBootstrapOrchestrator::phasePostBootstrap);
    }

    // --- Phase 1: Validate ---

    private static Result<BootstrapContext> phaseValidate(ClusterBootstrapConfig config) {
        logPhase(VALIDATE, "Validating bootstrap configuration");
        var clusterName = config.cluster().name();
        var configHash = Integer.toHexString(config.hashCode());
        var state = BootstrapState.initialState(clusterName, configHash, Instant.now().toString());
        return success(BootstrapContext.bootstrapContext(config, state, List.of(), List.of()));
    }

    // --- Phase 2: Provision Infrastructure ---

    private static Result<BootstrapContext> phaseProvision(BootstrapContext ctx) {
        logPhase(PROVISION, "Provisioning infrastructure for %d source(s)", ctx.config().sources().size());
        var allNodes = new ArrayList<ProvisionedNode>();

        for (var entry : ctx.config().sources().entrySet()) {
            var result = provisionSource(entry.getKey(), entry.getValue());

            if (result.isFailure()) {
                return result.map(_ -> ctx);
            }

            result.onSuccess(allNodes::addAll);
        }

        return success(ctx.withNodes(List.copyOf(allNodes)));
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static Result<List<ProvisionedNode>> provisionSource(String sourceName, SourceProfile source) {
        var nodes = new ArrayList<ProvisionedNode>();
        var roleOrder = List.of(NodeRole.CORE, NodeRole.WORKER, NodeRole.SPOT);

        for (var role : roleOrder) {
            var roleTable = option(source.roles().get(role));
            roleTable.onPresent(rt -> logProvisionRole(sourceName, source.type(), role, rt.count()));
        }

        // TODO: Wire actual provisioning per source type:
        //   CLOUD  -> CloudProvider.provision(NodeGroupConfig) for each role
        //   SSH    -> no-op (hosts already exist, return ProvisionedNode from hosts list)
        //   FORGE  -> no-op (Ember nodes created in Phase 4)
        //   DOCKER -> DockerCloudProvider.provision() to create containers
        return success(List.copyOf(nodes));
    }

    @Contract
    private static void logProvisionRole(String sourceName,
                                         SourceType type,
                                         NodeRole role,
                                         Option<Integer> count) {
        count.onPresent(c -> System.out.printf("  [%s/%s] %s: provisioning %d node(s)%n",
                                               sourceName, type.value(), role.value(), c));
    }

    // --- Phase 3: Collect Addresses ---

    private static Result<BootstrapContext> phaseCollectAddresses(BootstrapContext ctx) {
        logPhase(COLLECT_ADDRESSES, "Collecting addresses from %d source(s)", ctx.config().sources().size());
        var allAddresses = new ArrayList<NodeAddress>();

        for (var entry : ctx.config().sources().entrySet()) {
            var result = collectAddressesForSource(entry.getKey(), entry.getValue());

            if (result.isFailure()) {
                return result.map(_ -> ctx);
            }

            result.onSuccess(allAddresses::addAll);
        }

        return success(ctx.withAddresses(List.copyOf(allAddresses)));
    }

    private static Result<List<NodeAddress>> collectAddressesForSource(String sourceName, SourceProfile source) {
        return switch (source.type()) {
            case SSH -> collectSshAddresses(sourceName, source);
            case FORGE -> collectForgeAddresses(sourceName, source);
            // TODO: CLOUD -> cloudProvider.addresses(nodeIds)
            // TODO: DOCKER -> query Docker daemon for container IPs
            case CLOUD, DOCKER -> logAndReturnEmpty(sourceName, source.type());
        };
    }

    private static Result<List<NodeAddress>> collectSshAddresses(String sourceName, SourceProfile source) {
        var addresses = new ArrayList<NodeAddress>();

        for (var roleEntry : source.roles().entrySet()) {
            roleEntry.getValue().hosts().onPresent(hosts -> addHostAddresses(addresses, sourceName, hosts));
        }

        return success(List.copyOf(addresses));
    }

    @Contract
    private static void addHostAddresses(List<NodeAddress> addresses, String sourceName, List<String> hosts) {
        for (int i = 0; i < hosts.size(); i++) {
            addresses.add(NodeAddress.nodeAddress(sourceName + "-" + (i + 1), hosts.get(i), none()));
        }
    }

    private static Result<List<NodeAddress>> collectForgeAddresses(String sourceName, SourceProfile source) {
        var addresses = new ArrayList<NodeAddress>();
        var portBase = 7000;
        var coreRole = option(source.roles().get(NodeRole.CORE));
        var count = coreRole.flatMap(rt -> rt.count()).or(3);

        for (int i = 0; i < count; i++) {
            var nodeId = sourceName + "-core-" + (i + 1);
            addresses.add(NodeAddress.nodeAddress(nodeId, "127.0.0.1:" + (portBase + i), none()));
        }

        return success(List.copyOf(addresses));
    }

    private static Result<List<NodeAddress>> logAndReturnEmpty(String sourceName, SourceType type) {
        System.out.printf("  [%s] %s address collection deferred to provider integration%n", sourceName, type.value());
        return success(List.of());
    }

    // --- Phase 4: Deploy Runtime ---

    private static Result<BootstrapContext> phaseDeployRuntime(BootstrapContext ctx) {
        logPhase(DEPLOY_RUNTIME, "Deploying runtime to %d node(s)", ctx.addresses().size());
        var peerList = buildPeerList(ctx.addresses());
        System.out.printf("  Peer list: %s%n", peerList);
        // TODO: For each source type:
        //   CONTAINER -> SSH docker run with peer list
        //   DOCKER    -> already running from Phase 2
        //   FORGE     -> start EmberCluster in-process
        //   JVM       -> transfer JAR via SCP, start via SSH
        return success(ctx);
    }

    private static String buildPeerList(List<NodeAddress> addresses) {
        return String.join(",", addresses.stream().map(NodeAddress::publicIp).toList());
    }

    // --- Phase 5: Cluster Formation ---

    private static Result<BootstrapContext> phaseClusterFormation(BootstrapContext ctx) {
        logPhase(CLUSTER_FORMATION, "Establishing cluster quorum");
        var apiKey = generateApiKey();
        System.out.printf("  API key generated (%d bytes, Base64 URL-encoded)%n", API_KEY_BYTES);
        // TODO: Poll health endpoints until quorum
        // TODO: Store TEMPLATE + CURRENT config in KV-Store
        // TODO: Store API key in cluster
        return success(ctx.withApiKey(apiKey));
    }

    static String generateApiKey() {
        var bytes = new byte[API_KEY_BYTES];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // --- Phase 6: Post-Bootstrap ---

    private static Result<BootstrapResult> phasePostBootstrap(BootstrapContext ctx) {
        logPhase(POST_BOOTSTRAP, "Finalizing cluster setup");
        activateElectedLoadBalancers(ctx);
        registerClusterLocally(ctx);
        printConnectionInfo(ctx);
        return success(buildResult(ctx));
    }

    @Contract
    private static void activateElectedLoadBalancers(BootstrapContext ctx) {
        for (var source : ctx.config().sources().values()) {
            if (source.loadBalancer() == LoadBalancerMode.ELECTED) {
                System.out.printf("  Activating elected load balancer for source '%s'%n", source.name());
                // TODO: Call management API to activate LB on elected leader
            }
        }
    }

    @Contract
    private static void registerClusterLocally(BootstrapContext ctx) {
        var clusterName = ctx.config().cluster().name();
        var apiKeyEnvName = deriveApiKeyEnvName(clusterName);
        var endpoint = ctx.addresses().isEmpty()
                       ? "http://localhost:9090"
                       : "http://" + ctx.addresses().getFirst().publicIp();

        ClusterRegistry.load()
                       .map(registry -> registry.add(clusterName, endpoint, Option.some(apiKeyEnvName)))
                       .flatMap(ClusterRegistry::save)
                       .onFailure(cause -> System.err.println("Warning: failed to register cluster locally: " + cause.message()));
    }

    @Contract
    private static void printConnectionInfo(BootstrapContext ctx) {
        var clusterName = ctx.config().cluster().name();
        System.out.println();
        System.out.printf("Cluster \"%s\" bootstrapped successfully.%n", clusterName);
        System.out.printf("Nodes: %d address(es) collected%n", ctx.addresses().size());
        ctx.apiKey().onPresent(key -> System.out.printf("API Key Env: %s%n", deriveApiKeyEnvName(clusterName)));
    }

    private static BootstrapResult buildResult(BootstrapContext ctx) {
        var clusterName = ctx.config().cluster().name();
        var endpoint = ctx.addresses().isEmpty()
                       ? "http://localhost:9090"
                       : "http://" + ctx.addresses().getFirst().publicIp();
        var apiKey = ctx.apiKey().or("");
        var apiKeyEnvName = deriveApiKeyEnvName(clusterName);

        return BootstrapResult.bootstrapResult(clusterName, endpoint, apiKey, ctx.nodes(), apiKeyEnvName);
    }

    private static String deriveApiKeyEnvName(String clusterName) {
        return "AETHER_" + clusterName.toUpperCase().replace('-', '_') + "_API_KEY";
    }

    @Contract
    private static void logPhase(BootstrapPhase phase, String message) {
        System.out.printf("[Phase %d/%d: %s] %s%n", phase.ordinal() + 1, BootstrapPhase.values().length, phase.name(), message);
    }

    @Contract
    private static void logPhase(BootstrapPhase phase, String format, Object arg) {
        logPhase(phase, String.format(format, arg));
    }

    // --- Internal types ---

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
            @Override
            public String message() {
                return "Provisioning failed for source '" + sourceName + "': " + detail;
            }
        }

        record AddressCollectionFailed(String sourceName, String detail) implements BootstrapError {
            @Override
            public String message() {
                return "Address collection failed for source '" + sourceName + "': " + detail;
            }
        }

        record DeploymentFailed(String nodeId, String detail) implements BootstrapError {
            @Override
            public String message() {
                return "Runtime deployment failed for node '" + nodeId + "': " + detail;
            }
        }

        record QuorumNotEstablished(int healthy, int required) implements BootstrapError {
            @Override
            public String message() {
                return "Quorum not established: " + healthy + "/" + required + " nodes healthy";
            }
        }
    }
}
