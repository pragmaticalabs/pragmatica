package org.pragmatica.aether;

import org.pragmatica.aether.config.AetherConfig;
import org.pragmatica.aether.config.ConfigLoader;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.AetherNodeConfig;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;

import java.nio.file.Path;
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
        // Check for config file first
        var configPath = findArg("--config=");
        AetherConfig aetherConfig = configPath.map(Path::of)
                                              .filter(p -> p.toFile()
                                                            .exists())
                                              .flatMap(p -> ConfigLoader.load(p)
                                                                        .fold(cause -> {
                                                                                  log.error("Failed to load config: {}",
                                                                                            cause.message());
                                                                                  return java.util.Optional.<AetherConfig>empty();
                                                                              },
                                                                              java.util.Optional::of))
                                              .orElse(null);
        var nodeId = parseNodeId(aetherConfig);
        var port = parsePort(aetherConfig);
        var managementPort = parseManagementPort(aetherConfig);
        var peers = parsePeers(nodeId, port, aetherConfig);
        log.info("Starting Aether node {} on port {}", nodeId, port);
        log.info("Management API on port {}", managementPort);
        log.info("Peers: {}", peers);
        if (aetherConfig != null) {
            log.info("Config: environment={}, nodes={}, heap={}",
                     aetherConfig.environment()
                                 .displayName(),
                     aetherConfig.cluster()
                                 .nodes(),
                     aetherConfig.node()
                                 .heap());
        }
        var config = AetherNodeConfig.aetherNodeConfig(nodeId,
                                                       port,
                                                       peers,
                                                       AetherNodeConfig.defaultSliceConfig(),
                                                       managementPort);
        var node = AetherNode.aetherNode(config);
        // Register shutdown hook
        Runtime.getRuntime()
               .addShutdownHook(new Thread(() -> {
                                               log.info("Shutdown requested, stopping node...");
                                               node.stop()
                                                   .await();
                                               log.info("Node stopped");
                                           }));
        // Start the node
        node.start()
            .onSuccess(_ -> log.info("Node {} is running. Press Ctrl+C to stop.", nodeId))
            .onFailure(cause -> {
                log.error("Failed to start node: {}",
                          cause.message());
                System.exit(1);
            })
            .await();
        // Keep main thread alive
        try{
            Thread.currentThread()
                  .join();
        } catch (InterruptedException e) {
            Thread.currentThread()
                  .interrupt();
        }
    }

    private NodeId parseNodeId(AetherConfig aetherConfig) {
        // Command-line takes precedence
        return findArg("--node-id=")
               .map(NodeId::nodeId)
               .or(() -> findEnv("NODE_ID")
                         .map(NodeId::nodeId))
               .orElseGet(NodeId::randomNodeId);
    }

    private int parsePort(AetherConfig aetherConfig) {
        // Command-line takes precedence, then env, then config, then default
        return findArg("--port=")
               .map(Integer::parseInt)
               .or(() -> findEnv("CLUSTER_PORT")
                         .map(Integer::parseInt))
               .orElseGet(() -> {
                              if (aetherConfig != null) {
                              return aetherConfig.cluster()
                                                 .ports()
                                                 .cluster();
                          }
                              return DEFAULT_CLUSTER_PORT;
                          });
    }

    private int parseManagementPort(AetherConfig aetherConfig) {
        return findArg("--management-port=")
               .map(Integer::parseInt)
               .or(() -> findEnv("MANAGEMENT_PORT")
                         .map(Integer::parseInt))
               .orElseGet(() -> {
                              if (aetherConfig != null) {
                              return aetherConfig.cluster()
                                                 .ports()
                                                 .management();
                          }
                              return AetherNodeConfig.DEFAULT_MANAGEMENT_PORT;
                          });
    }

    private List<NodeInfo> parsePeers(NodeId self, int selfPort, AetherConfig aetherConfig) {
        var selfInfo = nodeInfo(self, nodeAddress("localhost", selfPort));
        // Check command-line first
        var peersArg = findArg("--peers=");
        if (peersArg.isPresent()) {
            return parsePeersFromString(peersArg.get(), self, selfInfo);
        }
        // Check environment variable
        var peersEnv = findEnv("CLUSTER_PEERS");
        if (peersEnv.isPresent()) {
            return parsePeersFromString(peersEnv.get(), self, selfInfo);
        }
        // If config is provided, generate peer list from node count
        if (aetherConfig != null) {
            var nodes = aetherConfig.cluster()
                                    .nodes();
            var clusterPort = aetherConfig.cluster()
                                          .ports()
                                          .cluster();
            // Generate peer addresses for all nodes
            return IntStream.range(0, nodes)
                            .mapToObj(i -> {
                                          var host = aetherConfig.environment() == org.pragmatica.aether.config.Environment.DOCKER
                                                     ? "aether-node-" + i
                                                     : "localhost";
                                          // Local deployment
            var port = clusterPort + (aetherConfig.environment() == org.pragmatica.aether.config.Environment.LOCAL
                                      ? i
                                      : 0);
                                          var nodeIdStr = "node-" + i;
                                          return nodeInfo(NodeId.nodeId(nodeIdStr),
                                                          nodeAddress(host, port));
                                      })
                            .toList();
        }
        // Default: just self
        return List.of(selfInfo);
    }

    private List<NodeInfo> parsePeersFromString(String peersStr, NodeId self, NodeInfo selfInfo) {
        var peers = Arrays.stream(peersStr.split(","))
                          .map(String::trim)
                          .filter(s -> !s.isEmpty())
                          .flatMap(peerStr -> parsePeerAddress(peerStr)
                                              .stream())
                          .toList();
        // Include self in the peer list if not already there
        if (peers.stream()
                 .noneMatch(p -> p.id()
                                  .equals(self))) {
            var allPeers = new java.util.ArrayList<>(peers);
            allPeers.add(selfInfo);
            return List.copyOf(allPeers);
        }
        return peers;
    }

    private java.util.Optional<NodeInfo> parsePeerAddress(String peerStr) {
        var parts = peerStr.split(":");
        if (parts.length == 2) {
            var host = parts[0];
            var port = Integer.parseInt(parts[1]);
            // Generate node ID from address for discovery
            var nodeId = NodeId.nodeId("node-" + host + "-" + port);
            return java.util.Optional.of(nodeInfo(nodeId, nodeAddress(host, port)));
        }else if (parts.length == 3) {
            // Format: nodeId:host:port
            var nodeId = NodeId.nodeId(parts[0]);
            var host = parts[1];
            var port = Integer.parseInt(parts[2]);
            return java.util.Optional.of(nodeInfo(nodeId, nodeAddress(host, port)));
        }
        log.warn("Invalid peer format: {}. Expected host:port or nodeId:host:port", peerStr);
        return java.util.Optional.empty();
    }

    private java.util.Optional<String> findArg(String prefix) {
        return Arrays.stream(args)
                     .filter(arg -> arg.startsWith(prefix))
                     .map(arg -> arg.substring(prefix.length()))
                     .findFirst();
    }

    private java.util.Optional<String> findEnv(String name) {
        return java.util.Optional.ofNullable(System.getenv(name))
                   .filter(s -> !s.isBlank());
    }
}
