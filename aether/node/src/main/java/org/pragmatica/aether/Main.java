// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether;

import org.pragmatica.aether.config.AetherConfig;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.BackupConfig;
import org.pragmatica.aether.config.ConfigLoader;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.MembershipConfigBinding;
import org.pragmatica.aether.config.StreamingConfig;
import org.pragmatica.aether.config.StorageConfig;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.aether.config.Environment;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.deployment.membership.MembershipConfig;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.EnvironmentIntegrationFactory;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.AetherNodeConfig;
import org.pragmatica.aether.node.labels.ContainerLabelInspector;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.net.NodeRole;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.net.tcp.security.CertificateProvider;
import org.pragmatica.net.tcp.security.SelfSignedCertificateProvider;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;


@SuppressWarnings("JBCT-RET-01")
public record Main(String[] args) {
    private static final Logger log = LoggerFactory.getLogger(Main.class);
    private static final int DEFAULT_CLUSTER_PORT = 8090;

    public static void main(String[] args) {
        new Main(args).run();
    }

    private record TlsBundle(org.pragmatica.net.tcp.TlsConfig tls, CertificateProvider provider) {}

    private void run() {
        var aetherConfig = loadConfig();
        verifyClusterLabelConsistency(aetherConfig);
        var nodeId = parseNodeId(aetherConfig);
        var port = parsePort(aetherConfig);
        var managementPort = parseManagementPort(aetherConfig);
        var nodeLabels = collectNodeLabels();
        var peers = parsePeers(nodeId, port, nodeLabels, aetherConfig);
        var sliceConfig = parseSliceConfig(aetherConfig);
        var dhtConfig = parseDhtConfig(aetherConfig);
        logStartupInfo(nodeId, port, managementPort, peers, aetherConfig, sliceConfig);
        var coreMax = parseCoreMax(aetherConfig);
        var tlsBundle = resolveTls(nodeId, peers, aetherConfig).expect("Failed to resolve TLS configuration at node startup");
        var appHttpTls = aetherConfig.filter(AetherConfig::tlsEnabled).map(_ -> tlsBundle.tls());
        var config = AetherNodeConfig.builder().self(nodeId).coreNodes(peers).managementPort(managementPort).sliceConfig(sliceConfig).artifactRepo(dhtConfig).coreMax(coreMax).appHttp(resolveAppHttp(aetherConfig)).tls(appHttpTls).quicTls(tlsBundle.tls()).certificateProvider(tlsBundle.provider()).configProvider(resolveConfigProvider()).environment(resolveEnvironment(aetherConfig)).managementHttpProtocol(resolveManagementHttpProtocol(aetherConfig)).storageConfig(resolveStorage(aetherConfig)).backupConfig(resolveBackup(aetherConfig)).membership(resolveMembership(aetherConfig)).streaming(resolveStreaming(aetherConfig)).build();
        var node = AetherNode.aetherNode(config).expect("Failed to initialize Aether node at startup");
        registerShutdownHook(node);
        startNodeAndWait(node, nodeId);
    }

    private static AppHttpConfig resolveAppHttp(Option<AetherConfig> aetherConfig) {
        return aetherConfig.map(AetherConfig::appHttp)
                           .filter(AppHttpConfig::enabled)
                           .or(AppHttpConfig.appHttpConfig());
    }

    /// First-boot consistency gate: compares this container's `aether.cluster` label
    /// (read from the Docker daemon via `/var/run/docker.sock`) against the configured
    /// cluster name (sourced from `AETHER_CLUSTER_NAME` env). On mismatch, aborts startup
    /// with `System.exit(1)` so a misconfigured node never joins the wrong cluster — the
    /// failure surfaces immediately at deployment time, not as a silent membership drift.
    ///
    /// Skipped silently when not running in Docker, when the socket isn't mounted, or
    /// when either side of the comparison is empty (we don't claim disagreement against
    /// missing information). The `aetherConfig` parameter is retained so this hook can
    /// trivially extend to read `[cluster] name` from local TOML once that field exists
    /// on runtime `ClusterConfig` (currently bootstrap-only).
    @SuppressWarnings("unused")
    private void verifyClusterLabelConsistency(Option<AetherConfig> aetherConfig) {
        var configured = Option.option(System.getenv("AETHER_CLUSTER_NAME")).filter(s -> !s.isBlank()).or("");

        if (configured.isEmpty()) {
            log.debug("verifyClusterLabelConsistency: AETHER_CLUSTER_NAME not set — skipping label check");

            return;
        }

        ContainerLabelInspector.inspectSelfLabels().onPresent(labels -> ContainerLabelInspector.compareWithConfigured(configured,
                                                                                                                      labels).onSuccess(_ -> log.info("Cluster label consistency OK: aether.cluster='{}' matches AETHER_CLUSTER_NAME",
                                                                                                                                                      configured))
                                                                                                                     .onFailure(cause -> {
                                                                                                                                    log.error("FATAL: {}",
                                                                                                                                              cause.message());
                                                                                                                                    System.exit(1);
                                                                                                                                }));
    }

    private Result<TlsBundle> resolveTls(NodeId nodeId, List<NodeInfo> peers, Option<AetherConfig> aetherConfig) {
        var tlsCfg = aetherConfig.flatMap(AetherConfig::tls).or(org.pragmatica.aether.config.TlsConfig.tlsConfig());

        return resolveClusterSecret(tlsCfg).flatMap(SelfSignedCertificateProvider::selfSignedCertificateProvider)
                                   .flatMap(provider -> {
                                                var hostname = findHostnameFromPeers(nodeId, peers);
                                                return org.pragmatica.net.tcp.TlsConfig.fromProvider(provider,
                                                                                                     nodeId.id(),
                                                                                                     hostname)
                                                                                       .map(tc -> new TlsBundle(tc,
                                                                                                                provider));
                                            })
                                   .onFailure(cause -> log.error("Failed to setup TLS: {}",
                                                                 cause.message()));
    }

    private Option<ConfigurationProvider> resolveConfigProvider() {
        return findArg("--config=").map(Path::of)
                      .filter(p -> p.toFile()
                                    .exists())
                      .map(this::buildConfigProvider);
    }

    private Option<EnvironmentIntegration> resolveEnvironment(Option<AetherConfig> aetherConfig) {
        return aetherConfig.flatMap(AetherConfig::cloud)
                           .flatMap(cloudConfig -> EnvironmentIntegrationFactory.createFromConfig(cloudConfig)
                                                                                .onFailure(cause -> log.error("Failed to create cloud environment: {}",
                                                                                                              cause.message()))
                                                                                .option());
    }

    private static HttpProtocol resolveManagementHttpProtocol(Option<AetherConfig> aetherConfig) {
        return aetherConfig.map(cfg -> cfg.cluster()
                                          .ports()
                                          .managementHttpProtocol())
                           .or(HttpProtocol.H1);
    }

    private static Map<String, StorageConfig> resolveStorage(Option<AetherConfig> aetherConfig) {
        return aetherConfig.map(AetherConfig::storage)
                           .filter(m -> !m.isEmpty())
                           .or(Map.of());
    }

    private static Option<BackupConfig> resolveBackup(Option<AetherConfig> aetherConfig) {
        return aetherConfig.map(AetherConfig::backup)
                           .filter(BackupConfig::enabled)
                           .filter(b -> !b.path()
                                          .isBlank());
    }

    private static StreamingConfig resolveStreaming(Option<AetherConfig> aetherConfig) {
        return aetherConfig.map(AetherConfig::streaming)
                           .or(StreamingConfig.streamingConfig());
    }

    /// Membership v2 — Stage 6 wiring. Lifts the optional aether-config-side
    /// [`MembershipConfigBinding`] into the aether-deployment-side
    /// [`MembershipConfig`]. Absent → `none()` so [`AetherNodeConfig`] sees the same
    /// "no `[membership]` section, defaults will be applied at use sites" signal it sees
    /// today, preserving default-OFF behaviour exactly.
    private static Option<MembershipConfig> resolveMembership(Option<AetherConfig> aetherConfig) {
        return aetherConfig.flatMap(AetherConfig::membership).map(Main::liftMembershipBinding);
    }

    private static MembershipConfig liftMembershipBinding(MembershipConfigBinding binding) {
        return new MembershipConfig(binding.nttDepartureTimeout(),
                                    binding.quorumLossDrainThreshold());
    }

    private ConfigurationProvider buildConfigProvider(Path configPath) {
        var builder = ConfigurationProvider.builder();
        builder.withTomlFile(configPath);
        builder.withSystemProperties("aether.");
        builder.withEnvironment("AETHER_");

        return builder.build();
    }

    private static String findHostnameFromPeers(NodeId nodeId, List<NodeInfo> peers) {
        return Option.from(peers.stream().filter(n -> n.id()
                                                       .equals(nodeId)).findFirst())
                     .map(n -> n.address()
                                .host())
                     .or("localhost");
    }

    private static Result<byte[]> resolveClusterSecret(org.pragmatica.aether.config.TlsConfig tlsCfg) {
        return Option.option(tlsCfg.clusterSecret())
                     .filter(s -> !s.isBlank())
                     .orElse(Option.option(System.getenv("AETHER_CLUSTER_SECRET")).filter(s -> !s.isBlank()))
                     .map(s -> s.getBytes(StandardCharsets.UTF_8))
                     .toResult(MISSING_CLUSTER_SECRET);
    }

    private static final Cause MISSING_CLUSTER_SECRET = Causes.cause("No cluster secret configured. Set 'cluster_secret' in [tls] section "
                                                                    + "or AETHER_CLUSTER_SECRET environment variable. "
                                                                    + "A cluster secret is required for TLS certificate generation.");

    private SliceConfig parseSliceConfig(Option<AetherConfig> aetherConfig) {
        return aetherConfig.map(AetherConfig::slice)
                           .or(SliceConfig.sliceConfig());
    }

    private org.pragmatica.dht.DHTConfig parseDhtConfig(Option<AetherConfig> aetherConfig) {
        return aetherConfig.map(AetherConfig::dhtReplication)
                           .map(dhtRepl -> org.pragmatica.dht.DHTConfig.withReplication(dhtRepl.targetRf()))
                           .flatMap(Result::option)
                           .or(org.pragmatica.dht.DHTConfig.DEFAULT);
    }

    private Option<AetherConfig> loadConfig() {
        return findArg("--config=").map(Path::of)
                      .filter(p -> p.toFile()
                                    .exists())
                      .flatMap(this::loadConfigFile);
    }

    private Option<AetherConfig> loadConfigFile(Path path) {
        return ConfigLoader.load(path)
                           .onFailure(cause -> log.error("Failed to load config: {}",
                                                         cause.message()))
                           .option();
    }

    private void logStartupInfo(NodeId nodeId,
                                int port,
                                int managementPort,
                                List<NodeInfo> peers,
                                Option<AetherConfig> aetherConfig,
                                SliceConfig sliceConfig) {
        log.info("Starting Aether node {} on port {}", nodeId, port);
        log.info("Management API on port {}", managementPort);
        log.info("Peers: {}", peers);
        log.info("Slice repositories: {}", sliceConfig.repositories());
        aetherConfig.onPresent(this::logConfigDetails);
    }

    private void logConfigDetails(AetherConfig cfg) {
        log.info("Config: environment={}, nodes={}, heap={}",
                 cfg.environment().displayName(),
                 cfg.cluster().nodes(),
                 cfg.node().heap());
    }

    private void registerShutdownHook(AetherNode node) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdownNode(node)));
    }

    private void shutdownNode(AetherNode node) {
        log.info("Shutdown requested, stopping node...");
        node.stop().await();
        log.info("Node stopped");
    }

    private void startNodeAndWait(AetherNode node, NodeId nodeId) {
        node.start().onSuccess(_ -> log.info("Node {} is running. Press Ctrl+C to stop.", nodeId)).onFailure(cause -> exitWithError(cause.message())).await();
        waitForInterrupt();
    }

    private void exitWithError(String message) {
        log.error("Failed to start node: {}", message);
        System.exit(1);
    }

    private void waitForInterrupt() {
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private NodeId parseNodeId(Option<AetherConfig> aetherConfig) {
        return findArg("--node-id=").flatMap(id -> NodeId.nodeId(id).option())
                      .orElse(findEnv("AETHER_NODE_ID").flatMap(id -> NodeId.nodeId(id).option()))
                      .orElse(findEnv("NODE_ID").flatMap(id -> NodeId.nodeId(id).option()))
                      .orElse(findEnv("HOSTNAME").flatMap(id -> NodeId.nodeId(id).option()))
                      .or(NodeId::randomNodeId);
    }

    private int parsePort(Option<AetherConfig> aetherConfig) {
        return findArg("--port=").map(Integer::parseInt)
                      .orElse(findEnv("CLUSTER_PORT").map(Integer::parseInt))
                      .or(() -> portFromConfig(aetherConfig));
    }

    private int portFromConfig(Option<AetherConfig> aetherConfig) {
        return aetherConfig.map(cfg -> cfg.cluster()
                                          .ports()
                                          .cluster())
                           .or(DEFAULT_CLUSTER_PORT);
    }

    private int parseManagementPort(Option<AetherConfig> aetherConfig) {
        return findArg("--management-port=").map(Integer::parseInt)
                      .orElse(findEnv("MANAGEMENT_PORT").map(Integer::parseInt))
                      .or(() -> managementPortFromConfig(aetherConfig));
    }

    private int parseCoreMax(Option<AetherConfig> aetherConfig) {
        return findEnv("CORE_MAX").flatMap(s -> Result.lift(() -> Integer.parseInt(s)).option())
                      .orElse(aetherConfig.map(cfg -> cfg.cluster()
                                                         .coreMax()))
                      .or(0);
    }

    private int managementPortFromConfig(Option<AetherConfig> aetherConfig) {
        return aetherConfig.map(cfg -> cfg.cluster()
                                          .ports()
                                          .management())
                           .or(AetherNodeConfig.DEFAULT_MANAGEMENT_PORT);
    }

    private List<NodeInfo> parsePeers(NodeId self,
                                      int selfPort,
                                      Map<String, String> labels,
                                      Option<AetherConfig> aetherConfig) {
        var selfHost = resolveHostname();
        var selfInfo = NodeInfo.nodeInfo(self,
                                         nodeAddress(selfHost, selfPort).expect("self host is a valid node address"),
                                         NodeRole.ACTIVE,
                                         labels);

        return findArg("--peers=").map(peersStr -> parsePeersFromString(peersStr, self, selfInfo))
                      .orElse(findEnv("CLUSTER_PEERS").map(peersStr -> parsePeersFromString(peersStr, self, selfInfo)))
                      .orElse(aetherConfig.map(this::generatePeersFromConfig))
                      .or(() -> List.of(selfInfo));
    }

    private List<NodeInfo> generatePeersFromConfig(AetherConfig aetherConfig) {
        var nodes = aetherConfig.cluster().nodes();
        var clusterPort = aetherConfig.cluster().ports().cluster();
        var env = aetherConfig.environment();

        return IntStream.range(0, nodes)
                        .mapToObj(i -> createNodeInfoForIndex(i, clusterPort, env))
                        .toList();
    }

    private NodeInfo createNodeInfoForIndex(int index, int clusterPort, Environment env) {
        var host = env == Environment.DOCKER
                   ? "aether-node-" + index
                   : "localhost";
        var port = clusterPort + (env == Environment.LOCAL
                                  ? index
                                  : 0);
        return NodeInfo.nodeInfo(NodeId.nodeId("node-" + index).expect("generated node id must be valid"),
                                 nodeAddress(host, port).expect("generated node address must be valid"));
    }

    private List<NodeInfo> parsePeersFromString(String peersStr, NodeId self, NodeInfo selfInfo) {
        var peers = Arrays.stream(peersStr.split(",")).map(String::trim).filter(s -> !s.isEmpty()).flatMap(peerStr -> parsePeerAddress(peerStr).stream()).toList();
        return ensureSelfIncluded(peers, self, selfInfo);
    }

    private List<NodeInfo> ensureSelfIncluded(List<NodeInfo> peers, NodeId self, NodeInfo selfInfo) {
        var selfMissing = peers.stream().noneMatch(p -> p.id()
                                                         .equals(self));

        if (selfMissing) {
            var allPeers = new ArrayList<>(peers);
            allPeers.add(selfInfo);

            return List.copyOf(allPeers);
        }

        return peers;
    }

    private Option<NodeInfo> parsePeerAddress(String peerStr) {
        var parts = peerStr.split(":");

        return switch (parts.length) {
            case 2 -> parseHostPortPeer(parts);
            case 3 -> parseIdHostPortPeer(parts);
            default -> logInvalidPeerFormat(peerStr);
        };
    }

    private Option<NodeInfo> parseHostPortPeer(String[] parts) {
        var host = parts[0];
        var port = Integer.parseInt(parts[1]);
        var nodeId = NodeId.nodeId("node-" + host + "-" + port).expect("generated node id must be valid");

        return nodeAddress(host, port).map(addr -> NodeInfo.nodeInfo(nodeId, addr))
                          .option();
    }

    private Option<NodeInfo> parseIdHostPortPeer(String[] parts) {
        var host = parts[1];
        var port = Integer.parseInt(parts[2]);

        return NodeId.nodeId(parts[0])
                     .flatMap(nodeId -> nodeAddress(host, port).map(addr -> NodeInfo.nodeInfo(nodeId, addr)))
                     .option();
    }

    private Option<NodeInfo> logInvalidPeerFormat(String peerStr) {
        log.warn("Invalid peer format: {}. Expected host:port or nodeId:host:port", peerStr);

        return Option.none();
    }

    private Map<String, String> collectNodeLabels() {
        var labels = new HashMap<String, String>();
        labels.put(NodeInfo.LABEL_HOSTNAME, resolveHostname());
        findEnv("AETHER_ZONE").onPresent(z -> labels.put(NodeInfo.LABEL_ZONE, z));
        findEnv("AETHER_INSTANCE_TYPE").onPresent(t -> labels.put(NodeInfo.LABEL_INSTANCE_TYPE, t));
        findEnv("AETHER_POOL").onPresent(p -> labels.put(NodeInfo.LABEL_POOL, p));

        return Map.copyOf(labels);
    }

    @SuppressWarnings("JBCT-RET-01")
    private static String resolveHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return findContainerHostname().or("unknown");
        }
    }

    private static Option<String> findContainerHostname() {
        return Option.option(System.getenv("HOSTNAME")).filter(s -> !s.isBlank());
    }

    private Option<String> findArg(String prefix) {
        return Option.from(Arrays.stream(args)
                                 .filter(arg -> arg.startsWith(prefix))
                                 .map(arg -> arg.substring(prefix.length()))
                                 .findFirst());
    }

    private Option<String> findEnv(String name) {
        return Option.option(System.getenv(name)).filter(s -> !s.isBlank());
    }
}
