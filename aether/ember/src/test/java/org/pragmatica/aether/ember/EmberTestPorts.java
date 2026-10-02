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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;

import org.pragmatica.aether.node.health.CoreSwimHealthDetector;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
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
    /// SWIM binds UDP at the node's cluster port plus this: the production constant itself (AetherNode binds SWIM at
    /// `port + CoreSwimHealthDetector.SWIM_PORT_OFFSET`), so the probe cannot drift from the bind (#1698's CI flake).
    static final int SWIM_PORT_OFFSET = CoreSwimHealthDetector.SWIM_PORT_OFFSET;
    static final int START_ATTEMPTS = 5;
    /// The one probed pool every Ember test that scans for a free block draws from. Below the Linux ephemeral floor
    /// (32768): a base inside 32768-60999 can be taken by any concurrent module's outbound connection between probe and
    /// bind. Tests in this module run one after another, so they share it; a block still held or in TIME_WAIT from the
    /// test before is skipped by the probe. The step is half a block: candidates are alternatives that never coexist, and
    /// the probe binds every port of a candidate, so a running cluster's ports rule out the overlapping candidates.
    /// Registered as one scan row in TEST_PORT_ALLOCATION.md.
    static final int POOL_FIRST = 1030;
    static final int POOL_LAST = 1830;
    static final int POOL_STEP = 100;

    /// `reservedOffsets`: further ports (TCP and UDP) the test uses at `base + offset`, e.g. a dead seed's address.
    record Block(int first,
                 int last,
                 int step,
                 int slots,
                 int mgmtOffset,
                 int appOffset,
                 List<Integer> reservedOffsets) {
        Block(int first, int last, int step, int slots, int mgmtOffset, int appOffset) {
            this(first, last, step, slots, mgmtOffset, appOffset, List.of());
        }
    }

    private EmberTestPorts() {}

    /// The first block in range whose every port is free right now.
    static int freeBase(Block block) {
        return freeBase(block, Set.of());
    }

    /// As [#freeBase(Block)], skipping `excluded` bases (#1707 review: a base that lost a bind is not retried, even if
    /// the port that collided has since been released).
    static int freeBase(Block block, Set<Integer> excluded) {
        for (int base = block.first(); base <= block.last(); base += block.step()) {
            if (!excluded.contains(base) && isFree(block, base)) {
                return base;
            }
        }

        return fail("no free port block between " + block.first() + " and " + block.last());
    }

    /// A started cluster on a free block. `clusterAt` builds the (unstarted) cluster for a base port.
    static EmberCluster startedCluster(Block block, IntFunction<EmberCluster> clusterAt, TimeSpan startBound) {
        return startedCluster(block, clusterAt, EmberCluster::start, EmberCluster::stop, startBound);
    }

    /// The block's retry path over any cluster type: the production base choice, excluding every attempted base.
    static <C> C startedCluster(Block block,
                                IntFunction<C> clusterAt,
                                Function<C, Promise<Unit>> start,
                                Function<C, Promise<Unit>> stop,
                                TimeSpan startBound) {
        return startedCluster(clusterAt, start, stop, startBound, attempted -> freeBase(block, attempted));
    }

    /// As above, with the base choice given the bases already attempted (a seam for EmberTestPortsTest).
    static EmberCluster startedCluster(IntFunction<EmberCluster> clusterAt,
                                       TimeSpan startBound,
                                       ToIntFunction<Set<Integer>> chooseBase) {
        return startedCluster(clusterAt, EmberCluster::start, EmberCluster::stop, startBound, chooseBase);
    }

    /// The retry loop over any cluster type, so EmberTestPortsTest can inject start and stop outcomes deterministically
    /// (#1707 review round 2: a real cluster cannot be made to fail its stop on demand).
    static <C> C startedCluster(IntFunction<C> clusterAt,
                                Function<C, Promise<Unit>> start,
                                Function<C, Promise<Unit>> stop,
                                TimeSpan startBound,
                                ToIntFunction<Set<Integer>> chooseBase) {
        var lastCollision = "";
        var attempted = new HashSet<Integer>();

        for (int attempt = 1; attempt <= START_ATTEMPTS; attempt++) {
            var base = chooseBase.applyAsInt(Set.copyOf(attempted));

            attempted.add(base);
            var cluster = clusterAt.apply(base);
            var outcome = start.apply(cluster).await(startBound).fold(Cause::message, _ -> "started");

            if ("started".equals(outcome)) {
                return cluster;
            }

            var stopped = stop.apply(cluster).await(startBound).fold(Cause::message, _ -> "stopped");

            if (!"stopped".equals(stopped)) {
                // A cluster that did not stop may still hold sockets; starting another beside it proves nothing.
                return fail("cluster start on base " + base
                           + " failed (" + outcome
                           + ") and its cleanup failed too: " + stopped);
            }

            if (!isBindCollision(outcome)) {
                return fail("cluster start on base " + base + " failed: " + outcome);
            }

            lastCollision = outcome;
            log.warn("Ember test cluster on base {} lost a port between probe and bind (attempt {}/{}); retrying on a "
                    + "fresh block: {}",
                     base,
                     attempt,
                     START_ATTEMPTS,
                     outcome);
        }

        return fail("every one of " + START_ATTEMPTS
                   + " cluster starts lost a port between probe and bind; last: " + lastCollision);
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

        return block.reservedOffsets()
                    .stream()
                    .allMatch(offset -> tcpFree(base + offset) && udpFree(base + offset));
    }

    /// One node's ports in the block: QUIC (TCP and UDP), SWIM UDP, management and app HTTP.
    static boolean slotFree(Block block, int base, int slot) {
        var quic = base + slot;

        return tcpFree(quic)
               && udpFree(quic)
               && udpFree(quic + SWIM_PORT_OFFSET)
               && tcpFree(base + block.mgmtOffset() + slot)
               && tcpFree(base + block.appOffset() + slot);
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
