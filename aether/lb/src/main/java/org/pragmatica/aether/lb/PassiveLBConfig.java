package org.pragmatica.aether.lb;

import org.pragmatica.consensus.net.NodeInfo;

import java.util.List;

/// Configuration for the passive load balancer node.
///
/// @param httpPort          Port for accepting external HTTP client connections
/// @param selfInfo          NodeInfo for this passive LB node (with NodeRole.PASSIVE)
/// @param clusterNodes      Active cluster nodes to connect to
/// @param clusterSize       Active cluster size (for quorum calculations - excludes passive nodes)
/// @param forwardTimeoutMs  Timeout for forwarded requests in milliseconds
public record PassiveLBConfig(int httpPort,
                              NodeInfo selfInfo,
                              List<NodeInfo> clusterNodes,
                              int clusterSize,
                              long forwardTimeoutMs) {
    public static final int DEFAULT_HTTP_PORT = 8080;
    public static final long DEFAULT_FORWARD_TIMEOUT_MS = 5000;

    /// Create config with defaults for timeout.
    public static PassiveLBConfig passiveLBConfig(int httpPort,
                                                  NodeInfo selfInfo,
                                                  List<NodeInfo> clusterNodes,
                                                  int clusterSize) {
        return new PassiveLBConfig(httpPort, selfInfo, clusterNodes, clusterSize, DEFAULT_FORWARD_TIMEOUT_MS);
    }
}
