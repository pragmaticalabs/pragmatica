// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntUnaryOperator;

import org.pragmatica.aether.ember.EmberPorts.Layout;
import org.pragmatica.aether.ember.EmberPorts.PortLease;
import org.pragmatica.aether.ember.EmberPorts.Windows;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// The JVM-global state behind [EmberPorts]: the window this JVM holds per [Windows] table, its sentinel, and which of
/// its sub-windows are leased. The state is JVM-lifetime on purpose (see [EmberPorts]); [#release] exists for tests.
final class PortWindows {
    private static final Logger log = LoggerFactory.getLogger(PortWindows.class);
    /// Pins the slot a JVM claims, for a reproducible port layout: `-Dember.ports.windowSlot=2`.
    static final String SLOT_PROPERTY = "ember.ports.windowSlot";
    /// How long a JVM that finds every window slot held keeps rescanning before it fails: `-Dember.ports.claimWaitMs=N`.
    static final String WAIT_PROPERTY = "ember.ports.claimWaitMs";
    private static final long RESCAN_MS = 250L;
    private static final Map<Windows, Window> HELD = new HashMap<>();

    private PortWindows() {}

    /// A window this JVM holds: its slot, the sentinel that makes it ours, which sub-windows are leased and the rotation.
    private static final class Window {
        final int slot;
        final int base;
        final ServerSocket sentinel;
        final boolean[] leased;
        int next;

        Window(int slot, int base, int subWindows, ServerSocket sentinel) {
            this.leased = new boolean[subWindows];
            this.slot = slot;
            this.base = base;
            this.sentinel = sentinel;
        }
    }

    static IntUnaryOperator defaultChooser() {
        return bound -> Integer.getInteger(SLOT_PROPERTY) != null
                        ? Integer.getInteger(SLOT_PROPERTY) % bound
                        : ThreadLocalRandom.current().nextInt(bound);
    }

    static long defaultWaitMs() {
        return Long.getLong(WAIT_PROPERTY, EmberPorts.DEFAULT_CLAIM_WAIT_MS);
    }

    static synchronized Result<PortLease> lease(Windows windows,
                                                Layout layout,
                                                IntUnaryOperator startChooser,
                                                long waitMs) {
        if (layout.extent() > EmberPorts.SUB_WINDOW_PORTS) {
            return Causes.cause("a cluster layout of " + layout.extent()
                               + " ports does not fit a sub-window of " + EmberPorts.SUB_WINDOW_PORTS
                               + " (" + layout
                               + ")").result();
        }

        var held = HELD.get(windows);

        if (held == null) {
            var claimed = claim(windows, startChooser, waitMs);

            if (claimed.isFailure()) {
                return claimed.map(_ -> null);
            }

            held = claimed.unwrap();
            HELD.put(windows, held);
            log.info("Port window claimed: slot {} of {}, ports {}-{}",
                     held.slot,
                     windows.slots(),
                     held.base,
                     held.base + windows.windowPorts() - 1);
        }

        return leaseSub(held, layout);
    }

    private static Result<PortLease> leaseSub(Window window, Layout layout) {
        var count = window.leased.length;

        for (int i = 0; i < count; i++) {
            var sub = (window.next + i) % count;

            if (!window.leased[sub]) {
                window.leased[sub] = true;
                window.next = (sub + 1) % count;

                return Result.success(new PortLease(window.base + sub * EmberPorts.SUB_WINDOW_PORTS,
                                                    layout,
                                                    () -> unlease(window, sub)));
            }
        }

        return Causes.cause("all " + count
                           + " sub-windows of this JVM's port window (slot " + window.slot
                           + ") are leased: a test holds more clusters at once than the window has sub-windows").result();
    }

    private static synchronized void unlease(Window window, int sub) {
        window.leased[sub] = false;
    }

    /// Drop this JVM's window for `windows` (closes the sentinel). For tests of the allocator itself.
    @SuppressWarnings("JBCT-RET-01")  // test seam: nothing to return
    static synchronized void release(Windows windows) {
        var held = HELD.remove(windows);

        if (held != null) {
            closeQuietly(held.sentinel);
        }
    }

    /// Rescans the slots until one is claimed or `waitMs` has passed, then fails naming EVERY slot's sentinel and why it was
    /// refused. A held slot frees only when its JVM exits, so the wait is a bound for a slot about to free, not a back-off:
    /// there is no fallback to a range the table does not own.
    private static Result<Window> claim(Windows windows, IntUnaryOperator startChooser, long waitMs) {
        var start = startChooser.applyAsInt(windows.slots());
        var deadline = System.nanoTime() + waitMs * 1_000_000L;

        while (true) {
            var reasons = new ArrayList<String>();

            for (int i = 0; i < windows.slots(); i++) {
                var slot = (start + i) % windows.slots();
                var sentinelHeld = bindSentinel(windows.sentinel(slot));

                if (sentinelHeld.isEmpty()) {
                    reasons.add("slot " + slot + ": sentinel " + windows.sentinel(slot) + " is HELD by another process");
                    continue;
                }

                var sentinel = sentinelHeld.unwrap();
                var busy = firstBusyPort(windows.windowBase(slot), windows.windowPorts());

                if (busy == 0) {
                    return Result.success(new Window(slot, windows.windowBase(slot), windows.subWindows(), sentinel));
                }

                closeQuietly(sentinel);
                reasons.add("slot " + slot
                           + ": sentinel " + windows.sentinel(slot)
                           + " was free but port " + busy
                           + " is bound by a process outside the allocator");
            }

            if (System.nanoTime() >= deadline) {
                return Causes.cause("no free port window: every one of " + windows.slots()
                                   + " slots from " + windows.first()
                                   + " was refused for " + waitMs
                                   + " ms (" + String.join("; ", reasons)
                                   + ")").result();
            }

            sleep(RESCAN_MS);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /// The first port of the window at `base` that cannot be bound as the nodes bind it, or 0 when all can.
    private static int firstBusyPort(int base, int ports) {
        for (int port = base; port < base + ports; port++) {
            if (!tcpFree(port) || !udpFree(port)) {
                return port;
            }
        }

        return 0;
    }

    private static Option<ServerSocket> bindSentinel(int port) {
        return Result.lift(() -> bindExclusive(port)).option();
    }

    /// Adapter leaf: the kernel decides whether the port is free. Exclusive (no `SO_REUSEADDR`), wildcard, kept open.
    @SuppressWarnings("JBCT-EX-01")
    private static ServerSocket bindExclusive(int port) throws IOException {
        var socket = new ServerSocket();

        try {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(port));

            return socket;
        } catch (IOException e) {
            closeQuietly(socket);

            throw e;
        }
    }

    private static void closeQuietly(ServerSocket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            log.warn("closing a port-window sentinel failed: {}", e.getMessage());
        }
    }

    /// TCP as the node's servers bind: the JDK's default options (`SO_REUSEADDR` on where the platform sets it), so a closed
    /// connection's TIME_WAIT does not count as busy while a live listener does.
    private static boolean tcpFree(int port) {
        try (var socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(port));

            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /// UDP as the QUIC and SWIM sockets bind: exclusively.
    private static boolean udpFree(int port) {
        try (var socket = new DatagramSocket(null)) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(port));

            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
