package org.pragmatica.aether;

import org.pragmatica.aether.config.AetherConfig;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.BackupConfig;
import org.pragmatica.aether.config.ConfigLoader;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.aether.config.Environment;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.environment.EnvironmentIntegrationFactory;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.AetherNodeConfig;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.net.tcp.security.CertificateProvider;
import org.pragmatica.net.tcp.security.SelfSignedCertificateProvider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// Main entry point for starting an Aether cluster node.
///
///
/// Usage: java -jar aether-node.jar [options]
///
///
/// Options:
///
///   - --config=&lt;path&gt;    Path to aether.toml config file
///   - --node-id=&lt;id&gt;     Node identifier (default: from config or random)
///   - --port=&lt;port&gt;      Cluster port (default: from config or 8090)
///   - --peers=&lt;host:port,...&gt;  Comma-separated list of peer addresses
///
///
///
/// When --config is provided, values from the config file are used as defaults,
/// but can be overridden by command-line arguments.
@SuppressWarnings("JBCT-RET-01")
public record Main( String[] args) {
    private static final Logger log = LoggerFactory.getLogger(Main.class);
    private static final int DEFAULT_CLUSTER_PORT = 8090;

    public static void main(String[] args) {
        new Main(args).run();
    }

    private void run() {
        var aetherConfig = loadConfig();
        var nodeId = parseNodeId(aetherConfig);
        var port = parsePort(aetherConfig);
        var managementPort = parseManagementPort(aetherConfig);
        var peers = parsePeers(nodeId, port, aetherConfig);
        var sliceConfig = parseSliceConfig(aetherConfig);
        var dhtConfig = parseDhtConfig(aetherConfig);
        logStartupInfo(nodeId, port, managementPort, peers, aetherConfig, sliceConfig);
        var coreMax = parseCoreMax(aetherConfig);
        var config = AetherNodeConfig.aetherNodeConfig(nodeId,
                                                       port,
                                                       peers,
                                                       AetherNodeConfig.defaultSliceActionConfig(),
                                                       sliceConfig,
                                                       managementPort,
                                                       dhtConfig,
                                                       coreMax);
        var withBackup = wireBackupIfConfigured(config, aetherConfig);
        var withConfig = wireConfigProvider(withBackup);
        var withAppHttp = wireAppHttpIfConfigured(withConfig, aetherConfig);
        var withMgmtProtocol = wireManagementHttpProtocol(withAppHttp, aetherConfig);
        var withTls = wireTlsIfEnabled(withMgmtProtocol, aetherConfig);
        warnInsecureTlsInNonLocal(withTls, aetherConfig);
        var withStorage = wireStorageIfConfigured(withTls, aetherConfig);
        var finalConfig = wireCloudIfConfigured(withStorage, aetherConfig);
        var node = AetherNode.aetherNode(finalConfig).unwrap();
        registerShutdownHook(node);
        startNodeAndWait(node, nodeId);
    }

    private AetherNodeConfig wireCloudIfConfigured(AetherNodeConfig config, Option<AetherConfig> aetherConfig) {
        return aetherConfig.flatMap(AetherConfig::cloud).flatMap(cloudConfig -> EnvironmentIntegrationFactory.createFromConfig(cloudConfig).onFailure(cause -> log.error("Failed to create cloud environment: {}",
                                                                                                                                                                         cause.message()))
                                                                                                                              .option())
                                   .map(config::withEnvironment)
                                   .or(config);
    }

    private static AetherNodeConfig wireBackupIfConfigured(AetherNodeConfig config,
                                                           Option<AetherConfig> aetherConfig) {
        return aetherConfig.map(AetherConfig::backup).filter(BackupConfig::enabled)
                               .filter(b -> !b.path().isBlank())
                               .map(config::withBackupConfig)
                               .or(config);
    }

    private AetherNodeConfig wireConfigProvider(AetherNodeConfig config) {
        return findArg("--config=").map(Path::of)
                      .filter(p -> p.toFile().exists())
                      .map(this::buildConfigProvider)
                      .map(config::withConfigProvider)
                      .or(config);
    }

    private ConfigurationProvider buildConfigProvider(Path configPath) {
        var builder = ConfigurationProvider.builder();
        builder.withTomlFile(configPath);
        builder.withSystemProperties("aether.");
        builder.withEnvironment("AETHER_");
        return builder.build();
    }

    private AetherNodeConfig wireAppHttpIfConfigured(AetherNodeConfig config, Option<AetherConfig> aetherConfig) {
        return aetherConfig.map(AetherConfig::appHttp).filter(AppHttpConfig::enabled)
                               .map(config::withAppHttp)
                               .or(config);
    }

    private static AetherNodeConfig wireManagementHttpProtocol(AetherNodeConfig config,
                                                               Option<AetherConfig> aetherConfig) {
        return aetherConfig.map(cfg -> cfg.cluster().ports()
                                                  .managementHttpProtocol()).map(config::withManagementHttpProtocol)
                               .or(config);
    }

    private static AetherNodeConfig wireStorageIfConfigured(AetherNodeConfig config,
                                                            Option<AetherConfig> aetherConfig) {
        return aetherConfig.map(AetherConfig::storage).filter(m -> !m.isEmpty())
                               .map(config::withStorage)
                               .or(config);
    }

    private void warnInsecureTlsInNonLocal(AetherNodeConfig config, Option<AetherConfig> aetherConfig) {
        var isNonLocal = aetherConfig.map(AetherConfig::environment).filter(env -> env != Environment.LOCAL)
                                         .isPresent();
        if ( isNonLocal && config.tls().isEmpty()) {
        log.warn("*** SECURITY WARNING: Running without TLS in non-LOCAL environment. " + "Cluster transport will use insecure self-signed certificates with no mutual authentication. " + "Configure TLS via [tls] section in aether.toml for production deployments. ***");}
    }

    private AetherNodeConfig wireTlsIfEnabled(AetherNodeConfig config, Option<AetherConfig> aetherConfig) {
        return aetherConfig.filter(AetherConfig::tlsEnabled).flatMap(AetherConfig::tls)
                                  .filter(org.pragmatica.aether.config.TlsConfig::autoGenerate)
                                  .map(tlsCfg -> wireAutoTls(config, tlsCfg))
                                  .or(config);
    }

    private AetherNodeConfig wireAutoTls(AetherNodeConfig config,
                                         org.pragmatica.aether.config.TlsConfig tlsCfg) {
        return resolveClusterSecret(tlsCfg).flatMap(SelfSignedCertificateProvider::selfSignedCertificateProvider)
                                   .flatMap(provider -> wireProviderToConfig(config, provider))
                                   .onFailure(cause -> log.error("Failed to setup TLS: {}",
                                                                 cause.message()))
                                   .or(config);
    }

    private static Result<byte[]> resolveClusterSecret(org.pragmatica.aether.config.TlsConfig tlsCfg) {
        return Option.option(tlsCfg.clusterSecret()).filter(s -> !s.isBlank())
                            .orElse(Option.option(System.getenv("AETHER_CLUSTER_SECRET")).filter(s -> !s.isBlank()))
                            .map(s -> s.getBytes(StandardCharsets.UTF_8))
                            .toResult(MISSING_CLUSTER_SECRET);
    }

    private static final Cause MISSING_CLUSTER_SECRET = Causes.cause("No cluster secret configured. Set 'cluster_secret' in [tls] section " + "or AETHER_CLUSTER_SECRET environment variable. " + "A cluster secret is required for TLS certificate generation.");

    private static Result<AetherNodeConfig> wireProviderToConfig(AetherNodeConfig config,
                                                                 CertificateProvider provider) {
        var nodeId = config.self().id();
        var hostname = findHostname(config);
        return org.pragmatica.net.tcp.TlsConfig.fromProvider(provider, nodeId, hostname)
        .map(tlsConfig -> config.withTls(tlsConfig).withCertificateProvider(provider));
    }

    private static String findHostname(AetherNodeConfig config) {
        return Option.from(config.topology().coreNodes()
                                          .stream()
                                          .filter(n -> n.id().equals(config.self()))
                                          .findFirst()).map(n -> n.address().host())
                          .or("localhost");
    }

    private SliceConfig parseSliceConfig(Option<AetherConfig> aetherConfig) {
        return aetherConfig.map(AetherConfig::slice).or(SliceConfig.sliceConfig());
    }

    private org.pragmatica.dht.DHTConfig parseDhtConfig(Option<AetherConfig> aetherConfig) {
        return aetherConfig.map(AetherConfig::dhtReplication).map(dhtRepl -> org.pragmatica.dht.DHTConfig.withReplication(dhtRepl.targetRf()))
                               .flatMap(Result::option)
                               .or(org.pragmatica.dht.DHTConfig.DEFAULT);
    }

    private Option<AetherConfig> loadConfig() {
        return findArg("--config=").map(Path::of)
                      .filter(p -> p.toFile().exists())
                      .flatMap(this::loadConfigFile);
    }

    private Option<AetherConfig> loadConfigFile(Path path) {
        return ConfigLoader.load(path).onFailure(cause -> log.error("Failed to load config: {}",
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
        node.start().onSuccess(_ -> log.info("Node {} is running. Press Ctrl+C to stop.", nodeId))
                  .onFailure(cause -> exitWithError(cause.message()))
                  .await();
        waitForInterrupt();
    }

    private void exitWithError(String message) {
        log.error("Failed to start node: {}", message);
        System.exit(1);
    }

    private void waitForInterrupt() {
        try {
            Thread.currentThread().join();
        }

























        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private NodeId parseNodeId(Option<AetherConfig> aetherConfig) {
        return findArg("--node-id=").flatMap(id -> NodeId.nodeId(id).option())
                      .orElse(findEnv("NODE_ID").flatMap(id -> NodeId.nodeId(id).option()))
                      .or(NodeId::randomNodeId);
    }

    private int parsePort(Option<AetherConfig> aetherConfig) {
        return findArg("--port=").map(Integer::parseInt)
                      .orElse(findEnv("CLUSTER_PORT").map(Integer::parseInt))
                      .or(() -> portFromConfig(aetherConfig));
    }

    private int portFromConfig(Option<AetherConfig> aetherConfig) {
        return aetherConfig.map(cfg -> cfg.cluster().ports()
                                                  .cluster()).or(DEFAULT_CLUSTER_PORT);
    }

    private int parseManagementPort(Option<AetherConfig> aetherConfig) {
        return findArg("--management-port=").map(Integer::parseInt)
                      .orElse(findEnv("MANAGEMENT_PORT").map(Integer::parseInt))
                      .or(() -> managementPortFromConfig(aetherConfig));
    }

    private int parseCoreMax(Option<AetherConfig> aetherConfig) {
        return findEnv("CORE_MAX").flatMap(s -> Result.lift(() -> Integer.parseInt(s)).option())
                      .orElse(aetherConfig.map(cfg -> cfg.cluster().coreMax()))
                      .or(0);
    }

    private int managementPortFromConfig(Option<AetherConfig> aetherConfig) {
        return aetherConfig.map(cfg -> cfg.cluster().ports()
                                                  .management())
        .or(AetherNodeConfig.DEFAULT_MANAGEMENT_PORT);
    }

    private List<NodeInfo> parsePeers(NodeId self, int selfPort, Option<AetherConfig> aetherConfig) {
        var selfInfo = NodeInfo.nodeInfo(self, nodeAddress("localhost", selfPort).unwrap());
        return findArg("--peers=").map(peersStr -> parsePeersFromString(peersStr, self, selfInfo))
                      .orElse(findEnv("CLUSTER_PEERS").map(peersStr -> parsePeersFromString(peersStr, self, selfInfo)))
                      .orElse(aetherConfig.map(this::generatePeersFromConfig))
                      .or(() -> List.of(selfInfo));
    }

    private List<NodeInfo> generatePeersFromConfig(AetherConfig aetherConfig) {
        var nodes = aetherConfig.cluster().nodes();
        var clusterPort = aetherConfig.cluster().ports()
                                              .cluster();
        var env = aetherConfig.environment();
        return IntStream.range(0, nodes).mapToObj(i -> createNodeInfoForIndex(i, clusterPort, env))
                              .toList();
    }

    private NodeInfo createNodeInfoForIndex(int index, int clusterPort, Environment env) {
        var host = env == Environment.DOCKER
                   ? "aether-node-" + index
                   : "localhost";
        var port = clusterPort + (env == Environment.LOCAL
                                  ? index
                                  : 0);
        return NodeInfo.nodeInfo(NodeId.nodeId("node-" + index).unwrap(),
                                 nodeAddress(host, port).unwrap());
    }

    private List<NodeInfo> parsePeersFromString(String peersStr, NodeId self, NodeInfo selfInfo) {
        var peers = Arrays.stream(peersStr.split(",")).map(String::trim)
                                 .filter(s -> !s.isEmpty())
                                 .flatMap(peerStr -> parsePeerAddress(peerStr).stream())
                                 .toList();
        return ensureSelfIncluded(peers, self, selfInfo);
    }

    private List<NodeInfo> ensureSelfIncluded(List<NodeInfo> peers, NodeId self, NodeInfo selfInfo) {
        var selfMissing = peers.stream().noneMatch(p -> p.id().equals(self));
        if ( selfMissing) {
            var allPeers = new ArrayList<>(peers);
            allPeers.add(selfInfo);
            return List.copyOf(allPeers);
        }
        return peers;
    }

    private Option<NodeInfo> parsePeerAddress(String peerStr) {
        var parts = peerStr.split(":");
        return switch (parts.length) {case 2 -> parseHostPortPeer(parts);case 3 -> parseIdHostPortPeer(parts);default -> logInvalidPeerFormat(peerStr);};
    }

    private Option<NodeInfo> parseHostPortPeer(String[] parts) {
        var host = parts[0];
        var port = Integer.parseInt(parts[1]);
        var nodeId = NodeId.nodeId("node-" + host + "-" + port).unwrap();
        return nodeAddress(host, port).map(addr -> NodeInfo.nodeInfo(nodeId, addr))
                          .option();
    }

    private Option<NodeInfo> parseIdHostPortPeer(String[] parts) {
        var host = parts[1];
        var port = Integer.parseInt(parts[2]);
        return NodeId.nodeId(parts[0]).flatMap(nodeId -> nodeAddress(host, port).map(addr -> NodeInfo.nodeInfo(nodeId,
                                                                                                               addr)))
                            .option();
    }

    private Option<NodeInfo> logInvalidPeerFormat(String peerStr) {
        log.warn("Invalid peer format: {}. Expected host:port or nodeId:host:port", peerStr);
        return Option.none();
    }

    private Option<String> findArg(String prefix) {
        return Option.from(Arrays.stream(args).filter(arg -> arg.startsWith(prefix))
                                        .map(arg -> arg.substring(prefix.length()))
                                        .findFirst());
    }

    private Option<String> findEnv(String name) {
        return Option.option(System.getenv(name)).filter(s -> !s.isBlank());
    }
}
