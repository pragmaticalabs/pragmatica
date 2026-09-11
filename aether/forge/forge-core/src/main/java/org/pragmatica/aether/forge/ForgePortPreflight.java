// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.channels.DatagramChannel;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/// #1008 — startup guard refusing to form a Forge cluster on QUIC ports another process already holds.
///
/// WHY A PREFLIGHT AND NOT A GUARD ON THE BIND ITSELF. The QUIC bind cannot report this collision.
/// `QuicClusterServer` sets `SO_REUSEADDR` on its `NioDatagramChannel` — deliberately, so a restarting
/// node rebinds its own port immediately — and two UDP sockets that BOTH set `SO_REUSEADDR` bind the
/// same port successfully. Measured on Darwin 25.5.0, all four combinations: holder and probe both
/// carrying `SO_REUSEADDR` is the ONLY one where the second bind succeeds; the other three are refused
/// with `Address already in use`. Forge-versus-Forge is exactly that one case. So a second Forge does
/// not fail to bind — it binds, then splits the port's datagrams with the first instance and never
/// reaches quorum, presenting as `activePeerCount=1`.
///
/// `QuicTransportError.BindFailed` already names the port and already aborts the whole cluster start,
/// so propagating bind failures harder changes nothing: on this path nothing fails. The probe below
/// therefore binds each port WITHOUT `SO_REUSEADDR`, which IS refused while any holder exists, and
/// reports before a single node is created.
///
/// LIMITATION, stated rather than papered over: this is a time-of-check/time-of-use probe. Each socket
/// is closed before the cluster binds the port for real, so a process claiming it inside that window is
/// not caught and the collision is silent again. It turns the overwhelmingly common case — another
/// Forge already running — from silent into loud. It is not a lock, and it is not claimed to be one.
public sealed interface ForgePortPreflight {
    /// Verify every QUIC/consensus UDP port this cluster is about to bind is free.
    ///
    /// @param basePort first UDP port of the range; node `i` binds `basePort + i`
    /// @param nodes    cluster size, so the range checked is `basePort .. basePort + nodes - 1`
    ///
    /// @return success when the whole range is free, otherwise a cause naming every occupied port
    static Result<Unit> ensureQuicPortsFree(int basePort, int nodes) {
        return verdict(basePort, nodes, occupiedPorts(basePort, nodes));
    }

    private static Result<Unit> verdict(int basePort, int nodes, List<Integer> occupied) {
        return occupied.isEmpty()
               ? Result.unitResult()
               : ForgePortError.quicPortsInUse(basePort, basePort + nodes - 1, occupied).result();
    }

    private static List<Integer> occupiedPorts(int basePort, int nodes) {
        return IntStream.range(0, nodes)
                        .map(offset -> basePort + offset)
                        .filter(ForgePortPreflight::isOccupied)
                        .boxed()
                        .toList();
    }

    private static boolean isOccupied(int port) {
        return probe(port).isFailure();
    }

    private static Result<InetSocketAddress> probe(int port) {
        return Result.lift(() -> bindAndClose(port));
    }

    /// Adapter leaf — the kernel is the only authority on whether a port is free. Deliberately does
    /// NOT set `SO_REUSEADDR`: that omission is the entire mechanism (see the type documentation).
    @SuppressWarnings("JBCT-EX-01")
    private static InetSocketAddress bindAndClose(int port) throws Exception {
        try (var channel = DatagramChannel.open(StandardProtocolFamily.INET)) {
            channel.bind(new InetSocketAddress(port));

            return (InetSocketAddress) channel.getLocalAddress();
        }
    }

    sealed interface ForgePortError extends Cause {
        /// The message discriminates this failure from the other producer of `activePeerCount=1` —
        /// stale on-disk `forge-data` state — because once the cluster is up the two are
        /// indistinguishable from the output, which is what cost the debugging time in #1008.
        record QuicPortsInUse(int basePort, int lastPort, List<Integer> occupied) implements ForgePortError {
            @Override
            public String message() {
                return "Aether Forge needs UDP ports " + basePort
                     + "-" + lastPort
                     + " for cluster consensus (QUIC), but " + describeOccupied()
                     + " already in use - most likely another Forge instance on this host. "
                     + "This is a PORT COLLISION caught before startup: no node was started, so it is "
                     + "NOT stale cluster state in forge-data/ and NOT a consensus fault. "
                     + "Set base_port under [cluster] in forge.toml to a free range, or stop the "
                     + "process holding these ports (lsof -nP -iUDP:" + basePort
                     + ").";
            }

            private String describeOccupied() {
                return occupied.size() == 1
                       ? "port " + occupied.getFirst() + " is"
                       : "ports " + occupied.stream()
                                            .map(String::valueOf)
                                            .collect(Collectors.joining(", ")) + " are";
            }
        }

        static ForgePortError quicPortsInUse(int basePort, int lastPort, List<Integer> occupied) {
            return new QuicPortsInUse(basePort, lastPort, occupied);
        }
    }

    record unused() implements ForgePortPreflight {}
}
