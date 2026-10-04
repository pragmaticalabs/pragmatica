// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.pragmatica.aether.ember.EmberPorts.Layout;
import org.pragmatica.aether.ember.EmberPorts.Windows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;


/// The window allocator behind dynamic Ember ports. The tests that need a fresh window use a private table (two slots of one
/// sub-window each, in a gap no source literal touches), released after every test, so they neither depend on nor disturb the
/// window the cluster tests of this JVM hold in the default table.
class EmberPortsTest {
    private static final int FIRST = 10700;
    private static final int SENTINEL_FIRST = 10990;
    private static final Windows TEST = Windows.windows(FIRST, 2, 1, SENTINEL_FIRST).unwrap();
    private static final int ROTATION_FIRST = 11163;
    private static final Windows ROTATION = Windows.windows(ROTATION_FIRST, 1, 2, 11490).unwrap();
    private static final Layout LAYOUT = new Layout(3, 40, 80);
    private static final long KILL_RECLAIM_BOUND_MS = 10_000L;
    private static final long EXHAUSTION_WAIT_MS = 1_500L;
    private static final int SLOT_PORTS = EmberPorts.SUB_WINDOW_PORTS;

    private final List<AutoCloseable> opened = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        for (var resource : opened) {
            resource.close();
        }
        PortWindows.release(TEST);
    }

    @Test
    void lease_consecutiveClusters_rotateThroughSubWindowsAndFreedOnesComeBack() {
        var first = EmberPorts.lease(ROTATION, LAYOUT, _ -> 0).unwrap();
        var second = EmberPorts.lease(ROTATION, LAYOUT, _ -> 0).unwrap();

        try {
            assertThat(first.base()).isEqualTo(ROTATION_FIRST);
            assertThat(second.base()).as("the next sub-window follows").isEqualTo(ROTATION_FIRST + SLOT_PORTS);
            assertThat(EmberPorts.lease(ROTATION, LAYOUT, _ -> 0, 300L).isFailure()).as("a third cluster at once has no sub-window").isTrue();

            first.close();
            first.close();
            var third = EmberPorts.lease(ROTATION, LAYOUT, _ -> 0).unwrap();

            assertThat(third.base()).as("the freed sub-window is leased again").isEqualTo(first.base());
            second.close();
            third.close();
            assertThat(EmberPorts.lease(ROTATION, LAYOUT, _ -> 0).unwrap().base())
                .as("rotation: after the sub-window just used comes the next one, not the one just freed")
                .isEqualTo(second.base());
        } finally {
            PortWindows.release(ROTATION);
        }
    }

    @Test
    void lease_defaultWindowLiesBelowTheEphemeralFloor() {
        try (var lease = EmberPorts.lease(LAYOUT).unwrap()) {
            assertThat(lease.base()).isGreaterThanOrEqualTo(EmberPorts.DEFAULT_WINDOWS.first());
            assertThat(lease.base() + EmberPorts.SUB_WINDOW_PORTS).isLessThanOrEqualTo(EmberPorts.EPHEMERAL_FLOOR);
        }
    }

    @Test
    void lease_layoutWiderThanASubWindow_isRefused() {
        var outcome = EmberPorts.lease(TEST, new Layout(30, 40, 80));

        assertThat(outcome.isFailure()).isTrue();
        outcome.onFailure(cause -> assertThat(cause.message()).contains("does not fit a sub-window"));
    }

    @Test
    void lease_holdsItsWindowSentinelUntilReleased() throws IOException {
        var lease = EmberPorts.lease(TEST, LAYOUT, _ -> 0).unwrap();
        var sentinel = SENTINEL_FIRST;

        assertThat(lease.base()).isEqualTo(FIRST);
        assertThat(canBindTcp(sentinel, false)).as("a second binder cannot take the sentinel").isFalse();
        assertThat(canBindTcp(sentinel, true)).as("not even one that sets SO_REUSEADDR").isFalse();

        PortWindows.release(TEST);
        assertThat(canBindTcp(sentinel, false)).as("control: it is bindable once the window is dropped").isTrue();
    }

    @Test
    void lease_udpPortBoundByAnOutsider_skipsThatWindowSlot() throws IOException {
        assertThat(EmberPorts.lease(TEST, LAYOUT, _ -> 0).unwrap().base()).as("control: slot 0 is free and chosen").isEqualTo(FIRST);
        PortWindows.release(TEST);

        var outsider = new DatagramSocket(null);

        opened.add(outsider);
        outsider.setReuseAddress(false);
        outsider.bind(new InetSocketAddress(FIRST + 7));

        assertThat(EmberPorts.lease(TEST, LAYOUT, _ -> 0).unwrap().base()).as("the busy slot 0 is skipped").isEqualTo(FIRST + SLOT_PORTS);
    }

    @Test
    void lease_tcpListenerOutsideTheAllocator_skipsThatWindowSlot() throws IOException {
        var outsider = new ServerSocket(FIRST + 90);

        opened.add(outsider);

        assertThat(EmberPorts.lease(TEST, LAYOUT, _ -> 0).unwrap().base()).isEqualTo(FIRST + SLOT_PORTS);
    }

    @Test
    void lease_everySlotBusy_failsNamingTheFirstConflict() throws IOException {
        opened.add(new ServerSocket(FIRST + 1));
        opened.add(new ServerSocket(FIRST + SLOT_PORTS + 1));

        var outcome = EmberPorts.lease(TEST, LAYOUT, _ -> 0, 500L);

        assertThat(outcome.isFailure()).isTrue();
        outcome.onFailure(cause -> assertThat(cause.message()).contains("no free port window").contains("port " + (FIRST + 1)));
    }

    /// A cluster that has stopped may not have closed its sockets yet, so the sub-window it used is skipped while a port of it
    /// is still bound, instead of handing it to the next cluster to fail with BindException.
    @Test
    void lease_subWindowWhosePortIsStillBound_isSkipped() throws IOException {
        try {
            EmberPorts.lease(ROTATION, LAYOUT, _ -> 0).unwrap().close();

            var lingering = new DatagramSocket(null);

            opened.add(lingering);
            lingering.setReuseAddress(false);
            lingering.bind(new InetSocketAddress(ROTATION_FIRST + SLOT_PORTS + 1));

            assertThat(EmberPorts.lease(ROTATION, LAYOUT, _ -> 0).unwrap().base())
                .as("rotation points at sub-window 1, which still holds a bound port, so the lease goes to sub-window 0")
                .isEqualTo(ROTATION_FIRST);
        } finally {
            PortWindows.release(ROTATION);
        }
    }

    @Test
    void lease_everySubWindowLeasedOrBound_failsAfterTheBoundNamingEach() throws IOException {
        try {
            var leased = EmberPorts.lease(ROTATION, LAYOUT, _ -> 0).unwrap();
            var lingering = new DatagramSocket(null);

            opened.add(lingering);
            lingering.setReuseAddress(false);
            lingering.bind(new InetSocketAddress(ROTATION_FIRST + SLOT_PORTS + 1));

            var startedAt = System.nanoTime();
            var outcome = EmberPorts.lease(ROTATION, LAYOUT, _ -> 0, 400L);

            assertThat(leased.base()).isEqualTo(ROTATION_FIRST);
            assertThat(outcome.isFailure()).isTrue();
            assertThat((System.nanoTime() - startedAt) / 1_000_000L).as("it waited the bound").isGreaterThanOrEqualTo(350L);
            outcome.onFailure(cause -> assertThat(cause.message()).contains("sub-window 0 is leased")
                                                                  .contains("sub-window 1: port " + (ROTATION_FIRST + SLOT_PORTS + 1)));
        } finally {
            PortWindows.release(ROTATION);
        }
    }

    /// The slot check is by pool extent: a cluster whose pool of 2N+extra slots runs past the sub-window is refused at start.
    @Test
    void cluster_poolRunningPastTheSubWindow_refusesToStart() {
        try (var lease = EmberPorts.lease(TEST, new Layout(3, 40, 80), _ -> 0).unwrap()) {
            var cluster = EmberCluster.emberCluster(3, lease.base(), lease.mgmtBase(), lease.appHttpBase(), "pool");

            cluster.adoptPortLease(lease).unwrap();
            cluster.withAdditionalNodeSlots(32).unwrap();

            var outcome = cluster.start().await();

            assertThat(outcome.isFailure()).isTrue();
            outcome.onFailure(cause -> assertThat(cause.message()).contains("run past its sub-window"));
        }
    }

    /// A previous JVM's closed connections leave TIME_WAIT on its window ports. The claim must not read that as busy: the nodes'
    /// servers bind through it (JDK default SO_REUSEADDR), so the probe binds the same way. Red when the TCP probe is made
    /// exclusive (`setReuseAddress(false)`).
    @Test
    void lease_afterAClosedConnectionLeftTimeWait_theSameSlotIsStillClaimable() throws Exception {
        var port = FIRST + 60;
        var server = new ServerSocket(port);
        var client = new Socket("127.0.0.1", port);
        var accepted = server.accept();

        accepted.close();
        client.close();
        server.close();

        assertThat(EmberPorts.lease(TEST, LAYOUT, _ -> 0).unwrap().base()).isEqualTo(FIRST);
    }

    /// Mutual exclusion between processes, and no stale lock: another JVM holds slot 0 while it lives, and slot 0 is claimable
    /// again, within a bound, as soon as it is killed with SIGKILL (measured at about 1 s on Linux; the bound is ten times that).
    @Test
    @Timeout(120)
    void lease_windowHeldByAnotherJvm_isNotGivenOutUntilThatProcessDies() throws Exception {
        var holder = startHolder();

        try (var out = new BufferedReader(new InputStreamReader(holder.getInputStream()))) {
            assertThat(heldLine(out)).as("the other JVM claims slot 0 first").isEqualTo("HELD " + FIRST);
            assertThat(EmberPorts.lease(TEST, LAYOUT, _ -> 0).unwrap().base())
                .as("both want slot 0; the one that does not hold the sentinel gets slot 1")
                .isEqualTo(FIRST + SLOT_PORTS);
            PortWindows.release(TEST);

            holder.destroyForcibly().waitFor();

            var startedAt = System.nanoTime();
            var reclaimed = EmberPorts.lease(TEST, LAYOUT, _ -> 0, KILL_RECLAIM_BOUND_MS);

            assertThat(reclaimed.unwrap().base()).as("after kill -9 the slot is free again: nothing stale").isEqualTo(FIRST);
            assertThat((System.nanoTime() - startedAt) / 1_000_000L).as("reclaimed within the bound").isLessThan(KILL_RECLAIM_BOUND_MS);
        } finally {
            holder.destroyForcibly();
        }
    }

    /// Exhaustion is loud and bounded: with every slot held by another JVM the claim waits its bound and then fails, naming each
    /// sentinel and saying it is held. It never falls back to a range the table does not own.
    @Test
    @Timeout(120)
    void lease_everySlotHeldByOtherJvms_waitsTheBoundThenFailsNamingEverySentinel() throws Exception {
        var first = startHolder();
        Process second = null;

        try (var firstOut = new BufferedReader(new InputStreamReader(first.getInputStream()))) {
            assertThat(heldLine(firstOut)).isEqualTo("HELD " + FIRST);
            // started only after the first announced, so the claim order is not a race between the two holders
            second = startHolder();

            var secondOut = new BufferedReader(new InputStreamReader(second.getInputStream()));

            assertThat(heldLine(secondOut)).as("the second holder walks past the held slot 0").isEqualTo("HELD " + (FIRST + SLOT_PORTS));

            var startedAt = System.nanoTime();
            var outcome = EmberPorts.lease(TEST, LAYOUT, _ -> 0, EXHAUSTION_WAIT_MS);
            var waitedMs = (System.nanoTime() - startedAt) / 1_000_000L;

            assertThat(outcome.isFailure()).isTrue();
            assertThat(waitedMs).as("it waited the bound before failing").isGreaterThanOrEqualTo(EXHAUSTION_WAIT_MS - 100L);
            outcome.onFailure(cause -> assertThat(cause.message())
                .contains("no free port window")
                .contains("sentinel " + SENTINEL_FIRST + " is HELD")
                .contains("sentinel " + (SENTINEL_FIRST + 1) + " is HELD"));
        } finally {
            first.destroyForcibly();
            if (second != null) {
                second.destroyForcibly();
            }
        }
    }

    /// The window table and the registry row agree: a window that moves without its row moving would hide it from the gate
    /// that keeps pinned tests out of it (`tools/check-test-ports.py`, see the row's "(dynamic" marker).
    @Test
    void defaultWindows_areTheRowRegisteredAsDynamicInTheAllocationTable() throws IOException {
        var table = Path.of(System.getProperty("basedir", "."), "..", "forge", "forge-tests", "src", "test", "resources", "TEST_PORT_ALLOCATION.md");
        var row = Files.readAllLines(table).stream().filter(line -> line.startsWith("| EmberPorts.DEFAULT_WINDOWS")).findFirst();

        assertThat(row).as("the table registers EmberPorts.DEFAULT_WINDOWS").isPresent();

        var cells = row.get().split("\\|");
        var base = Integer.parseInt(cells[2].trim());
        var maxOffset = Integer.parseInt(cells[4].trim());
        var windows = EmberPorts.DEFAULT_WINDOWS;
        var lastWindowPort = windows.first() + windows.slots() * windows.windowPorts() - 1;
        var lastSentinel = windows.sentinelFirst() + windows.slots() - 1;

        assertThat(base).isEqualTo(windows.first());
        assertThat(base + maxOffset).as("the row covers every window port and every sentinel").isGreaterThanOrEqualTo(Math.max(lastWindowPort, lastSentinel));
        assertThat(cells[1]).contains("(dynamic");
    }

    /// The holder's announcement; its log lines come first on the same stream.
    private static String heldLine(BufferedReader out) throws IOException {
        String line;

        do {
            line = out.readLine();
        } while (line != null && !line.startsWith("HELD "));

        return line;
    }

    private static Process startHolder() throws IOException {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                  "-cp",
                                  System.getProperty("java.class.path"),
                                  EmberPortsHolder.class.getName(),
                                  String.valueOf(FIRST),
                                  "2",
                                  String.valueOf(SENTINEL_FIRST)).redirectErrorStream(true).start();
    }

    private static boolean canBindTcp(int port, boolean reuse) throws IOException {
        try (var socket = new ServerSocket()) {
            socket.setReuseAddress(reuse);
            socket.bind(new InetSocketAddress(port));

            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
