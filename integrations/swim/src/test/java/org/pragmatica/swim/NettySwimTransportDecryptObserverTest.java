// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// See LICENSE in the repository root for full terms.
package org.pragmatica.swim;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/// #1200 — the real transport reports, per sender, the datagrams it cannot open for want of the key and the ones it can. Real UDP, real AES-GCM
/// encryptors on both sides; nothing is hand-fed to the observer.
class NettySwimTransportDecryptObserverTest {
    private static final byte[] HELD_KEY = filled((byte) 0x11);
    private static final byte[] ROTATED_KEY = filled((byte) 0x22);
    private static final byte[] PAYLOAD = "swim-probe".getBytes();

    private final List<String> events = new CopyOnWriteArrayList<>();

    @Test
    void unknownKeyDatagram_isReportedWithItsSender_andAnOpenableOneAsDecrypted() throws Exception {
        var port = freeUdpPort();
        var transport = NettySwimTransport.nettySwimTransport(mock(Serializer.class),
                                                              mock(Deserializer.class),
                                                              AesGcmGossipEncryptor.aesGcmGossipEncryptor(HELD_KEY, 1).unwrap())
                                          .unwrap();

        transport.decryptObserver(recording());

        try (var sender = new DatagramSocket()) {
            assertThat(transport.start(port, (_, _) -> {}).await().isSuccess()).isTrue();

            send(sender, port, AesGcmGossipEncryptor.aesGcmGossipEncryptor(ROTATED_KEY, 2).unwrap().encrypt(PAYLOAD).unwrap());
            awaitEvents(1);
            send(sender, port, AesGcmGossipEncryptor.aesGcmGossipEncryptor(HELD_KEY, 1).unwrap().encrypt(PAYLOAD).unwrap());
            awaitEvents(2);

            assertThat(events).hasSize(2);
            assertThat(events.get(0)).as("a datagram under an unheld key id names its sender and the key id").startsWith("unknown:127.0.0.1:").endsWith(":2");
            assertThat(events.get(1)).as("a datagram under the held key is reported as decrypted").startsWith("decrypted:127.0.0.1:");
        } finally {
            transport.stop().await();
        }
    }

    /// Junk that is not a datagram of this cluster at all (bad authentication under a held key id) is neither: it is not a key divergence.
    @Test
    void authenticationFailureUnderAHeldKeyId_isNotReportedAsUnknownKey() throws Exception {
        var port = freeUdpPort();
        var transport = NettySwimTransport.nettySwimTransport(mock(Serializer.class),
                                                              mock(Deserializer.class),
                                                              AesGcmGossipEncryptor.aesGcmGossipEncryptor(HELD_KEY, 1).unwrap())
                                          .unwrap();

        transport.decryptObserver(recording());

        try (var sender = new DatagramSocket()) {
            assertThat(transport.start(port, (_, _) -> {}).await().isSuccess()).isTrue();

            var tampered = AesGcmGossipEncryptor.aesGcmGossipEncryptor(HELD_KEY, 1).unwrap().encrypt(PAYLOAD).unwrap();

            tampered[tampered.length - 1] ^= 0x55;
            send(sender, port, tampered);
            // control: a following good datagram IS reported, so the transport was alive and the empty prefix is a real absence
            send(sender, port, AesGcmGossipEncryptor.aesGcmGossipEncryptor(HELD_KEY, 1).unwrap().encrypt(PAYLOAD).unwrap());
            awaitEvents(1);

            assertThat(events).singleElement().asString().startsWith("decrypted:");
        } finally {
            transport.stop().await();
        }
    }

    private GossipDecryptObserver recording() {
        return new GossipDecryptObserver() {
            @Override
            public void unknownKey(InetSocketAddress sender, int keyId) {
                events.add("unknown:" + sender.getAddress().getHostAddress() + ":" + sender.getPort() + ":" + keyId);
            }

            @Override
            public void decrypted(InetSocketAddress sender) {
                events.add("decrypted:" + sender.getAddress().getHostAddress() + ":" + sender.getPort());
            }
        };
    }

    private void awaitEvents(int expected) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (events.size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    private static void send(DatagramSocket sender, int port, byte[] datagram) throws Exception {
        sender.send(new DatagramPacket(datagram, datagram.length, InetAddress.getLoopbackAddress(), port));
    }

    private static int freeUdpPort() throws Exception {
        try (var socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static byte[] filled(byte value) {
        var key = new byte[32];

        java.util.Arrays.fill(key, value);

        return key;
    }
}
