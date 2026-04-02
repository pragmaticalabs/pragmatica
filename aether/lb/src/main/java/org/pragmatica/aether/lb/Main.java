package org.pragmatica.aether.lb;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.net.NodeRole;
import org.pragmatica.lang.Option;

import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// Main entry point for starting an Aether passive load balancer.
///
/// Usage: java -jar aether-lb.jar --peers=host1:6000,host2:6000 [options]
///
/// Options:
///   --http-port=<port>      HTTP listening port (default: 8080)
///   --node-id=<id>          Node identifier (default: lb-passive)
///   --cluster-port=<port>   Cluster communication port (default: 7000)
///   --peers=<host:port,...>  Comma-separated list of cluster node addresses (required)
@SuppressWarnings("JBCT-RET-01")
public record Main( String[] args) {
    private static final Logger log = LoggerFactory.getLogger(Main.class);
    private static final int DEFAULT_HTTP_PORT = 8080;
    private static final int DEFAULT_CLUSTER_PORT = 7000;
    private static final String DEFAULT_NODE_ID = "lb-passive";

    public static void main(String[] args) {
        new Main(args).run();
    }

    private void run() {
        var httpPort = parseIntOption("--http-port=", "LB_HTTP_PORT").or(DEFAULT_HTTP_PORT);
        var clusterPort = parseIntOption("--cluster-port=", "LB_CLUSTER_PORT").or(DEFAULT_CLUSTER_PORT);
        var nodeId = parseNodeId();
        var peers = parsePeers();
        if ( peers.isEmpty()) {
            log.error("No peers specified. Use --peers=host1:port1,host2:port2 or set PEERS env var");
            System.exit(1);
        }
        var selfInfo = NodeInfo.nodeInfo(nodeId, nodeAddress("0.0.0.0", clusterPort).unwrap(), NodeRole.PASSIVE);
        var config = PassiveLBConfig.passiveLBConfig(httpPort, selfInfo, peers, peers.size());
        logStartupInfo(httpPort, clusterPort, nodeId, peers);
        var lb = AetherPassiveLB.aetherPassiveLB(config);
        registerShutdownHook(lb);
        startAndWait(lb);
    }

    private NodeId parseNodeId() {
        return findArg("--node-id=").orElse(findEnv("LB_NODE_ID"))
                      .flatMap(id -> NodeId.nodeId(id).option())
                      .or(() -> NodeId.nodeId(DEFAULT_NODE_ID).unwrap());
    }

    private List<NodeInfo> parsePeers() {
        var peersStr = findArg("--peers=").orElse(findEnv("PEERS"))
                              .or("");
        if ( peersStr.isBlank()) {
        return List.of();}
        return Arrays.stream(peersStr.split(",")).map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .map(this::parsePeerAddress)
                            .flatMap(Option::stream)
                            .toList();
    }

    private Option<NodeInfo> parsePeerAddress(String hostPort) {
        var parts = hostPort.split(":");
        if ( parts.length != 2) {
            log.warn("Invalid peer address (expected host:port): {}", hostPort);
            return Option.none();
        }
        return nodeAddress(parts[0],
                           Integer.parseInt(parts[1])).map(addr -> NodeInfo.nodeInfo(NodeId.randomNodeId(),
                                                                                     addr))
                          .option();
    }

    private Option<Integer> parseIntOption(String argPrefix, String envName) {
        return findArg(argPrefix).map(Integer::parseInt)
                      .orElse(findEnv(envName).map(Integer::parseInt));
    }

    private Option<String> findArg(String prefix) {
        return Option.from(Arrays.stream(args).filter(a -> a.startsWith(prefix))
                                        .map(a -> a.substring(prefix.length()))
                                        .findFirst());
    }

    private static Option<String> findEnv(String name) {
        return Option.option(System.getenv(name)).filter(s -> !s.isBlank());
    }

    private static void logStartupInfo(int httpPort, int clusterPort, NodeId nodeId, List<NodeInfo> peers) {
        log.info("Starting Aether Passive LB");
        log.info("  HTTP port:    {}", httpPort);
        log.info("  Cluster port: {}", clusterPort);
        log.info("  Node ID:      {}", nodeId);
        log.info("  Peers:        {}", peers.size());
    }

    private void registerShutdownHook(AetherPassiveLB lb) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdownLB(lb)));
    }

    private static void shutdownLB(AetherPassiveLB lb) {
        log.info("Shutdown requested, stopping LB...");
        lb.stop().await();
        log.info("LB stopped");
    }

    private void startAndWait(AetherPassiveLB lb) {
        lb.start().onSuccess(_ -> log.info("Aether Passive LB is running on port {}",
                                           lb.port()))
                .onFailure(cause -> exitWithError(cause.message()))
                .await();
        waitForInterrupt();
    }

    private static void exitWithError(String message) {
        log.error("Failed to start LB: {}", message);
        System.exit(1);
    }

    private static void waitForInterrupt() {
        try {
            Thread.currentThread().join();
        }

























        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
