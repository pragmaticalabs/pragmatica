package org.pragmatica.cluster.net.ip;

import org.pragmatica.cluster.net.AddressBook;
import org.pragmatica.cluster.net.NodeAddress;
import org.pragmatica.cluster.net.NodeId;
import org.pragmatica.cluster.net.NodeInfo;
import org.pragmatica.lang.Option;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class IpAddressBook implements AddressBook {
    private final Map<NodeId, NodeInfo> nodesById = new ConcurrentHashMap<>();
    private final Map<NodeAddress, NodeId> nodeIdsByAddress = new ConcurrentHashMap<>();

    public IpAddressBook(Collection<NodeInfo> nodes) {
        nodes.forEach(this::addNode);
    }

    private void addNode(NodeInfo nodeInfo) {
        nodesById.put(nodeInfo.id(), nodeInfo);
        nodeIdsByAddress.put(nodeInfo.address(), nodeInfo.id());
    }

    private void removeNode(NodeId nodeId) {
        Option.option(nodesById.remove(nodeId))
              .onPresent(nodeInfo -> nodeIdsByAddress.remove(nodeInfo.address()));
    }

    @Override
    public Option<NodeInfo> get(NodeId id) {
        return Option.option(nodesById.get(id));
    }

    @Override
    public int clusterSize() {
        return nodesById.size();
    }

    @Override
    public Option<NodeId> reverseLookup(SocketAddress socketAddress) {
        return (socketAddress instanceof InetSocketAddress inet)
                ? Option.option(nodeIdsByAddress.get(NodeAddress.create(inet)))
                : Option.empty();
    }
} 