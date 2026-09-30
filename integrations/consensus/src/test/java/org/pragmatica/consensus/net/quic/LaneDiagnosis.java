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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import io.netty.handler.codec.quic.QuicChannel;

/// #1727 — the diagnosis every failure in the lane-delivery tests carries (`QuicLaneOwnershipTest`,
/// `QuicLaneFinishUnderLossTest`), so a red on ANY wait, not only on the delivery check, is attributable without a rerun.
/// [#stall] snapshots both ends' QUIC counters twice, [#RECHECK] apart: a recvB that stays flat while the other end's sentB
/// or lostB keeps growing is a stall, a moving one is a slow path. It also names every socket bound to the acceptor's port
/// ([#portSharers]), because #1719's port theft looked exactly like a stall.
final class LaneDiagnosis {
    static final TimeSpan RECHECK = TimeSpan.timeSpan(5).seconds();

    private static final List<Path> TABLES = List.of(Path.of("/proc/net/udp"), Path.of("/proc/net/udp6"));

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

    /// Where acknowledged-but-unread writes stopped: CONNECTION-CLOSED (quiche discarded what it held), RECEIVER-SIDE (the
    /// acceptor's QUIC stack received at least the missing bytes beyond what the app read), or SENDER-SIDE (it received
    /// less, so they stopped at or before the dialer's QUIC stack). Received bytes include retransmitted duplicates and the
    /// other lanes, so the verdict leans RECEIVER-SIDE when close; callers print the raw numbers too.
    static String verdict(boolean connected, long receivedBeyondDelivered, long missingBytes) {
        if (!connected) {
            return "CONNECTION-CLOSED";
        }
        return (receivedBeyondDelivered >= missingBytes ? "RECEIVER-SIDE" : "SENDER-SIDE") + " (acceptor QUIC received "
               + receivedBeyondDelivered + " B beyond what the app read " + (receivedBeyondDelivered >= missingBytes ? ">=" : "<")
               + " " + missingBytes + " B undelivered)";
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
