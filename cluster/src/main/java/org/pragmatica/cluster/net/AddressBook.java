package org.pragmatica.cluster.net;

import org.pragmatica.lang.Option;

import java.net.SocketAddress;

public interface AddressBook {
    Option<NodeInfo> get(NodeId id);

    int clusterSize();

    default int consensusSize() {
        return clusterSize()/2 + 1;
    }

    Option<NodeId> reverseLookup(SocketAddress socketAddress);
}
