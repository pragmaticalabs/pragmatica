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

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.quic.QuicChannel;

/// #1727 — the diagnosis every failure in the lane-delivery tests carries (`QuicLaneOwnershipTest`,
/// `QuicLaneFinishUnderLossTest`), so a red on ANY wait, not only on the delivery check, is attributable without a rerun.
/// [#stall] snapshots both ends' QUIC counters twice, [#RECHECK] apart: a recvB that stays flat while the other end's sentB
/// or lostB keeps growing is a stall, a moving one is a slow path. It also names every socket bound to the acceptor's port
/// ([#portSharers]), because #1719's port theft looked exactly like a stall.
final class LaneDiagnosis {
    static final TimeSpan RECHECK = TimeSpan.timeSpan(5).seconds();

    private static final List<Path> TABLES = List.of(Path.of("/proc/net/udp"), Path.of("/proc/net/udp6"));
    private static final Path SNMP = Path.of("/proc/net/snmp");
    private static final List<String> SNMP_FIELDS = List.of("RcvbufErrors", "SndbufErrors", "InErrors");
    private static final String NON_LINUX = "n/a (non-Linux)";

    private LaneDiagnosis() {}

    static String stall(Option<QuicPeerConnection> dialer, Option<QuicPeerConnection> acceptor, Option<Integer> acceptorPort) {
        var atTimeout = stats(dialer, acceptor);

        LockSupport.parkNanos(RECHECK.nanos());
        return "stats@timeout=" + atTimeout + " stats@+" + RECHECK.millis() / 1000 + "s=" + stats(dialer, acceptor)
               + " acceptorPortSharers=" + acceptorPort.map(LaneDiagnosis::portSharers).or("n/a");
    }

    static String stats(Option<QuicPeerConnection> dialer, Option<QuicPeerConnection> acceptor) {
        return "dialer{" + dialer.map(side -> stats(side.connection())).or("not connected") + "} acceptor{"
               + acceptor.map(side -> stats(side.connection())).or("not connected") + "}";
    }

    static String stats(QuicChannel channel) {
        try {
            var st = channel.collectStats().get(5, TimeUnit.SECONDS);

            return "active=" + channel.isActive() + " sentB=" + st.sentBytes() + " recvB=" + st.recvBytes() + " lostB="
                   + st.lostBytes() + " retransB=" + st.streamRetransBytes();
        } catch (Exception e) {
            return "active=" + channel.isActive() + " stats-unavailable:" + e;
        }
    }

    static long receivedBytes(QuicChannel channel) {
        try {
            return channel.collectStats().get(5, TimeUnit.SECONDS).recvBytes();
        } catch (Exception e) {
            return -1;
        }
    }

    static long lostBytes(QuicChannel channel) {
        try {
            return channel.collectStats().get(5, TimeUnit.SECONDS).lostBytes();
        } catch (Exception e) {
            return -1;
        }
    }

    /// Where acknowledged-but-unread writes stopped: CONNECTION-CLOSED (quiche discarded what it held), NOTHING-UNREAD (there
    /// are none, so a timeout lies elsewhere, e.g. a FIN that never arrived), RECEIVER-SIDE (the
    /// acceptor's QUIC stack received at least the missing bytes beyond what the app read), or SENDER-SIDE (it received
    /// less, so they stopped at or before the dialer's QUIC stack). Received bytes include retransmitted duplicates and the
    /// other lanes, so the verdict leans RECEIVER-SIDE when close; callers print the raw numbers too.
    static String verdict(boolean connected, long receivedBeyondDelivered, long missingBytes) {
        if (!connected) {
            return "CONNECTION-CLOSED";
        }
        if (missingBytes == 0) {
            return "NOTHING-UNREAD (every acknowledged write so far was read)";
        }
        return (receivedBeyondDelivered >= missingBytes ? "RECEIVER-SIDE" : "SENDER-SIDE") + " (acceptor QUIC received "
               + receivedBeyondDelivered + " B beyond what the app read " + (receivedBeyondDelivered >= missingBytes ? ">=" : "<")
               + " " + missingBytes + " B undelivered)";
    }

    /// #1727 — socket-level discriminators BELOW quiche (v1733-report.md: one direction stalled for 15 s while both QUIC
    /// channels stayed active and the acceptor's quiche processed nothing). [#attach] snapshots after the connection is up;
    /// [#describe] reports the change up to the failure:
    /// - datagrams counted by a test-only handler at the HEAD of each end's datagram pipeline, so inbound BEFORE the QUIC
    ///   codec and outbound AFTER it (with write failures). The acceptor's inbound count separates "the socket never
    ///   delivered it" from "the server codec dropped it before quiche";
    /// - per socket, Linux `/proc/net/udp` rx_queue and drops: a growing rx_queue means the socket is not being read,
    ///   growing drops mean its receive buffer overflowed, both flat mean the datagrams never reached it;
    /// - Linux `/proc/net/snmp` Udp RcvbufErrors, SndbufErrors and InErrors (host-wide: other processes add to them);
    /// - each end's datagram channel isActive and isWritable (quiche counts sentB when it emits a packet, not when the
    ///   socket sends it, so a dead or unwritable datagram channel under an "active" QuicChannel looks like a stall).
    static final class Sockets {
        private final Channel dialer;
        private final Channel acceptor;
        private final DatagramCounter dialerDatagrams = new DatagramCounter();
        private final DatagramCounter acceptorDatagrams = new DatagramCounter();
        private final Map<String, long[]> udpAtStart;
        private final Map<String, Long> snmpAtStart;

        private Sockets(Channel dialer, Channel acceptor) {
            this.dialer = dialer;
            this.acceptor = acceptor;
            this.udpAtStart = udpQueues(List.of(localPort(dialer), localPort(acceptor)));
            this.snmpAtStart = snmp();
        }

        /// The datagram channels are the QuicChannels' parents: the dialer's per-peer socket and the server's bound one.
        static Sockets attach(QuicChannel dialerConnection, QuicChannel acceptorConnection) {
            var sockets = new Sockets(dialerConnection.parent(), acceptorConnection.parent());

            sockets.dialer.pipeline().addFirst("lane-diagnosis-datagrams", sockets.dialerDatagrams);
            sockets.acceptor.pipeline().addFirst("lane-diagnosis-datagrams", sockets.acceptorDatagrams);
            return sockets;
        }

        String describe() {
            var udpNow = udpQueues(List.of(localPort(dialer), localPort(acceptor)));

            return "sockets(since attach){dialer{" + channel(dialer) + " " + dialerDatagrams + " udp=" + udp(localPort(dialer), udpNow)
                   + "} acceptor{" + channel(acceptor) + " " + acceptorDatagrams + " udp=" + udp(localPort(acceptor), udpNow)
                   + "} snmpUdp(host-wide)=" + snmpDeltas() + "}";
        }

        private static String channel(Channel datagram) {
            return datagram.getClass().getSimpleName() + ":" + localPort(datagram) + " active=" + datagram.isActive()
                   + " writable=" + datagram.isWritable();
        }

        private String udp(int port, Map<String, long[]> now) {
            if (TABLES.stream().noneMatch(Files::isReadable)) {
                return NON_LINUX;
            }

            var suffix = ":" + port;

            return now.entrySet()
                      .stream()
                      .filter(entry -> entry.getKey().endsWith(suffix))
                      .map(entry -> queue(entry.getKey(), udpAtStart.get(entry.getKey()), entry.getValue()))
                      .toList()
                      .toString();
        }

        private static String queue(String socket, long[] start, long[] now) {
            var inode = socket.substring(0, socket.indexOf(':'));

            return start == null
                   ? "inode " + inode + " (new since start) rx_queue=" + now[0] + " drops=" + now[1]
                   : "inode " + inode + " rx_queue " + start[0] + "->" + now[0] + " drops " + start[1] + "->" + now[1] + " (+"
                     + (now[1] - start[1]) + ")";
        }

        private String snmpDeltas() {
            if (snmpAtStart.isEmpty()) {
                return NON_LINUX;
            }

            var now = snmp();

            return SNMP_FIELDS.stream()
                              .map(field -> field + " +" + (now.getOrDefault(field, 0L) - snmpAtStart.getOrDefault(field, 0L)))
                              .collect(Collectors.joining(" "));
        }

        private static int localPort(Channel datagram) {
            return datagram.localAddress() instanceof InetSocketAddress address
                   ? address.getPort()
                   : -1;
        }

        /// "inode:port" -> {rx_queue, drops} for every socket bound to one of `ports`.
        private static Map<String, long[]> udpQueues(List<Integer> ports) {
            var queues = new LinkedHashMap<String, long[]>();

            TABLES.stream()
                  .filter(Files::isReadable)
                  .flatMap(LaneDiagnosis::entries)
                  .filter(fields -> fields.length > 12)
                  .forEach(fields -> ports.stream()
                                          .filter(port -> fields[1].endsWith(String.format(":%04X", port)))
                                          .forEach(port -> queues.put(fields[9] + ":" + port,
                                                                      new long[]{rxQueue(fields[4]), Long.parseLong(fields[12])})));
            return queues;
        }

        private static long rxQueue(String txRx) {
            return Long.parseLong(txRx.substring(txRx.indexOf(':') + 1), 16);
        }

        /// `/proc/net/snmp` carries two "Udp:" lines: the field names, then the values.
        private static Map<String, Long> snmp() {
            try {
                var udp = Files.readAllLines(SNMP)
                               .stream()
                               .filter(line -> line.startsWith("Udp:"))
                               .map(line -> line.substring(4).trim().split("\\s+"))
                               .toList();
                var values = new LinkedHashMap<String, Long>();

                for (int i = 0; udp.size() == 2 && i < udp.get(0).length; i++) {
                    values.put(udp.get(0)[i], Long.parseLong(udp.get(1)[i]));
                }
                return values;
            } catch (Exception e) {
                return Map.of();
            }
        }
    }

    /// Counts datagrams at the head of a datagram pipeline: reads before the QUIC codec, writes after it. Test-only.
    private static final class DatagramCounter extends ChannelDuplexHandler {
        private final AtomicLong in = new AtomicLong();
        private final AtomicLong out = new AtomicLong();
        private final AtomicLong outFailed = new AtomicLong();
        private final AtomicLong outUnobserved = new AtomicLong();

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            in.incrementAndGet();
            ctx.fireChannelRead(msg);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            out.incrementAndGet();
            if (promise.isVoid()) {
                outUnobserved.incrementAndGet();
            } else {
                promise.addListener(future -> {
                    if (!future.isSuccess()) {
                        outFailed.incrementAndGet();
                    }
                });
            }
            ctx.write(msg, promise);
        }

        @Override
        public String toString() {
            return "datagramsIn=" + in.get() + " datagramsOut=" + out.get() + " outFailed=" + outFailed.get()
                   + " outUnobserved(void promise)=" + outUnobserved.get();
        }
    }

    /// Every UDP socket bound to `port`, from Linux `/proc/net/udp{,6}`, each marked as this JVM's (its inode is among this
    /// process's socket fds) or OTHER. v1677 reproduced #1727 as a foreign reuse-enabled socket taking over the acceptor's
    /// port mid-burst (#1719), so a shortfall names any intruder. Best-effort: "n/a" off Linux.
    static String portSharers(int port) {
        if (TABLES.stream().noneMatch(Files::isReadable)) {
            return "n/a";
        }

        var own = ownSocketInodes();
        var suffix = String.format(":%04X", port);

        return TABLES.stream()
                     .filter(Files::isReadable)
                     .flatMap(LaneDiagnosis::entries)
                     .filter(fields -> fields.length > 9 && fields[1].endsWith(suffix))
                     .map(fields -> "inode " + fields[9] + (own.contains(fields[9]) ? " (this JVM)" : " (OTHER)"))
                     .toList()
                     .toString();
    }

    private static Stream<String[]> entries(Path table) {
        try {
            return Files.readAllLines(table)
                        .stream()
                        .skip(1)
                        .map(line -> line.trim().split("\\s+"));
        } catch (Exception e) {
            return Stream.empty();
        }
    }

    private static Set<String> ownSocketInodes() {
        try (var fds = Files.list(Path.of("/proc/self/fd"))) {
            return fds.map(LaneDiagnosis::link)
                      .filter(target -> target.startsWith("socket:["))
                      .map(target -> target.substring("socket:[".length(), target.length() - 1))
                      .collect(Collectors.toSet());
        } catch (Exception e) {
            return Set.of();
        }
    }

    private static String link(Path fd) {
        try {
            return Files.readSymbolicLink(fd).toString();
        } catch (Exception e) {
            return "";
        }
    }
}
