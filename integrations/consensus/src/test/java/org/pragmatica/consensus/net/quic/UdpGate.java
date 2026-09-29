/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.pragmatica.consensus.net.quic;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.fail;

/// #1578 test helper. A UDP relay on its own port in front of `targetPort`: drops every datagram while closed; once open, relays each
/// client (by source address) through its own upstream socket and relays the replies back.
final class UdpGate implements AutoCloseable {
    private final DatagramSocket front;
    private final InetSocketAddress target;
    private final Map<SocketAddress, DatagramSocket> upstreams = new ConcurrentHashMap<>();
    private final List<Thread> threads = new CopyOnWriteArrayList<>();
    private volatile boolean open;
    private volatile boolean closed;

    private UdpGate(DatagramSocket front, InetSocketAddress target) {
        this.front = front;
        this.target = target;
    }

    static UdpGate udpGate(int targetPort) {
        try {
            var gate = new UdpGate(new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0)),
                                   new InetSocketAddress(InetAddress.getLoopbackAddress(), targetPort));

            gate.spawn(gate::relayFromClients);
            return gate;
        } catch (IOException e) {
            return fail("gate: " + e.getMessage());
        }
    }

    int port() {
        return front.getLocalPort();
    }

    void open() {
        open = true;
    }

    private void spawn(Runnable loop) {
        var thread = Thread.ofPlatform().daemon().start(loop);

        threads.add(thread);
    }

    private void relayFromClients() {
        var buffer = new byte[65_535];

        while (!closed) {
            try {
                var packet = new DatagramPacket(buffer, buffer.length);

                front.receive(packet);
                if (open) {
                    upstream(packet.getSocketAddress()).send(new DatagramPacket(packet.getData(), packet.getLength(), target));
                }
            } catch (IOException e) {
                return;
            }
        }
    }

    private DatagramSocket upstream(SocketAddress client) {
        return upstreams.computeIfAbsent(client, this::newUpstream);
    }

    private DatagramSocket newUpstream(SocketAddress client) {
        try {
            var socket = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));

            spawn(() -> relayToClient(socket, client));
            return socket;
        } catch (IOException e) {
            return fail("gate upstream: " + e.getMessage());
        }
    }

    private void relayToClient(DatagramSocket socket, SocketAddress client) {
        var buffer = new byte[65_535];

        while (!closed) {
            try {
                var packet = new DatagramPacket(buffer, buffer.length);

                socket.receive(packet);
                front.send(new DatagramPacket(packet.getData(), packet.getLength(), client));
            } catch (IOException e) {
                return;
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        front.close();
        upstreams.values().forEach(DatagramSocket::close);
    }
}
