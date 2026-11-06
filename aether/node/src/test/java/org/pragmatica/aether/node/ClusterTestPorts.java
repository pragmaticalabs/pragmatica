// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.pragmatica.aether.node.health.CoreSwimHealthDetector;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntSupplier;

/// #2016 / #2731: ports for node tests that boot a real [AetherNode].
///
/// A node binds QUIC on its cluster port and SWIM on `port + SWIM_PORT_OFFSET`, both UDP, on the wildcard address.
/// The port is configuration, known to every peer before any node boots, so "bind port 0 and read it back" is not
/// available here; the port has to be chosen first and bound later. #2731: the old helper drew candidates from
/// `DatagramSocket(0)`, i.e. the kernel ephemeral range, and released them, so every outgoing socket on the host
/// competed for the port between the probe and the node's bind.
///
/// What an issued port carries, and the mechanism behind each part:
/// - **The kernel never autobinds it.** Candidates come from a WINDOW outside the host's ephemeral range, read at class
///   init (Linux `/proc/sys/net/ipv4/ip_local_port_range`, macOS `sysctl net.inet.ip.portrange.first/last`). Autobind
///   (`bind(0)`, an outgoing connect) draws only from that range. The window is the larger free span above the range's
///   top or below its bottom, clear of the fixed and probed test ports below [#FIXED_TEST_PORTS_TOP]. No window of at
///   least [#MIN_WINDOW_PORTS] ports, or an unreadable range: class init FAILS, naming the range. It never falls back.
/// - **No other cooperating allocator on the host issues it** while this JVM lives: every port the node binds is held
///   under an exclusive OS file lock, `/tmp/aether-test-ports/<port>.lock`, taken with `FileChannel.tryLock` and never
///   released. The OS drops the lock when the process dies (also on `kill -9`), so there is no stale-lock cleanup.
///   POSIX trap: closing ANY descriptor of a locked file drops this process's lock on it, so a file is opened at most
///   once per JVM (the `HELD` map, under the class monitor).
/// - **No socket held it at issue time:** cluster UDP, cluster TCP and SWIM UDP are probed on the wildcard address,
///   the address the node binds.
///
/// Residual, stated: a process that does not use the lock directory and binds an explicit port inside the window can
/// still take it (none in the repo: the #2731 grep of 61000-65535 found only range-validation bounds and config-parse
/// literals). The lock directory is a fixed path, not `java.io.tmpdir`, so forks with a redirected tmpdir still meet.
public final class ClusterTestPorts {
    private static final int SWIM_OFFSET = CoreSwimHealthDetector.SWIM_PORT_OFFSET;
    private static final int MAX_PORT = 65_535;
    private static final int ATTEMPTS = 200;
    /// Everything up to the Linux ephemeral floor belongs to fixed and probed test ports: Ember's fixed span
    /// (~1030-32100, `TEST_PORT_ALLOCATION.md`), its probed pools and scans, the Forge preflight band, and
    /// `HierarchicalLoadedMovementTest`'s 32400-32600+.
    static final int FIXED_TEST_PORTS_TOP = 32_767;
    static final int MIN_WINDOW_PORTS = 1_000;
    static final Path LOCK_DIR = Path.of("/tmp/aether-test-ports");
    static final Window WINDOW = Window.choose(EphemeralRange.ofThisHost());
    private static final Map<Integer, FileChannel> HELD = new ConcurrentHashMap<>();

    static {
        prepareLockDir();
        System.err.println("ClusterTestPorts (#2731): ephemeral range " + WINDOW.ephemeral()
                           + "; issuing from " + WINDOW.first() + "-" + WINDOW.last()
                           + " (" + WINDOW.size() + " ports); locks under " + LOCK_DIR);
    }

    private ClusterTestPorts() {}

    /// The host's ephemeral range and where it was read from.
    record EphemeralRange(int first, int last, String source) {
        static EphemeralRange ofThisHost() {
            var linux = Path.of("/proc/sys/net/ipv4/ip_local_port_range");

            if (Files.isReadable(linux)) {
                return parse(read(linux), linux.toString());
            }
            if (System.getProperty("os.name", "").toLowerCase().contains("mac")) {
                return parse(sysctl("net.inet.ip.portrange.first", "net.inet.ip.portrange.last"),
                             "sysctl net.inet.ip.portrange.first/last");
            }
            throw new IllegalStateException("#2731: cannot read the ephemeral port range on " + System.getProperty("os.name")
                                            + " (no " + linux + ", not macOS); refusing to issue test ports");
        }

        static EphemeralRange parse(String text, String source) {
            var parts = text.trim().split("\\s+");

            if (parts.length != 2) {
                throw new IllegalStateException("#2731: unreadable ephemeral range '" + text + "' from " + source);
            }
            return new EphemeralRange(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), source);
        }

        @Override
        public String toString() {
            return first + "-" + last + " (" + source + ")";
        }

        /// Line-wise, never `Files.readString`: procfs reports size 0, and the size-driven read returned one byte ("3")
        /// on cloudbb-d1 (JDK 25).
        private static String read(Path path) {
            try {
                return String.join(" ", Files.readAllLines(path));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private static String sysctl(String... names) {
            var command = new java.util.ArrayList<>(java.util.List.of("sysctl", "-n"));

            command.addAll(java.util.List.of(names));
            try {
                var process = new ProcessBuilder(command).redirectErrorStream(true).start();
                var out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

                if (process.waitFor() != 0) {
                    throw new IllegalStateException("#2731: " + command + " failed: " + out);
                }
                return out;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    /// The port window issued from: inclusive bounds, outside `ephemeral` and above [#FIXED_TEST_PORTS_TOP].
    record Window(int first, int last, EphemeralRange ephemeral) {
        static Window choose(EphemeralRange ephemeral) {
            var below = new Window(FIXED_TEST_PORTS_TOP + 1, ephemeral.first() - 1, ephemeral);
            var above = new Window(Math.max(ephemeral.last() + 1, FIXED_TEST_PORTS_TOP + 1), MAX_PORT, ephemeral);
            var larger = below.size() >= above.size() ? below : above;

            if (larger.size() < MIN_WINDOW_PORTS) {
                throw new IllegalStateException("#2731: no window of " + MIN_WINDOW_PORTS + " test ports outside the ephemeral range "
                                                + ephemeral + " and above the fixed test ports (<= " + FIXED_TEST_PORTS_TOP
                                                + "): below " + below.size() + ", above " + above.size());
            }
            return larger;
        }

        int size() {
            return Math.max(0, last - first + 1);
        }

        boolean contains(int port) {
            return port >= first && port <= last;
        }
    }

    /// A cluster port whose cluster UDP, cluster TCP and SWIM UDP ports are locked for this JVM and were free at the call.
    public static int freeClusterPort() {
        return freeClusterPort(ClusterTestPorts::windowCandidate);
    }

    /// Candidate source injected so a test can offer a taken or already-issued port and watch it be refused.
    static int freeClusterPort(IntSupplier candidates) {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            var candidate = candidates.getAsInt();

            if (WINDOW.contains(candidate) && WINDOW.contains(candidate + SWIM_OFFSET) && claimAll(candidate, candidate + SWIM_OFFSET)) {
                return candidate;
            }
        }
        throw new IllegalStateException("no cluster port with free UDP, TCP and SWIM UDP in " + WINDOW.first() + "-"
                                        + WINDOW.last() + " after " + ATTEMPTS + " attempts (locks: " + LOCK_DIR + ")");
    }

    /// A port free on BOTH TCP and UDP, for a listener whose protocol (H1, H3, BOTH) the test does not fix. Not for a
    /// cluster port: that one also needs the SWIM port, see [#freeClusterPort].
    public static int freeTcpAndUdpPort() {
        return freeTcpAndUdpPort(ClusterTestPorts::windowCandidate);
    }

    static int freeTcpAndUdpPort(IntSupplier candidates) {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            var candidate = candidates.getAsInt();

            if (WINDOW.contains(candidate) && claimAll(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("no port free on both TCP and UDP in " + WINDOW.first() + "-" + WINDOW.last()
                                        + " after " + ATTEMPTS + " attempts (locks: " + LOCK_DIR + ")");
    }

    /// True when every port a node on `clusterPort` binds is free right now.
    public static boolean isFreeClusterPort(int clusterPort) {
        return udpFree(clusterPort) && tcpFree(clusterPort) && udpFree(clusterPort + SWIM_OFFSET);
    }

    static boolean udpFree(int port) {
        try (var socket = new DatagramSocket(null)) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static boolean tcpFree(int port) {
        try (var socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /// A random port in the window; NOT claimed. Tests use it to find a port to occupy.
    static int windowCandidate() {
        return ThreadLocalRandom.current().nextInt(WINDOW.first(), WINDOW.last() + 1);
    }

    /// Locks every port in `ports`, then probes them; on any refusal, releases what this call locked.
    private static synchronized boolean claimAll(int first, int... rest) {
        var ports = new int[rest.length + 1];

        ports[0] = first;
        System.arraycopy(rest, 0, ports, 1, rest.length);
        for (int i = 0; i < ports.length; i++) {
            if (!lock(ports[i])) {
                releaseFirst(ports, i);
                return false;
            }
        }
        if (allFree(ports)) {
            return true;
        }
        releaseFirst(ports, ports.length);
        return false;
    }

    private static boolean allFree(int[] ports) {
        if (!isFreeOnBothProtocols(ports[0])) {
            return false;
        }
        for (int i = 1; i < ports.length; i++) {
            if (!udpFree(ports[i])) {
                return false;
            }
        }
        return true;
    }

    private static boolean isFreeOnBothProtocols(int port) {
        return udpFree(port) && tcpFree(port);
    }

    private static void releaseFirst(int[] ports, int count) {
        for (int i = 0; i < count; i++) {
            closeQuietly(HELD.remove(ports[i]));
        }
    }

    /// Caller holds the class monitor. Opens the lock file only when this JVM holds no descriptor for it (POSIX trap).
    private static boolean lock(int port) {
        if (HELD.containsKey(port)) {
            return false;
        }
        FileChannel channel;
        try {
            channel = FileChannel.open(LOCK_DIR.resolve(port + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        } catch (IOException e) {
            return false;  // e.g. a lock file another user owns: read as taken, which is the safe answer
        }
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (IOException | OverlappingFileLockException e) {
            lock = null;
        }
        if (lock == null) {
            closeQuietly(channel);
            return false;
        }
        HELD.put(port, channel);
        return true;
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException e) {
            // closing releases the lock; nothing else to do
        }
    }

    private static void prepareLockDir() {
        try {
            Files.createDirectories(LOCK_DIR);
        } catch (IOException e) {
            throw new UncheckedIOException("#2731: cannot create the test-port lock directory " + LOCK_DIR, e);
        }
        try {
            Files.setPosixFilePermissions(LOCK_DIR, PosixFilePermissions.fromString("rwxrwxrwx"));
        } catch (IOException | UnsupportedOperationException e) {
            // another user created it, or no POSIX attributes: locking still works for whoever can write there
        }
    }
}
