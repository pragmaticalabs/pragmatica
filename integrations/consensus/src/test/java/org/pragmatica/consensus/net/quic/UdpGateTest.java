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
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #1727 — a relay direction survives a transient send failure. [UdpGate]'s relay threads used to return silently on any
/// IOException, which stalled that direction for good while the other kept flowing; v1677 attributed two
/// `QuicLaneFinishUnderLossTest` reds under d1 load to it. Each test injects one failed send ([UdpGate#failNextSends]), which
/// loses that datagram as ENOBUFS would, and requires the NEXT datagram in the same direction to arrive.
/// Mutation it must catch: the silent `return` restored in the catch — the next datagram never arrives.
@Timeout(30)
class UdpGateTest {
    private static final int RECEIVE_TIMEOUT_MS = 3_000;

    private DatagramSocket target;
    private DatagramSocket client;
    private UdpGate gate;

    @BeforeEach
    void setUp() throws IOException {
        target = socket();
        client = socket();
        gate = UdpGate.lossyRelay(target.getLocalPort(), 0.0, 1L);
    }

    @AfterEach
    void tearDown() {
        gate.close();
        target.close();
        client.close();
    }

    @Test
    void relayToTarget_survivesAFailedSend_theNextDatagramArrives() throws IOException {
        gate.failNextSends(1);
        send(client, "lost", gatePort());
        send(client, "after", gatePort());

        assertThat(receive(target).text()).as("the to-target direction keeps relaying after a failed send").isEqualTo("after");
        assertThat(gate.describe()).contains("transientErrors=1").contains("exits=[]");
    }

    @Test
    void relayToClient_survivesAFailedSend_theNextDatagramArrives() throws IOException {
        send(client, "hello", gatePort());
        var upstream = receive(target).from();

        gate.failNextSends(1);
        send(target, "lost", upstream);
        send(target, "after", upstream);

        assertThat(receive(client).text()).as("the to-client direction keeps relaying after a failed send").isEqualTo("after");
        assertThat(gate.describe()).contains("transientErrors=1").contains("exits=[]");
    }

    @Test
    void close_stopsBothDirections_andRecordsWhy() throws IOException {
        send(client, "hello", gatePort());
        receive(target);

        gate.close();
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while (System.nanoTime() < deadline && !gate.describe().contains("to-client: gate closed")) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }
        assertThat(gate.describe()).contains("to-target: gate closed").contains("to-client: gate closed");
    }

    private record Received(String text, SocketAddress from) {}

    private SocketAddress gatePort() {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), gate.port());
    }

    private static DatagramSocket socket() throws IOException {
        var socket = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));

        socket.setSoTimeout(RECEIVE_TIMEOUT_MS);
        return socket;
    }

    private static void send(DatagramSocket from, String text, SocketAddress to) throws IOException {
        var bytes = text.getBytes(StandardCharsets.UTF_8);

        from.send(new DatagramPacket(bytes, bytes.length, to));
    }

    private static Received receive(DatagramSocket socket) {
        var packet = new DatagramPacket(new byte[1024], 1024);

        try {
            socket.receive(packet);
        } catch (IOException e) {
            return fail("nothing arrived within " + RECEIVE_TIMEOUT_MS + " ms: " + e);
        }
        return new Received(new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8), packet.getSocketAddress());
    }
}
