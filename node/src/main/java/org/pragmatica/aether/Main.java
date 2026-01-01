package org.pragmatica.aether;

import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.AetherNodeConfig;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;

import java.util.Arrays;
import java.util.List;

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
 *   <li>--node-id=&lt;id&gt;       Node identifier (default: random)</li>
 *   <li>--port=&lt;port&gt;        Cluster port (default: 8090)</li>
 *   <li>--peers=&lt;host:port,...&gt;  Comma-separated list of peer addresses</li>
 * </ul>
 */
public record Main(String[] args) {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        new Main(args).run();
    }

    private void run() {
        var nodeId = parseNodeId();
        var port = parsePort();
        var peers = parsePeers(nodeId, port);
        log.info("Starting Aether node {} on port {}", nodeId, port);
        log.info("Peers: {}", peers);
        var config = AetherNodeConfig.aetherNodeConfig(nodeId, port, peers);
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

    private NodeId parseNodeId() {
        return findArg("--node-id=")
               .map(NodeId::nodeId)
               .orElseGet(NodeId::randomNodeId);
    }

    private int parsePort() {
        return findArg("--port=")
               .map(Integer::parseInt)
               .orElse(8090);
    }

    private List<NodeInfo> parsePeers(NodeId self, int selfPort) {
        var selfInfo = nodeInfo(self, nodeAddress("localhost", selfPort));
        return findArg("--peers=")
               .map(peersStr -> Arrays.stream(peersStr.split(","))
                                      .map(String::trim)
                                      .filter(s -> !s.isEmpty())
                                      .flatMap(peerStr -> parsePeerAddress(peerStr)
                                                          .stream())
                                      .toList())
               .map(peers -> {
                        // Include self in the peer list if not already there
        if (peers.stream()
                 .noneMatch(p -> p.id()
                                  .equals(self))) {
                        var allPeers = new java.util.ArrayList<>(peers);
                        allPeers.add(selfInfo);
                        return List.copyOf(allPeers);
                    }
                        return peers;
                    })
               .orElse(List.of(selfInfo));
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
}
