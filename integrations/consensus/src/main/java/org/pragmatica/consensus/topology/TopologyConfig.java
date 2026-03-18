package org.pragmatica.consensus.topology;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.TlsConfig;

import java.util.List;

/// Configuration for cluster topology management.
/// Note: The constructor validation throw is intentional - cluster size < 1
/// represents a programming error, not a business validation failure.
///
/// @param self                   This node's ID
/// @param clusterSize            Fixed cluster size for quorum calculations (prevents split-brain resurrection)
/// @param reconciliationInterval How often to reconcile cluster state
/// @param pingInterval           How often to ping other nodes
/// @param helloTimeout           Timeout for Hello handshake on new connections
/// @param coreNodes              Initial cluster members
/// @param tls                    TLS configuration for cluster communication (empty for plain TCP)
/// @param backoff                Backoff configuration for connection retries and node disabling
/// @param coreMax                Maximum number of core consensus nodes (0 = unlimited)
/// @param coreMin                Minimum number of core consensus nodes (defaults to clusterSize)
public record TopologyConfig(NodeId self,
                             int clusterSize,
                             TimeSpan reconciliationInterval,
                             TimeSpan pingInterval,
                             TimeSpan helloTimeout,
                             List<NodeInfo> coreNodes,
                             Option<TlsConfig> tls,
                             BackoffConfig backoff,
                             int coreMax,
                             int coreMin) {
    public TopologyConfig {
        if (clusterSize < 1) {
            throw new IllegalArgumentException("Cluster size must be at least 1");
        }
        coreNodes = List.copyOf(coreNodes);
    }

    public static final TimeSpan DEFAULT_HELLO_TIMEOUT = TimeSpan.timeSpan(5)
                                                                .seconds();

    /// Create TopologyConfig without TLS and default hello timeout.
    public TopologyConfig(NodeId self,
                          int clusterSize,
                          TimeSpan reconciliationInterval,
                          TimeSpan pingInterval,
                          List<NodeInfo> coreNodes) {
        this(self,
             clusterSize,
             reconciliationInterval,
             pingInterval,
             DEFAULT_HELLO_TIMEOUT,
             coreNodes,
             Option.empty(),
             BackoffConfig.DEFAULT,
             0,
             clusterSize);
    }

    /// Create TopologyConfig with all parameters except backoff and core limits (uses defaults).
    public TopologyConfig(NodeId self,
                          int clusterSize,
                          TimeSpan reconciliationInterval,
                          TimeSpan pingInterval,
                          TimeSpan helloTimeout,
                          List<NodeInfo> coreNodes,
                          Option<TlsConfig> tls) {
        this(self,
             clusterSize,
             reconciliationInterval,
             pingInterval,
             helloTimeout,
             coreNodes,
             tls,
             BackoffConfig.DEFAULT,
             0,
             clusterSize);
    }

    /// Create TopologyConfig with all parameters except core limits (uses defaults).
    public TopologyConfig(NodeId self,
                          int clusterSize,
                          TimeSpan reconciliationInterval,
                          TimeSpan pingInterval,
                          TimeSpan helloTimeout,
                          List<NodeInfo> coreNodes,
                          Option<TlsConfig> tls,
                          BackoffConfig backoff) {
        this(self,
             clusterSize,
             reconciliationInterval,
             pingInterval,
             helloTimeout,
             coreNodes,
             tls,
             backoff,
             0,
             clusterSize);
    }

    /// Check whether this node is a seed node (present in coreNodes).
    public boolean isSeedNode() {
        return coreNodes.stream()
                        .anyMatch(node -> node.id().equals(self));
    }
}
