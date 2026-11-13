// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

/// Picks the port block an F4 arm binds (f4fixC: a fixed `33000 + 400 * index` turned a leftover holder on the host into a round ERROR).
/// Every port the arm will bind (cluster, management and application offsets, one per node plus a margin) is bind-checked; a taken block moves
/// the arm to the next block, and when none is free the round ends in an ERROR naming the ports. The block is never assumed free.
final class EquivalencePorts {
    /// Offsets of the three port ranges `emberCluster(nodes, base, base + 100, base + 200, ...)` binds.
    static final int[] OFFSETS = {0, 100, 200};
    /// Block spacing: the three ranges above fit in 300 ports.
    static final int STEP = 400;
    static final int TRIES = 8;
    /// Ports beyond the node count that a re-placing scenario may bind.
    private static final int MARGIN = 4;

    private EquivalencePorts() {}

    /// The first block at or after `start` (stepping by [#STEP], at most [#TRIES] blocks) whose ports all bind on this host.
    static int freeBase(int start, int nodes) {
        return freeBase(start, STEP, TRIES, nodes, EquivalencePorts::canBind);
    }

    static int freeBase(int start, int step, int tries, int nodes, IntPredicate canBind) {
        var taken = new ArrayList<Integer>();

        for (var attempt = 0; attempt < tries; attempt++) {
            var base = start + step * attempt;
            var busy = busyPorts(base, nodes, canBind);

            if (busy.isEmpty()) {
                return base;
            }
            taken.addAll(busy);
        }
        throw new IllegalStateException("no free port block in %d tries from base %d (step %d); busy ports: %s".formatted(tries, start, step, taken));
    }

    private static List<Integer> busyPorts(int base, int nodes, IntPredicate canBind) {
        var busy = new ArrayList<Integer>();

        for (var offset : OFFSETS) {
            for (var node = 0; node < nodes + MARGIN; node++) {
                var port = base + offset + node;

                if (!canBind.test(port)) {
                    busy.add(port);
                }
            }
        }
        return busy;
    }

    /// True when both a TCP and a UDP socket bind the port on the wildcard address (the way the nodes bind it).
    static boolean canBind(int port) {
        try (var tcp = new ServerSocket(port); var udp = new DatagramSocket(port)) {
            return tcp.isBound() && udp.isBound();
        } catch (IOException e) {
            return false;
        }
    }
}
