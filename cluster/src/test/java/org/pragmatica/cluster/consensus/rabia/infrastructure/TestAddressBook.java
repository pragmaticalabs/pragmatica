package org.pragmatica.cluster.consensus.rabia.infrastructure;

import org.pragmatica.cluster.net.AddressBook;
import org.pragmatica.cluster.net.NodeId;
import org.pragmatica.cluster.net.NodeInfo;
import org.pragmatica.lang.Option;

import java.net.SocketAddress;

public record TestAddressBook(int clusterSize) implements AddressBook {

    @Override
    public Option<NodeInfo> get(NodeId id) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public Option<NodeId> reverseLookup(SocketAddress socketAddress) {
        throw new UnsupportedOperationException("Not implemented");
    }
}
