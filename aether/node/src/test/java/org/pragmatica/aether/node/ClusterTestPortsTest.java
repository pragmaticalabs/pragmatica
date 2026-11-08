// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.pragmatica.aether.node.health.CoreSwimHealthDetector;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// #2016 / #2731: the cluster-port helper issues from a window outside the ephemeral range under cross-JVM locks, probes the protocols a node binds (UDP cluster, TCP cluster, UDP SWIM),
/// and no node test goes back to reserving its cluster port over TCP alone.
class ClusterTestPortsTest {
    private static final int SWIM = CoreSwimHealthDetector.SWIM_PORT_OFFSET;
    /// TCP-only: the port is for an HTTP/1 management server and nothing UDP ever binds it.
    /// Reservation forms caught: `ServerSocket(0`, `ServerSocket(0, backlog, addr)`, any `ServerSocketChannel`, and an
    /// explicit `bind(new InetSocketAddress(..., 0))`. A bare `new InetSocketAddress(.., 0)` is NOT matched: it is also
    /// how a live `HttpServer.create(...)` binds (GitBackupRepositoryTest), which would be a false positive.
    /// KNOWN GAP (#2016): a port held in a variable (`new ServerSocket(port)`, `bind(addrOf(p))`) is not caught. Such a
    /// call is also how tests deliberately HOLD a taken port, so no text pattern separates the two without false
    /// positives; telling them apart needs data flow (the port came from a closed socket), which this scan cannot see.
    private static final java.util.regex.Pattern RESERVES = java.util.regex.Pattern.compile("ServerSocket\\(\\s*0|ServerSocketChannel|\\.bind\\(\\s*new (?:java\\.net\\.)?InetSocketAddress\\([^()]*(?:\\([^()]*\\))?[^()]*\\b0\\s*\\)\\s*\\)");
    private final List<int[]> reservations = new ArrayList<>();
    private static final String TCP_ONLY_MANAGEMENT_TEST = "ManagementServerDhtCatchUpGaugeTest.java";

    @AfterEach
    void dropLeftoverReservations() {
        reservations.forEach(ClusterTestPorts::unreserve);
    }

    @Test
    void issuedPort_hasFreeClusterUdpTcpAndSwimUdp() {
        var port = ClusterTestPorts.freeClusterPort();

        assertThat(ClusterTestPorts.udpFree(port)).as("cluster UDP " + port).isTrue();
        assertThat(ClusterTestPorts.tcpFree(port)).as("cluster TCP " + port).isTrue();
        assertThat(ClusterTestPorts.udpFree(port + SWIM)).as("SWIM UDP " + (port + SWIM)).isTrue();
    }

    @Test
    void issuedPorts_areDistinctWithinTheJvm() {
        var seen = new HashSet<Integer>();

        for (int i = 0; i < 50; i++) {
            assertThat(seen.add(ClusterTestPorts.freeClusterPort())).as("port issued twice").isTrue();
        }
    }

    /// Pins the uniqueness claim: a candidate source that keeps offering an already-issued port is refused until a new one appears.
    @Test
    void issuedPort_isNotIssuedAgainEvenWhenTheSourceOffersItAgain() {
        var first = ClusterTestPorts.freeClusterPort();
        var second = ClusterTestPorts.freeClusterPort();
        var offers = new ArrayDeque<>(List.of(first, first, first, second, second));
        var third = ClusterTestPorts.freeClusterPort(() -> offers.isEmpty() ? ClusterTestPorts.windowCandidate() : offers.poll());

        assertThat(third).as("only a never-issued port may come back").isNotIn(first, second);
    }

    /// #2731: the window lies outside the host's ephemeral range, so the kernel's autobind (any `bind(0)`, any outgoing
    /// connect) can never hand an issued port to another socket. The old helper drew from `DatagramSocket(0)`, i.e. from
    /// INSIDE that range, so this is red against it by construction.
    @Test
    void issuedPorts_lieInTheWindow_outsideTheEphemeralRange() {
        var window = ClusterTestPorts.WINDOW;
        var ephemeral = window.ephemeral();

        assertThat(window.size()).isGreaterThanOrEqualTo(ClusterTestPorts.MIN_WINDOW_PORTS);
        assertThat(window.first() > ephemeral.last() || window.last() < ephemeral.first()).as("window " + window + " vs ephemeral " + ephemeral)
                                                                                          .isTrue();
        assertThat(window.first()).as("above the fixed test ports").isGreaterThan(ClusterTestPorts.FIXED_TEST_PORTS_TOP);

        for (int i = 0; i < 50; i++) {
            var port = ClusterTestPorts.freeClusterPort();

            assertThat(window.contains(port) && window.contains(port + SWIM)).as("cluster " + port + " and SWIM in " + window)
                                                                                .isTrue();
            assertThat(port >= ephemeral.first() && port <= ephemeral.last()).as("cluster " + port + " ephemeral").isFalse();
            assertThat(port + SWIM >= ephemeral.first() && port + SWIM <= ephemeral.last()).as("SWIM ephemeral").isFalse();
        }
    }

    /// The window choice, on the ranges that matter: Linux default, macOS default, and a host with no room left.
    @Test
    void window_isTheLargerSpanOutsideTheEphemeralRange_orRefusesNamingTheRange() {
        var linux = ClusterTestPorts.Window.choose(new ClusterTestPorts.EphemeralRange(32_768, 60_999, "linux"));
        var mac = ClusterTestPorts.Window.choose(new ClusterTestPorts.EphemeralRange(49_152, 65_535, "mac"));

        assertThat(List.of(linux.first(), linux.last())).containsExactly(61_000, 65_535);
        assertThat(List.of(mac.first(), mac.last())).containsExactly(32_768, 49_151);
        assertThatThrownBy(() -> ClusterTestPorts.Window.choose(new ClusterTestPorts.EphemeralRange(1_024, 65_535, "wide")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("1024-65535 (wide)");
        assertThatThrownBy(() -> ClusterTestPorts.Window.choose(new ClusterTestPorts.EphemeralRange(32_768, 64_999, "narrow-top")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("32768-64999");
    }

    /// #2731 proof (non-cooperating holder): a candidate whose cluster UDP port a plain socket holds is skipped; once the
    /// socket closes, the SAME candidate is accepted, so the refusal came from the occupation and nothing else.
    @Test
    void freeClusterPort_skipsACandidateWhoseClusterUdpIsHeld_andAcceptsItOnceReleased() throws IOException {
        var taken = occupiableClusterCandidate(true);

        try (var held = new DatagramSocket(new InetSocketAddress(taken))) {
            var got = ClusterTestPorts.freeClusterPort(offering(taken));

            assertThat(got).as("a port with cluster UDP held must not be issued").isNotEqualTo(taken);
        }
        assertThat(ClusterTestPorts.freeClusterPort(offering(taken))).as("control: the same port, released").isEqualTo(taken);
    }

    @Test
    void freeClusterPort_skipsACandidateWhoseSwimUdpIsHeld_andAcceptsItOnceReleased() throws IOException {
        var taken = occupiableClusterCandidate(true);

        try (var held = new DatagramSocket(new InetSocketAddress(taken + SWIM))) {
            assertThat(ClusterTestPorts.freeClusterPort(offering(taken))).as("SWIM UDP held").isNotEqualTo(taken);
        }
        assertThat(ClusterTestPorts.freeClusterPort(offering(taken))).as("control: SWIM released").isEqualTo(taken);
    }

    /// The both-protocol helper must skip a candidate whose TCP side is taken, and one whose UDP side is taken.
    @Test
    void freeTcpAndUdpPort_skipsACandidateWithTcpTaken() throws IOException {
        var taken = occupiableClusterCandidate(false);

        try (var held = new ServerSocket()) {
            held.bind(new InetSocketAddress(taken));
            assertThat(ClusterTestPorts.freeTcpAndUdpPort(offering(taken))).as("TCP held").isNotEqualTo(taken);
        }
        assertThat(ClusterTestPorts.freeTcpAndUdpPort(offering(taken))).as("control: TCP released").isEqualTo(taken);
    }

    @Test
    void freeTcpAndUdpPort_skipsACandidateWithUdpTaken() throws IOException {
        var taken = occupiableClusterCandidate(false);

        try (var held = new DatagramSocket(new InetSocketAddress(taken))) {
            assertThat(ClusterTestPorts.freeTcpAndUdpPort(offering(taken))).as("UDP held").isNotEqualTo(taken);
        }
        assertThat(ClusterTestPorts.freeTcpAndUdpPort(offering(taken))).as("control: UDP released").isEqualTo(taken);
    }

    /// #2731 proof (cooperating allocators in two JVMs): a child JVM allocates ports and holds them WITHOUT binding, i.e.
    /// it sits in the window between allocation and a node's bind. The parent never issues any of them: neither by its own
    /// draws nor when its candidate source offers exactly the child's port. Once the child is killed (`kill -9`), the OS
    /// drops its locks and the parent obtains that port: no stale lock, and the port itself was usable all along.
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void portsHeldByAnotherJvm_areNeverIssued_untilThatJvmDies() throws Exception {
        var child = PortHolder.start("new", "100");

        try {
            var childPorts = child.ports();
            var parentPorts = new HashSet<Integer>();

            for (int i = 0; i < 100; i++) {
                parentPorts.add(ClusterTestPorts.freeClusterPort());
            }
            var claimedByBoth = new HashSet<>(parentPorts);

            claimedByBoth.retainAll(childPorts);
            assertThat(childPorts).hasSize(100);
            assertThat(claimedByBoth).as("issued to both JVMs").isEmpty();

            var victim = childPorts.getFirst();

            assertThatThrownBy(() -> ClusterTestPorts.freeClusterPort(() -> victim)).as("child's port " + victim + " offered to the parent")
                                                                                   .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> ClusterTestPorts.freeTcpAndUdpPort(() -> victim + SWIM)).as("child's SWIM port offered")
                                                                                          .isInstanceOf(IllegalStateException.class);
            child.kill();
            assertThat(ClusterTestPorts.freeClusterPort(() -> victim)).as("after the child died").isEqualTo(victim);
        } finally {
            child.kill();
        }
    }

    /// #2902: the reservation a control test relies on is a real cross-JVM lock. It is refused for a port another JVM holds, and for a port it
    /// took, a second claim on the same lock file overlaps (this JVM holds the lock), so no other allocator could take the port either.
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void reserve_isRefusedForAPortAnotherJvmHolds_andHoldsTheLockForThePortsItTook() throws Exception {
        var child = PortHolder.start("new", "5");

        try {
            var victim = child.ports().getFirst();
            var mine = occupiableClusterCandidate(false);

            assertThat(ClusterTestPorts.reserve(victim)).as("child's port " + victim + " is locked by another JVM").isFalse();
            // Opening and closing the lock file here drops this JVM's OS lock on it (POSIX), so `mine` is not used after the check.
            try (var channel = FileChannel.open(ClusterTestPorts.LOCK_DIR.resolve(mine + ".lock"), StandardOpenOption.WRITE)) {
                assertThatThrownBy(channel::tryLock).as("the lock on the reserved port " + mine + " is held").isInstanceOf(OverlappingFileLockException.class);
            }
        } finally {
            child.kill();
        }
    }

    /// RED CONTROL, the pre-#2731 allocator: a child JVM picks a port the old way (`DatagramSocket(0)` probe, released)
    /// and is still alive in the allocation-to-bind window, yet nothing holds the port. A third party binds it at once, and
    /// the child's node would then fail with BindFailed. The port also lies inside the kernel's ephemeral range, where any
    /// outgoing socket on the host can be handed it without asking.
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void legacyAllocator_leavesItsPortTakeableByAThirdParty() throws Exception {
        var child = PortHolder.start("legacy");

        try {
            var port = child.ports().getFirst();
            var ephemeral = ClusterTestPorts.WINDOW.ephemeral();

            assertThat(port).as("legacy candidate comes from the ephemeral range").isBetween(ephemeral.first(), ephemeral.last());
            try (var thirdParty = new DatagramSocket(new InetSocketAddress(port))) {
                assertThat(thirdParty.getLocalPort()).as("a third party took the legacy port").isEqualTo(port);
            }
        } finally {
            child.kill();
        }
    }

    /// Positive controls: each probe must read a port this test holds as taken, or "free" is vacuous.
    @Test
    void probe_seesHeldClusterUdp() throws IOException {
        try (var held = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            assertThat(ClusterTestPorts.isFreeClusterPort(held.getLocalPort())).isFalse();
        }
    }

    @Test
    void probe_seesHeldClusterTcp() throws IOException {
        try (var held = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            assertThat(ClusterTestPorts.isFreeClusterPort(held.getLocalPort())).isFalse();
        }
    }

    /// The derived port the old helper never looked at: cluster port and TCP free, only the SWIM UDP port taken.
    @Test
    void probe_seesHeldSwimUdpAtTheDerivedPort() throws IOException {
        var clusterPort = ClusterTestPorts.freeClusterPort();

        try (var held = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), clusterPort + SWIM))) {
            assertThat(held.getLocalPort()).isEqualTo(clusterPort + SWIM);
            assertThat(ClusterTestPorts.isFreeClusterPort(clusterPort)).isFalse();
        }
    }

    /// Tripwire for the class: a node test that reserves a cluster port over TCP and lets a node bind UDP on it.
    @Test
    void nodeTests_doNotReserveAPortWithTcpServerSocket() throws IOException {
        var testRoot = Path.of("src", "test", "java");

        assertThat(testRoot).as("run from the module directory").isDirectory();

        try (Stream<Path> files = Files.walk(testRoot)) {
            var offenders = files.filter(p -> p.toString().endsWith(".java"))
                                 .filter(p -> !p.getFileName().toString().equals(TCP_ONLY_MANAGEMENT_TEST))
                                 .filter(p -> !p.getFileName().toString().equals("ClusterTestPortsTest.java"))
                                 .filter(ClusterTestPortsTest::reservesWithTcpServerSocket)
                                 .map(Path::toString)
                                 .toList();

            assertThat(offenders).as("use ClusterTestPorts.freeClusterPort() (#2016)").isEmpty();
        }
    }

    /// A window port free right now on cluster UDP, cluster TCP and SWIM UDP and NOT held by this JVM: a test occupies it.
    /// #2902: the cross-JVM lock on it (and on its SWIM port, when `withSwim`) is taken first and kept through the whole test, so
    /// no other allocator can issue it between the probe and the control "released, accepted again" (the allocation attempt adopts
    /// the reservation, see [ClusterTestPorts#reserve]). A port another JVM holds is simply not chosen.
    private int occupiableClusterCandidate(boolean withSwim) {
        for (int attempt = 0; attempt < 200; attempt++) {
            var port = ClusterTestPorts.windowCandidate();
            var ports = withSwim ? new int[] {port, port + SWIM} : new int[] {port};

            if (ClusterTestPorts.WINDOW.contains(port + SWIM) && ClusterTestPorts.reserve(ports)) {
                if (ClusterTestPorts.isFreeClusterPort(port)) {
                    reservations.add(ports);
                    return port;
                }
                ClusterTestPorts.unreserve(ports);
            }
        }
        throw new IllegalStateException("no free window port to occupy");
    }

    /// Offers `port` first, then random window ports.
    private static IntSupplier offering(int port) {
        var offers = new ArrayDeque<>(List.of(port));

        return () -> offers.isEmpty() ? ClusterTestPorts.windowCandidate() : offers.poll();
    }

    /// A child JVM running [PortHolderMain] on this JVM's classpath.
    private record PortHolder(Process process, List<Integer> ports) {
        static PortHolder start(String... args) throws IOException {
            var command = new ArrayList<String>();

            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.add("-cp");
            command.add(System.getProperty("java.class.path"));
            command.add(PortHolderMain.class.getName());
            command.addAll(List.of(args));

            var process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            var ports = new ArrayList<Integer>();
            String line;

            while ((line = reader.readLine()) != null && !line.equals("READY")) {
                if (line.startsWith("PORT ")) {
                    ports.add(Integer.parseInt(line.substring(5).trim()));
                }
            }
            if (line == null) {
                process.destroyForcibly();
                throw new IllegalStateException("port holder exited before READY: " + command);
            }
            return new PortHolder(process, List.copyOf(ports));
        }

        void kill() throws InterruptedException {
            process.destroyForcibly();
            process.waitFor();
        }
    }

    private static boolean reservesWithTcpServerSocket(Path file) {
        try {
            return RESERVES.matcher(Files.readString(file)).find();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
