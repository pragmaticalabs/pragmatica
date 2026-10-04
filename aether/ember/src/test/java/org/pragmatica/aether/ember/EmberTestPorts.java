// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntFunction;

import org.pragmatica.aether.ember.EmberPorts.Layout;
import org.pragmatica.aether.ember.EmberPorts.PortLease;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.fail;


/// Port leases for Ember cluster tests (#939, #1189, #1667): the test-side shape over [EmberPorts].
///
/// A test names the block it needs (slots and offsets) and leases a sub-window of this JVM's port window. The window was claimed
/// from a table shared by every JVM on the host, so concurrent Maven reactors do not collide on ports (the claim is the lock,
/// see [EmberPorts]); the tests of one JVM run one after another and reuse the window, rotating through its sub-windows. The
/// lease is handed to the cluster, which frees it when it stops.
///
/// What remains probabilistic is only a process outside the allocator binding a window port after the claim, so
/// [#startedCluster] still retries a start that fails on a bind collision on the next sub-window, a bounded number of times,
/// and any other start failure fails the test at once.
final class EmberTestPorts {
    private static final Logger log = LoggerFactory.getLogger(EmberTestPorts.class);
    static final int SWIM_PORT_OFFSET = EmberPorts.SWIM_PORT_OFFSET;
    static final int START_ATTEMPTS = 5;

    /// `reservedOffsets`: further ports (TCP and UDP) the test uses at `base + offset`, e.g. a dead seed's address.
    record Block(int slots, int mgmtOffset, int appOffset, List<Integer> reservedOffsets) {
        Block(int slots, int mgmtOffset, int appOffset) {
            this(slots, mgmtOffset, appOffset, List.of());
        }

        Layout layout() {
            return new Layout(slots, mgmtOffset, appOffset, reservedOffsets);
        }
    }

    /// A base for a cluster that is built but never started or bound (storage-config and wiring tests): nothing listens
    /// on it, so it needs no lease.
    static final int UNBOUND_BASE = 1030;

    private EmberTestPorts() {}

    /// An UNSTARTED cluster on a leased sub-window, for a test that starts it in its own way. The cluster owns the lease and frees it
    /// when it stops; a test that never stops it closes `cluster.releasePortLease()`.
    static EmberCluster clusterOnFreeLease(Block block, IntFunction<EmberCluster> clusterAt) {
        var lease = claim(block);
        var cluster = clusterAt.apply(lease.base());

        cluster.adoptPortLease(lease).onFailure(cause -> fail(cause.message()));
        return cluster;
    }

    /// A sub-window lease for `block`, from this JVM's port window. The caller closes it, or hands it to a cluster
    /// ([EmberCluster#adoptPortLease]) that frees it on stop.
    static PortLease claim(Block block) {
        return EmberPorts.lease(block.layout())
                         .fold(cause -> fail("no port sub-window: " + cause.message()), lease -> lease);
    }

    /// Whether every port of `block` at `base` is free right now.
    static boolean isFree(Block block, int base) {
        var layout = block.layout();

        return java.util.stream.IntStream.range(0, layout.slots()).allMatch(slot -> slotFree(block, base, slot))
               && layout.reservedOffsets().stream().allMatch(offset -> tcpFree(base + offset) && udpFree(base + offset));
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
            socket.bind(new java.net.InetSocketAddress(port));

            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean udpFree(int port) {
        try (var socket = new DatagramSocket(null)) {
            socket.setReuseAddress(false);
            socket.bind(new java.net.InetSocketAddress(port));

            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /// One port a test occupies on purpose, at `base + offset`.
    record Hold(int offset, boolean udp) {
        static Hold tcp(int offset) {
            return new Hold(offset, false);
        }

        static Hold udp(int offset) {
            return new Hold(offset, true);
        }
    }

    /// The sockets a test holds on purpose, on a lease claimed for it; closing frees the sockets and the lease.
    record Held(int base, PortLease lease, List<AutoCloseable> sockets) implements AutoCloseable {
        @Override
        public void close() {
            sockets.forEach(socket -> {
                try {
                    socket.close();
                } catch (Exception e) {
                    log.warn("closing a held test socket failed: {}", e.getMessage());
                }
            });
            lease.close();
        }
    }

    /// Binds `holds` on a claimed lease. A hold that fails to bind lost a port to a process outside the allocator
    /// between the claim's probe and this bind, so it is retried on a fresh lease, a bounded number of times.
    static Held hold(Block block, List<Hold> holds) {
        for (int attempt = 1; attempt <= START_ATTEMPTS; attempt++) {
            var lease = claim(block);
            var opened = new ArrayList<AutoCloseable>();

            try {
                for (var hold : holds) {
                    var port = lease.base() + hold.offset();

                    // The wildcard address and the JDK's default options, as the tests took these ports before: it
                    // collides with the node's bind on any interface.
                    opened.add(hold.udp() ? new DatagramSocket(port) : new ServerSocket(port));
                }
                return new Held(lease.base(), lease, List.copyOf(opened));
            } catch (IOException e) {
                new Held(lease.base(), lease, opened).close();
                log.warn("Test port hold on lease {} lost a port between claim and bind (attempt {}/{}): {}",
                         lease.base(),
                         attempt,
                         START_ATTEMPTS,
                         e.getMessage());
            }
        }

        return fail("every one of " + START_ATTEMPTS + " port holds lost a port between claim and bind");
    }

    /// A started cluster on a free lease. `clusterAt` builds the (unstarted) cluster for a base port; the lease is handed
    /// to the cluster, which frees it when it stops.
    static EmberCluster startedCluster(Block block, IntFunction<EmberCluster> clusterAt, TimeSpan startBound) {
        return startedCluster(block, clusterAt, EmberCluster::start, EmberCluster::stop, startBound);
    }

    /// As above for a cluster that a test starts in its own way (`start` returns the start promise); `adopt` hands over the lease.
    static EmberCluster startedCluster(Block block,
                                       IntFunction<EmberCluster> clusterAt,
                                       Function<EmberCluster, Promise<Unit>> start,
                                       Function<EmberCluster, Promise<Unit>> stop,
                                       TimeSpan startBound) {
        return startedCluster(() -> claim(block),
                              lease -> {
                                  var cluster = clusterAt.apply(lease.base());

                                  cluster.adoptPortLease(lease).onFailure(cause -> fail(cause.message()));
                                  return cluster;
                              },
                              start,
                              stop,
                              startBound);
    }

    /// The retry loop over any cluster type, so EmberTestPortsTest can inject start and stop outcomes deterministically
    /// (a real cluster cannot be made to fail its stop on demand). `claim` yields a fresh lease per attempt; `clusterOn`
    /// builds the cluster on it and is responsible for handing the lease over.
    static <C> C startedCluster(java.util.function.Supplier<PortLease> claim,
                                Function<PortLease, C> clusterOn,
                                Function<C, Promise<Unit>> start,
                                Function<C, Promise<Unit>> stop,
                                TimeSpan startBound) {
        var lastCollision = "";

        for (int attempt = 1; attempt <= START_ATTEMPTS; attempt++) {
            var lease = claim.get();
            var cluster = clusterOn.apply(lease);
            var outcome = start.apply(cluster).await(startBound).fold(Cause::message, _ -> "started");

            if ("started".equals(outcome)) {
                return cluster;
            }

            var stopped = stop.apply(cluster).await(startBound).fold(Cause::message, _ -> "stopped");

            lease.close();

            if (!"stopped".equals(stopped)) {
                // A cluster that did not stop may still hold sockets; starting another beside it proves nothing.
                return fail("cluster start on base " + lease.base()
                           + " failed (" + outcome
                           + ") and its cleanup failed too: " + stopped);
            }

            if (!isBindCollision(outcome)) {
                return fail("cluster start on base " + lease.base() + " failed: " + outcome);
            }

            lastCollision = outcome;
            log.warn("Ember test cluster on lease {} lost a port between claim and bind (attempt {}/{}); retrying on a "
                    + "fresh lease: {}",
                     lease.base(),
                     attempt,
                     START_ATTEMPTS,
                     outcome);
        }

        return fail("every one of " + START_ATTEMPTS
                   + " cluster starts lost a port between claim and bind; last: " + lastCollision);
    }

    static boolean isBindCollision(String startFailure) {
        return startFailure.contains("Address already in use") || startFailure.contains("BindException");
    }
}
