package org.pragmatica.aether;

import org.pragmatica.aether.config.AetherConfig;
import org.pragmatica.aether.config.ConfigLoader;
import org.pragmatica.aether.config.Environment;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.AetherNodeConfig;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Option;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.consensus.net.NodeInfo.nodeInfo;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/**
 * Main entry point for starting an Aether cluster node.
 *
 * <p>Usage: java -jar aether-node.jar [options]
 *
 * <p>Options:
 * <ul>
 *   <li>--config=&lt;path&gt;    Path to aether.toml config file</li>
 *   <li>--node-id=&lt;id&gt;     Node identifier (default: from config or random)</li>
 *   <li>--port=&lt;port&gt;      Cluster port (default: from config or 8090)</li>
 *   <li>--peers=&lt;host:port,...&gt;  Comma-separated list of peer addresses</li>
 * </ul>
 *
 * <p>When --config is provided, values from the config file are used as defaults,
 * but can be overridden by command-line arguments.
 */
public record Main(String[] args) {
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
        logStartupInfo(nodeId, port, managementPort, peers, aetherConfig);
        var config = AetherNodeConfig.aetherNodeConfig(nodeId,
                                                       port,
                                                       peers,
                                                       AetherNodeConfig.defaultSliceConfig(),
                                                       managementPort);
        var node = AetherNode.aetherNode(config)
                             .unwrap();
        registerShutdownHook(node);
        startNodeAndWait(node, nodeId);
    }

    private Option<AetherConfig> loadConfig() {
        return findArg("--config=")
                      .map(Path::of)
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
                                Option<AetherConfig> aetherConfig) {
        log.info("Starting Aether node {} on port {}", nodeId, port);
        log.info("Management API on port {}", managementPort);
        log.info("Peers: {}", peers);
        aetherConfig.onPresent(cfg -> logConfigDetails(cfg));
    }

    private void logConfigDetails(AetherConfig cfg) {
        log.info("Config: environment={}, nodes={}, heap={}",
                 cfg.environment()
                    .displayName(),
                 cfg.cluster()
                    .nodes(),
                 cfg.node()
                    .heap());
    }

    private void registerShutdownHook(AetherNode node) {
        Runtime.getRuntime()
               .addShutdownHook(new Thread(() -> shutdownNode(node)));
    }

    private void shutdownNode(AetherNode node) {
        log.info("Shutdown requested, stopping node...");
        node.stop()
            .await();
        log.info("Node stopped");
    }

    private void startNodeAndWait(AetherNode node, NodeId nodeId) {
        node.start()
            .onSuccess(_ -> log.info("Node {} is running. Press Ctrl+C to stop.", nodeId))
            .onFailure(cause -> exitWithError(cause.message()))
            .await();
        waitForInterrupt();
    }

    private void exitWithError(String message) {
        log.error("Failed to start node: {}", message);
        System.exit(1);
    }

    private void waitForInterrupt() {
        try{
            Thread.currentThread()
                  .join();
        } catch (InterruptedException e) {
            Thread.currentThread()
                  .interrupt();
        }
    }

    private NodeId parseNodeId(Option<AetherConfig> aetherConfig) {
        return findArg("--node-id=")
                      .map(NodeId::nodeId)
                      .orElse(findEnv("NODE_ID")
                                     .map(NodeId::nodeId))
                      .or(NodeId::randomNodeId);
    }

    private int parsePort(Option<AetherConfig> aetherConfig) {
        return findArg("--port=")
                      .map(Integer::parseInt)
                      .orElse(findEnv("CLUSTER_PORT")
                                     .map(Integer::parseInt))
                      .or(() -> portFromConfig(aetherConfig));
    }

    private int portFromConfig(Option<AetherConfig> aetherConfig) {
        return aetherConfig.map(cfg -> cfg.cluster()
                                          .ports()
                                          .cluster())
                           .or(DEFAULT_CLUSTER_PORT);
    }

    private int parseManagementPort(Option<AetherConfig> aetherConfig) {
        return findArg("--management-port=")
                      .map(Integer::parseInt)
                      .orElse(findEnv("MANAGEMENT_PORT")
                                     .map(Integer::parseInt))
                      .or(() -> managementPortFromConfig(aetherConfig));
    }

    private int managementPortFromConfig(Option<AetherConfig> aetherConfig) {
        return aetherConfig.map(cfg -> cfg.cluster()
                                          .ports()
                                          .management())
                           .or(AetherNodeConfig.DEFAULT_MANAGEMENT_PORT);
    }

    private List<NodeInfo> parsePeers(NodeId self, int selfPort, Option<AetherConfig> aetherConfig) {
        var selfInfo = nodeInfo(self, nodeAddress("localhost", selfPort));
        return findArg("--peers=")
                      .map(peersStr -> parsePeersFromString(peersStr, self, selfInfo))
                      .orElse(findEnv("CLUSTER_PEERS")
                                     .map(peersStr -> parsePeersFromString(peersStr, self, selfInfo)))
                      .orElse(aetherConfig.map(this::generatePeersFromConfig))
                      .or(() -> List.of(selfInfo));
    }

    private List<NodeInfo> generatePeersFromConfig(AetherConfig aetherConfig) {
        var nodes = aetherConfig.cluster()
                                .nodes();
        var clusterPort = aetherConfig.cluster()
                                      .ports()
                                      .cluster();
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
        return nodeInfo(NodeId.nodeId("node-" + index), nodeAddress(host, port));
    }

    private List<NodeInfo> parsePeersFromString(String peersStr, NodeId self, NodeInfo selfInfo) {
        var peers = Arrays.stream(peersStr.split(","))
                          .map(String::trim)
                          .filter(s -> !s.isEmpty())
                          .flatMap(peerStr -> parsePeerAddress(peerStr)
                                                              .stream())
                          .toList();
        return ensureSelfIncluded(peers, self, selfInfo);
    }

    private List<NodeInfo> ensureSelfIncluded(List<NodeInfo> peers, NodeId self, NodeInfo selfInfo) {
        var selfMissing = peers.stream()
                               .noneMatch(p -> p.id()
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
        var nodeId = NodeId.nodeId("node-" + host + "-" + port);
        return Option.option(nodeInfo(nodeId, nodeAddress(host, port)));
    }

    private Option<NodeInfo> parseIdHostPortPeer(String[] parts) {
        var nodeId = NodeId.nodeId(parts[0]);
        var host = parts[1];
        var port = Integer.parseInt(parts[2]);
        return Option.option(nodeInfo(nodeId, nodeAddress(host, port)));
    }

    private Option<NodeInfo> logInvalidPeerFormat(String peerStr) {
        log.warn("Invalid peer format: {}. Expected host:port or nodeId:host:port", peerStr);
        return Option.none();
    }

    private Option<String> findArg(String prefix) {
        return Option.from(Arrays.stream(args)
                                 .filter(arg -> arg.startsWith(prefix))
                                 .map(arg -> arg.substring(prefix.length()))
                                 .findFirst());
    }

    private Option<String> findEnv(String name) {
        return Option.option(System.getenv(name))
                     .filter(s -> !s.isBlank());
    }
}
