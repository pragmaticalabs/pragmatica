package org.pragmatica.cluster.net;

import java.net.InetSocketAddress;

// TODO: generalize it???
/// Network node address. At present TCP/IP address structure is assumed
public interface NodeAddress {
    String host();

    int port();

    static NodeAddress create(String host, int port) {
        record nodeAddress(String host, int port) implements NodeAddress {}

        return new nodeAddress(host, port);
    }

    static NodeAddress create(InetSocketAddress socketAddress) {
        return create(socketAddress.getAddress().toString(), socketAddress.getPort());
    }
}
