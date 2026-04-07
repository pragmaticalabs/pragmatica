package org.pragmatica.aether.lb;

import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import java.util.List;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Configuration for the passive load balancer node.
///
/// @param httpPort          Port for accepting external HTTP client connections (app traffic)
/// @param selfInfo          NodeInfo for this passive LB node (with NodeRole.PASSIVE)
/// @param clusterNodes      Active cluster nodes to connect to
/// @param clusterSize       Active cluster size (for quorum calculations - excludes passive nodes)
/// @param forwardTimeout    Timeout for forwarded requests
/// @param managementPort    Optional port for management API forwarding. When empty, management API
///                          forwarding is disabled and no management HTTP server is started.
///                          SECURITY: This MUST be on a separate port from `httpPort` and bound
///                          to an internal network only — management API must never be exposed
///                          to the public Internet. Absence of configuration is treated as an
///                          explicit opt-out for safety.
public record PassiveLBConfig(int httpPort,
                              NodeInfo selfInfo,
                              List<NodeInfo> clusterNodes,
                              int clusterSize,
                              TimeSpan forwardTimeout,
                              Option<Integer> managementPort) {
    public static final int DEFAULT_HTTP_PORT = 8080;

    public static final TimeSpan DEFAULT_FORWARD_TIMEOUT = timeSpan(5).seconds();

    public static PassiveLBConfig passiveLBConfig(int httpPort,
                                                  NodeInfo selfInfo,
                                                  List<NodeInfo> clusterNodes,
                                                  int clusterSize) {
        return new PassiveLBConfig(httpPort,
                                   selfInfo,
                                   clusterNodes,
                                   clusterSize,
                                   DEFAULT_FORWARD_TIMEOUT,
                                   Option.empty());
    }

    public static PassiveLBConfig passiveLBConfig(int httpPort,
                                                  NodeInfo selfInfo,
                                                  List<NodeInfo> clusterNodes,
                                                  int clusterSize,
                                                  Option<Integer> managementPort) {
        return new PassiveLBConfig(httpPort,
                                   selfInfo,
                                   clusterNodes,
                                   clusterSize,
                                   DEFAULT_FORWARD_TIMEOUT,
                                   managementPort);
    }
}
