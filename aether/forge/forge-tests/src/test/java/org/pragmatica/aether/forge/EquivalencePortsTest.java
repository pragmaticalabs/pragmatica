// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.util.function.IntPredicate;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The F4 port-block choice, offline (f4fixC, f4fixD). Each test holds real sockets, so the probe is exercised against the OS, not a stub.
/// The blocks are never assumed free: [#twoFreeBlocks] probes a start whose block and the next block are both free on this host (retrying),
/// and every test holds only the one port it is about, at a literal offset.
class EquivalencePortsTest {
    private static final int NODES = 3;

    /// A start port whose block and next block (by [EquivalencePorts#STEP]) are both free right now; retried from fresh ephemeral ports so a
    /// busy host does not turn a test red on a neighbour's socket.
    private static int twoFreeBlocks() throws IOException {
        for (var attempt = 0; attempt < 200; attempt++) {
            int start;

            try (var probe = new ServerSocket(0)) {
                start = probe.getLocalPort();
            }
            if (start + 2 * EquivalencePorts.STEP + 300 < 65_000 && blockIsFree(start) && blockIsFree(start + EquivalencePorts.STEP)) {
                return start;
            }
        }
        throw new IllegalStateException("no two free port blocks on this host after 200 probes");
    }

    private static boolean blockIsFree(int start) {
        try {
            return EquivalencePorts.freeBase(start, EquivalencePorts.STEP, 1, NODES, EquivalencePorts::canBind) == start;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /// Mutation: the TCP bind probe removed (every port reported free); the held block is returned and this goes red. Holds the literal
    /// `base + 100` (the management range) and nothing else: the mutant `OFFSETS = {0, 200}` (P6) leaves the block free and goes red too.
    @Test
    void aHeldTcpPort_inTheMiddleRange_skipsItsBlock() throws IOException {
        var start = twoFreeBlocks();

        try (var held = new ServerSocket(start + 100)) {
            assertThat(EquivalencePorts.freeBase(start, NODES)).isEqualTo(start + EquivalencePorts.STEP);
            assertThat(EquivalencePorts.canBind(held.getLocalPort())).isFalse();
        }
    }

    /// The application range, literal `base + 200`: an OFFSETS shrunk to two ranges goes red.
    @Test
    void aHeldTcpPort_inTheLastRange_skipsItsBlock() throws IOException {
        var start = twoFreeBlocks();

        try (var held = new ServerSocket(start + 200)) {
            assertThat(EquivalencePorts.freeBase(start, NODES)).isEqualTo(start + EquivalencePorts.STEP);
        }
    }

    /// The cluster transport is QUIC, so the UDP half of the probe is the half that matters (vf4cfable P07, vf4copus P4): only a
    /// `DatagramSocket` is held. Mutation: `new DatagramSocket(port)` replaced by `new DatagramSocket()`; the held block is returned, red.
    @Test
    void aHeldUdpPortOnly_skipsItsBlock() throws IOException {
        var start = twoFreeBlocks();

        try (var held = new DatagramSocket(start)) {
            assertThat(EquivalencePorts.canBind(held.getLocalPort())).as("TCP is free there, UDP is not").isFalse();
            assertThat(EquivalencePorts.freeBase(start, NODES)).isEqualTo(start + EquivalencePorts.STEP);
        }
    }

    /// The last node's port of the first range, `base + nodes - 1` (vf4copus P5). Mutation: the per-range node loop shrunk to one port.
    @Test
    void aHeldPort_ofTheLastNode_skipsItsBlock() throws IOException {
        var start = twoFreeBlocks();

        try (var held = new ServerSocket(start + NODES - 1)) {
            assertThat(EquivalencePorts.freeBase(start, NODES)).isEqualTo(start + EquivalencePorts.STEP);
        }
    }

    /// The margin beyond the node count (a re-placing scenario binds one instance per node): the last margin port of the last range, as a
    /// literal (3 nodes + a margin of 4 = 7 ports per range, so the last is `base + 200 + 6`); the constant is deliberately not used, or any
    /// MARGIN would pass. Mutation: MARGIN shrunk (0, 1, 3) or dropped from the loop bound (f4fixE: P4, P4b).
    @Test
    void aHeldPort_atTheEndOfTheMargin_skipsItsBlock() throws IOException {
        var start = twoFreeBlocks();

        try (var held = new ServerSocket(start + 200 + 6)) {
            assertThat(EquivalencePorts.freeBase(start, NODES)).isEqualTo(start + EquivalencePorts.STEP);
        }
    }

    @Test
    void aFreeBlock_isChosenAsIs() throws IOException {
        var start = twoFreeBlocks();

        assertThat(EquivalencePorts.freeBase(start, NODES)).isEqualTo(start);
    }

    /// Mutation: the exhausted case returning the last block instead of throwing; goes red (an ERROR, never a PASS on a taken block).
    @Test
    void noFreeBlock_throwsNamingThePort() throws IOException {
        try (var held = new ServerSocket(0)) {
            var port = held.getLocalPort();

            assertThatThrownBy(() -> EquivalencePorts.freeBase(port, EquivalencePorts.STEP, 1, NODES, EquivalencePorts::canBind))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(String.valueOf(port));
        }
    }

    /// The taken-port list of the exhausted message is the ports that were busy, in probe order (f4fixE P8d): the start port also appears in
    /// "from base N", so naming it proves nothing. Mutation: `taken.addAll(busy)` removed; the list reads `[]`.
    @Test
    void noFreeBlock_listsTheBusyPorts() {
        IntPredicate canBind = port -> port != 5001 && port != 5401 && port != 5602;

        assertThatThrownBy(() -> EquivalencePorts.freeBase(5000, 400, 2, 3, canBind))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("no free port block in 2 tries from base 5000 (step 400); busy ports: [5001, 5401, 5602]");
    }
}
