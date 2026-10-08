// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.pragmatica.aether.node.health.CoreSwimHealthDetector;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #2016: the cluster-port helper probes the protocols a node binds (UDP cluster, TCP cluster, UDP SWIM),
/// and no node test goes back to reserving its cluster port over TCP alone.
class ClusterTestPortsTest {
    private static final int SWIM = CoreSwimHealthDetector.SWIM_PORT_OFFSET;
    /// TCP-only: the port is for an HTTP/1 management server and nothing UDP ever binds it.
    private static final String TCP_ONLY_MANAGEMENT_TEST = "ManagementServerDhtCatchUpGaugeTest.java";

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

    private static boolean reservesWithTcpServerSocket(Path file) {
        try {
            return Files.readString(file).contains("ServerSocket(0)");
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
