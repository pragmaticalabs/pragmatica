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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.fail;

/// #1578 test helper. A UDP relay on its own port in front of `targetPort`: drops every datagram while closed; once open, relays each
/// client (by source address) through its own upstream socket and relays the replies back.
final class UdpGate implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(UdpGate.class);

    private final DatagramSocket front;
    private final InetSocketAddress target;
    private final Map<SocketAddress, DatagramSocket> upstreams = new ConcurrentHashMap<>();
    private final List<Thread> threads = new CopyOnWriteArrayList<>();
    private volatile boolean open;
    private volatile boolean closed;
    /// #1727: probability of dropping a relayed datagram, in either direction (0 = none).
    private volatile double dropRate;
    private final java.util.Random drops;
    /// #1727: what the relay did, for a failure's diagnosis. A relay thread used to END SILENTLY on any IOException, which
    /// stalls one direction for good while the other keeps flowing (v1677 attributed two loss-test reds under d1 load to
    /// it). Now a direction survives a transient error ([#survives]) and stops only on teardown, recording why.
    private final AtomicLong toTarget = new AtomicLong();
    private final AtomicLong toClient = new AtomicLong();
    private final AtomicLong discarded = new AtomicLong();
    private final AtomicLong transientErrors = new AtomicLong();
    private final List<String> recentErrors = new CopyOnWriteArrayList<>();
    private final List<String> exits = new CopyOnWriteArrayList<>();
    private final AtomicInteger failNextSends = new AtomicInteger();

    private UdpGate(DatagramSocket front, InetSocketAddress target, long seed) {
        this.front = front;
        this.target = target;
        this.drops = new java.util.Random(seed);
    }

    static UdpGate udpGate(int targetPort) {
        try {
            var gate = new UdpGate(new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0)),
                                   new InetSocketAddress(InetAddress.getLoopbackAddress(), targetPort),
                                   0L);

            gate.spawn(gate::relayFromClients);
            return gate;
        } catch (IOException e) {
            return fail("gate: " + e.getMessage());
        }
    }

    /// #1727: an OPEN relay that drops each datagram, in either direction, with probability `dropRate` (seeded, so a run
    /// is repeatable up to thread scheduling) — QUIC loss and retransmission on demand.
    static UdpGate lossyRelay(int targetPort, double dropRate, long seed) {
        try {
            var gate = new UdpGate(new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0)),
                                   new InetSocketAddress(InetAddress.getLoopbackAddress(), targetPort),
                                   seed);

            gate.dropRate = dropRate;
            gate.open = true;
            gate.spawn(gate::relayFromClients);
            return gate;
        } catch (IOException e) {
            return fail("lossy relay: " + e.getMessage());
        }
    }

    private boolean drop() {
        if (dropRate <= 0) {
            return false;
        }
        synchronized (drops) {
            return drops.nextDouble() < dropRate;
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
                if (open && !drop()) {
                    send(upstream(packet.getSocketAddress()), new DatagramPacket(packet.getData(), packet.getLength(), target));
                    toTarget.incrementAndGet();
                } else {
                    discarded.incrementAndGet();
                }
            } catch (IOException e) {
                if (!survives("to-target", front, e)) {
                    return;
                }
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
                if (!drop()) {
                    send(front, new DatagramPacket(packet.getData(), packet.getLength(), client));
                    toClient.incrementAndGet();
                } else {
                    discarded.incrementAndGet();
                }
            } catch (IOException e) {
                if (!survives("to-client", socket, e)) {
                    return;
                }
            }
        }
    }

    /// A relay direction survives a transient IOException (a send or receive failing under load, e.g. ENOBUFS): the datagram
    /// is lost, as on a real network, and is counted and logged, and the loop goes on. It stops only on teardown (the gate
    /// closed, or the socket it reads closed), and records the cause.
    private boolean survives(String direction, DatagramSocket receiving, IOException cause) {
        if (closed || receiving.isClosed()) {
            exits.add(direction + ": " + (closed ? "gate closed" : "socket closed") + " (" + cause + ")");
            return false;
        }
        transientErrors.incrementAndGet();
        if (recentErrors.size() < 10) {
            recentErrors.add(direction + ": " + cause);
        }
        LOG.warn("UdpGate {} survived a transient error: {}", direction, cause.toString());
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        return true;
    }

    /// Test seam: the next `count` relayed sends, in either direction, fail with an IOException as ENOBUFS would.
    void failNextSends(int count) {
        failNextSends.set(count);
    }

    private void send(DatagramSocket socket, DatagramPacket packet) throws IOException {
        if (failNextSends.getAndUpdate(remaining -> Math.max(0, remaining - 1)) > 0) {
            throw new IOException("injected send failure (UdpGate#failNextSends)");
        }
        socket.send(packet);
    }

    /// The relay's own UDP ports (front and every upstream), so a diagnosis can read their kernel queues too.
    List<Integer> ports() {
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(front), upstreams.values().stream())
                                      .map(DatagramSocket::getLocalPort)
                                      .toList();
    }

    String describe() {
        return "relay(since relay start){toTarget=" + toTarget.get() + " toClient=" + toClient.get() + " discarded=" + discarded.get()
               + " transientErrors=" + transientErrors.get() + " recentErrors=" + recentErrors + " exits=" + exits + "}";
    }

    @Override
    public void close() {
        closed = true;
        front.close();
        upstreams.values().forEach(DatagramSocket::close);
    }
}
