package org.pragmatica.cluster.net;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.net.ip.IpAddressBook;
import org.pragmatica.lang.Option;

import java.net.InetSocketAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IpAddressBookTest {
    private AddressBook addressBook = new IpAddressBook(List.of(
            NodeInfo.create(NodeId.createRandom(), NodeAddress.create("host1", 8090)),
            NodeInfo.create(NodeId.createRandom(), NodeAddress.create("host2", 8090)),
            NodeInfo.create(NodeId.createRandom(), NodeAddress.create("host3", 8090)),
            NodeInfo.create(NodeId.createRandom(), NodeAddress.create("host4", 8090)),
            NodeInfo.create(NodeId.createRandom(), NodeAddress.create("host5", 8090)),
            NodeInfo.create(NodeId.createRandom(), NodeAddress.create("host6", 8090)),
            NodeInfo.create(NodeId.createRandom(), NodeAddress.create("host7", 8090))));
    private NodeId nodeId1;
    private NodeId nodeId2;
    private NodeId nodeId3;
    private NodeInfo nodeInfo1;
    private NodeInfo nodeInfo2;
    private InetSocketAddress socketAddress1;
    private InetSocketAddress socketAddress2;
    private InetSocketAddress socketAddress3;

    @BeforeEach
    void setUp() {
        nodeId1 = NodeId.createRandom();
        nodeId2 = NodeId.createRandom();
        nodeId3 = NodeId.createRandom();

        socketAddress1 = new InetSocketAddress("localhost", 8080);
        socketAddress2 = new InetSocketAddress("127.0.0.1", 8082);
        socketAddress2 = new InetSocketAddress("127.0.0.1", 8083);

        nodeInfo1 = NodeInfo.create(nodeId1, NodeAddress.create(socketAddress1));
        nodeInfo2 = NodeInfo.create(nodeId2, NodeAddress.create(socketAddress2));


        addressBook = new IpAddressBook(List.of(nodeInfo1, nodeInfo2));
    }

    @Test
    void getReturnsNoneForNonExistentNode() {
        assertEquals(Option.none(), addressBook.get(nodeId3));
    }

    @Test
    void getReturnsCorrectValueForExistingNodeId() {
        assertEquals(Option.some(nodeInfo1), addressBook.get(nodeId1));
        assertEquals(Option.some(nodeInfo2), addressBook.get(nodeId2));
    }

    @Test
    void reverseLookupReturnsNoneForNonExistentAddress() {
        assertEquals(Option.none(), addressBook.reverseLookup(socketAddress3));
    }

    @Test
    void reverseLookupReturnsExistingAddress() {
        assertEquals(Option.some(nodeId1), addressBook.reverseLookup(socketAddress1));
        assertEquals(Option.some(nodeId2), addressBook.reverseLookup(socketAddress2));
    }

    @Test
    void clusterSizeReturnsCorrectSize() {
        assertEquals(2, addressBook.clusterSize());
    }
}