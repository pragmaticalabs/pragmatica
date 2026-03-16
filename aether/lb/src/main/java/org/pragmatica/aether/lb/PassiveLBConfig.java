package org.pragmatica.aether.lb;

import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.io.TimeSpan;

import java.util.List;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Configuration for the passive load balancer node.
///
/// @param httpPort          Port for accepting external HTTP client connections
/// @param selfInfo          NodeInfo for this passive LB node (with NodeRole.PASSIVE)
/// @param clusterNodes      Active cluster nodes to connect to
/// @param clusterSize       Active cluster size (for quorum calculations - excludes passive nodes)
/// @param forwardTimeout    Timeout for forwarded requests
public record PassiveLBConfig(int httpPort,
                              NodeInfo selfInfo,
                              List<NodeInfo> clusterNodes,
                              int clusterSize,
                              TimeSpan forwardTimeout) {
    public static final int DEFAULT_HTTP_PORT = 8080;
    public static final TimeSpan DEFAULT_FORWARD_TIMEOUT = timeSpan(5).seconds();

    /// Create config with defaults for timeout.
    public static PassiveLBConfig passiveLBConfig(int httpPort,
                                                  NodeInfo selfInfo,
                                                  List<NodeInfo> clusterNodes,
                                                  int clusterSize) {
        return new PassiveLBConfig(httpPort, selfInfo, clusterNodes, clusterSize, DEFAULT_FORWARD_TIMEOUT);
    }
}
