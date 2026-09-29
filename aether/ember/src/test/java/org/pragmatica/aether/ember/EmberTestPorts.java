// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.function.IntFunction;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.io.TimeSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.fail;

/// Port blocks for Ember cluster tests (#939, #1189, #1667).
///
/// A probed block is only a GOOD GUESS: the probe releases each port before the cluster binds it, and CI runs a
/// module-parallel reactor (`-T 1C`), so another process can take a port in between. [#startedCluster] therefore
/// retries on a bind collision with a fresh block, a bounded number of times, instead of failing the test on a
/// port it never chose. A start failure that is not a bind collision fails at once.
///
/// A block is `slots` consecutive ports from `base` for QUIC, the same slots at `base + mgmtOffset` (management) and
/// `base + appOffset` (app HTTP), all TCP, plus SWIM's UDP port at QUIC port + [#SWIM_PORT_OFFSET].
final class EmberTestPorts {
    private static final Logger log = LoggerFactory.getLogger(EmberTestPorts.class);
    /// SWIM binds UDP at the node's cluster port plus this (`SwimHealthState.SWIM_PORT_OFFSET`).
    static final int SWIM_PORT_OFFSET = 100;
    static final int START_ATTEMPTS = 5;

    record Block(int first, int last, int step, int slots, int mgmtOffset, int appOffset) {}

    private EmberTestPorts() {}

    /// The first block in range whose every port is free right now.
    static int freeBase(Block block) {
        for (int base = block.first(); base <= block.last(); base += block.step()) {
            if (isFree(block, base)) {
                return base;
            }
        }
        return fail("no free port block between " + block.first() + " and " + block.last());
    }

    /// A started cluster on a free block. `clusterAt` builds the (unstarted) cluster for a base port.
    static EmberCluster startedCluster(Block block, IntFunction<EmberCluster> clusterAt, TimeSpan startBound) {
        var lastCollision = "";

        for (int attempt = 1; attempt <= START_ATTEMPTS; attempt++) {
            var base = freeBase(block);
            var cluster = clusterAt.apply(base);
            var outcome = cluster.start()
                                 .await(startBound)
                                 .fold(Cause::message, _ -> "started");

            if ("started".equals(outcome)) {
                return cluster;
            }
            cluster.stop()
                   .await(startBound);
            if (!isBindCollision(outcome)) {
                return fail("cluster start on base " + base + " failed: " + outcome);
            }
            lastCollision = outcome;
            log.warn("Ember test cluster on base {} lost a port between probe and bind (attempt {}/{}); retrying on a "
                     + "fresh block: {}", base, attempt, START_ATTEMPTS, outcome);
        }
        return fail("every one of " + START_ATTEMPTS + " cluster starts lost a port between probe and bind; last: "
                    + lastCollision);
    }

    static boolean isBindCollision(String startFailure) {
        return startFailure.contains("Address already in use") || startFailure.contains("BindException");
    }

    static boolean isFree(Block block, int base) {
        for (int slot = 0; slot < block.slots(); slot++) {
            if (!slotFree(block, base, slot)) {
                return false;
            }
        }
        return true;
    }

    /// One node's ports in the block: QUIC (TCP and UDP), SWIM UDP, management and app HTTP.
    static boolean slotFree(Block block, int base, int slot) {
        var quic = base + slot;

        return tcpFree(quic) && udpFree(quic) && udpFree(quic + SWIM_PORT_OFFSET)
               && tcpFree(base + block.mgmtOffset() + slot) && tcpFree(base + block.appOffset() + slot);
    }

    private static boolean tcpFree(int port) {
        try (var socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(loopback(port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean udpFree(int port) {
        try (var socket = new DatagramSocket(null)) {
            socket.setReuseAddress(false);
            socket.bind(loopback(port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static InetSocketAddress loopback(int port) {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
    }
}
