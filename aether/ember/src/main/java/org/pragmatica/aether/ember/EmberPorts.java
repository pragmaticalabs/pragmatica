// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.util.List;
import java.util.function.IntUnaryOperator;

import org.pragmatica.aether.node.health.CoreSwimHealthDetector;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;


/// Dynamic ports for Ember clusters that share a host: one WINDOW per JVM, reused by every cluster the JVM starts.
///
/// WHY. Tests of one JVM run one after another, so a JVM needs the ports of one cluster at a time, not a range per test
/// class. Every JVM (any reactor, checkout or user on the host) claims one window of sub-windows of
/// [#SUB_WINDOW_PORTS] ports each, from the slots of a [Windows] table below the Linux ephemeral floor (32768). A cluster
/// leases one sub-window, at fixed offsets from its base, and consecutive clusters rotate through the sub-windows. There is no
/// coordinator: the claim is the only synchronisation.
///
/// THE CLAIM. A window slot belongs to the JVM that holds its sentinel: a TCP listener on a port of a separate sentinel
/// band, bound WITHOUT `SO_REUSEADDR` and held until the process exits. A TCP listener is the lock because a second bind of a
/// listening port fails on Linux whatever options the binder sets, whereas a UDP socket with `SO_REUSEADDR` binds beside an
/// existing one and takes its datagrams (#1727); the kernel drops a dead process's listener, so `kill -9` leaves nothing
/// stale. Both were measured on Linux (bigboy, 2026-10-04). The walk over slots starts at a random slot.
///
/// WHAT THE SENTINEL DOES NOT COVER. A process that does not use this allocator and binds a window port. At claim time
/// every port of the window is therefore probed, bound exactly as the nodes bind: UDP exclusively, TCP with the JDK's default
/// options (`SO_REUSEADDR` on, which Linux honours through TIME_WAIT, so a previous JVM's closed connections do not make
/// the window look busy). A process that binds a window port AFTER the claim is not prevented; the cluster's own bind failure
/// (`BindFailed`) is the backstop, and a retry leases the next sub-window.
public sealed interface EmberPorts {
    /// SWIM binds UDP at the node's cluster port plus this: the production constant itself.
    int SWIM_PORT_OFFSET = CoreSwimHealthDetector.SWIM_PORT_OFFSET;
    /// First port past the Linux ephemeral floor; no window may reach it.
    int EPHEMERAL_FLOOR = 32768;
    /// Ports per sub-window: QUIC and the spare ports `[0, 100)`, SWIM at `+100`; room for 28 slots.
    int SUB_WINDOW_PORTS = 128;
    /// Sub-windows per window in the default table: the clusters a JVM can hold at once, and the rotation length.
    int DEFAULT_SUB_WINDOWS = 3;
    /// How long a JVM that finds every window slot held rescans before it fails (`-Dember.ports.claimWaitMs`).
    long DEFAULT_CLAIM_WAIT_MS = 60_000L;

    /// `slots` windows of `subWindows` sub-windows each from `first`, and the `slots` consecutive TCP sentinel ports from
    /// `sentinelFirst`.
    ///
    /// The default sits in a gap that no `src/main` or `src/test` literal and no registered range of
    /// `TEST_PORT_ALLOCATION.md` touches (checked with a +450 margin over every port-context literal in the tree at rc4
    /// `222b7026d`), so it can share a host with the tests that still pin ports. It is widened when they are gone.
    record Windows(int first, int slots, int subWindows, int sentinelFirst) {
        /// Windows are disjoint from their sentinels and lie below the ephemeral floor, or there are no windows.
        public static Result<Windows> windows(int first, int slots, int subWindows, int sentinelFirst) {
            var end = first + slots * subWindows * SUB_WINDOW_PORTS;
            var sentinelsOverlapWindows = sentinelFirst < end && first < sentinelFirst + slots;

            return first < 1024 || slots < 1 || subWindows < 1 || end > EPHEMERAL_FLOOR || sentinelFirst < 1024 || sentinelFirst + slots > EPHEMERAL_FLOOR || sentinelsOverlapWindows
                   ? Causes.cause("windows " + first
                                 + "+" + slots
                                 + "x" + subWindows
                                 + "x" + SUB_WINDOW_PORTS
                                 + " with sentinels from " + sentinelFirst
                                 + " are not disjoint ports in 1024-" + (EPHEMERAL_FLOOR - 1)).result()
                   : Result.success(new Windows(first, slots, subWindows, sentinelFirst));
        }

        int windowPorts() {
            return subWindows * SUB_WINDOW_PORTS;
        }

        int windowBase(int slot) {
            return first + slot * windowPorts();
        }

        int sentinel(int slot) {
            return sentinelFirst + slot;
        }
    }

    Windows DEFAULT_WINDOWS = Windows.windows(26208, 3, DEFAULT_SUB_WINDOWS, 27400).unwrap();

    /// What a cluster needs inside its sub-window. Port `s` (0 .. slots-1) relative to the sub-window's `base`: QUIC at
    /// `s`, SWIM at `SWIM_PORT_OFFSET + s`, management at `mgmtOffset + s`, app HTTP at `appOffset + s`.
    /// `reservedOffsets` are further ports, TCP and UDP, a test occupies on purpose (a dead seed, a held port).
    record Layout(int slots, int mgmtOffset, int appOffset, List<Integer> reservedOffsets) {
        public Layout {
            reservedOffsets = List.copyOf(reservedOffsets);
        }

        public Layout(int slots, int mgmtOffset, int appOffset) {
            this(slots, mgmtOffset, appOffset, List.of());
        }

        /// Ports from the base up to and including the highest offset the layout uses.
        public int extent() {
            var highest = Math.max(Math.max(slots, SWIM_PORT_OFFSET + slots),
                                   Math.max(mgmtOffset + slots, appOffset + slots));

            return Math.max(highest,
                            reservedOffsets.stream().mapToInt(offset -> offset + 1).max().orElse(0));
        }
    }

    /// A cluster's lease on a sub-window: the base its ports are computed from. Closing it returns the sub-window to the
    /// JVM's window (idempotent); it does not touch the cluster's own sockets.
    record PortLease(int base, Layout layout, Runnable release) implements AutoCloseable {
        public int mgmtBase() {
            return base + layout.mgmtOffset();
        }

        public int appHttpBase() {
            return base + layout.appOffset();
        }

        /// A port the lease holds for a test that occupies it on purpose (see [Layout#reservedOffsets]).
        public int port(int offset) {
            return base + offset;
        }

        public boolean contains(int port) {
            return port >= base && port < base + layout.extent();
        }

        @Override
        @SuppressWarnings("JBCT-RET-01")  // AutoCloseable.close
        public void close() {
            release.run();
        }
    }

    /// Lease a sub-window of this JVM's window in [#DEFAULT_WINDOWS], claiming the window first when the JVM has none.
    static Result<PortLease> lease(Layout layout) {
        return lease(DEFAULT_WINDOWS, layout);
    }

    static Result<PortLease> lease(Windows windows, Layout layout) {
        return PortWindows.lease(windows, layout, PortWindows.defaultChooser(), PortWindows.defaultWaitMs());
    }

    /// As above with the first slot tried chosen by `startChooser` (given the slot count, returns an index in
    /// `0 .. slots-1`); a seam so a test can make two claimants contend for the same slot.
    static Result<PortLease> lease(Windows windows, Layout layout, IntUnaryOperator startChooser) {
        return PortWindows.lease(windows, layout, startChooser, PortWindows.defaultWaitMs());
    }

    /// As above with the wait for a held window bounded by `waitMs` instead of the default.
    static Result<PortLease> lease(Windows windows, Layout layout, IntUnaryOperator startChooser, long waitMs) {
        return PortWindows.lease(windows, layout, startChooser, waitMs);
    }

    record unused() implements EmberPorts {}
}
