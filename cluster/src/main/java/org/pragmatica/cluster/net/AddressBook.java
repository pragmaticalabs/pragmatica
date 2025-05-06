package org.pragmatica.cluster.net;

import org.pragmatica.lang.Option;

import java.net.SocketAddress;

/// Representation of our knowledge about the cluster structure: known nodes and cluster/quorum size.
/// Note that this is not a representation of the actual cluster topology.
public interface AddressBook {
    /// Retrieve information about node
    Option<NodeInfo> get(NodeId id);

    /// Configured cluster size
    int clusterSize();

    /// The quorum size (majority) for the cluster.
    default int quorumSize() {
        return clusterSize()/2 + 1;
    }

    /// Gets the f+1 size for the cluster (where f is the maximum number of failures).
    default int fPlusOne() {
        return clusterSize() - quorumSize() + 1;
    }

    /// Mapping from IP address (host and port) to node ID
    Option<NodeId> reverseLookup(SocketAddress socketAddress);
}
