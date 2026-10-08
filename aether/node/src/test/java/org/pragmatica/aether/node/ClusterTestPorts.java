// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.pragmatica.aether.node.health.CoreSwimHealthDetector;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;

/// #2016: cluster ports for node tests that boot a real [AetherNode].
///
/// A node binds QUIC on its cluster port and SWIM on `port + SWIM_PORT_OFFSET`, both UDP. The helper this
/// replaces reserved a TCP port through an ephemeral `ServerSocket` and handed it on: a free TCP port says nothing about the UDP
/// port, and nothing about the derived SWIM port, so `CoreSwimHealthDetector.start` lost the bind to a
/// concurrent module under the `-T 1C` reactor (`BindException` from the SWIM UDP transport).
///
/// The candidate comes from a `DatagramSocket(0)` (the protocol the node binds), and every port the node will
/// bind is probed: cluster UDP, cluster TCP, and SWIM UDP at the production offset. A port is never issued
/// twice in one JVM.
///
/// Residual, stated: probe and bind are still two steps, so another PROCESS can take the port in between.
/// The window is the time from this call to `AetherNode.start()`; it is narrowed by the full-set probe, not
/// closed. The node's cluster port must be known before the node exists, so "bind port 0 and read it back"
/// is not available to these tests.
public final class ClusterTestPorts {
    private static final int SWIM_OFFSET = CoreSwimHealthDetector.SWIM_PORT_OFFSET;
    private static final int MAX_PORT = 65_535;
    private static final int ATTEMPTS = 200;
    private static final Set<Integer> ISSUED = ConcurrentHashMap.newKeySet();

    private ClusterTestPorts() {}

    /// A cluster port whose cluster UDP, cluster TCP and SWIM UDP ports were all free at the moment of the call.
    public static int freeClusterPort() {
        return freeClusterPort(ClusterTestPorts::udpCandidate);
    }

    /// Candidate source injected so a test can offer an already-issued port and watch it be refused.
    static int freeClusterPort(IntSupplier candidates) {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            var candidate = candidates.getAsInt();

            if (candidate + SWIM_OFFSET <= MAX_PORT && isFreeClusterPort(candidate) && ISSUED.add(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("no cluster port with free UDP, TCP and SWIM UDP after " + ATTEMPTS + " attempts");
    }

    /// A port free on BOTH TCP and UDP, for a listener whose protocol (H1, H3, BOTH) the test does not fix. Not for a
    /// cluster port: that one also needs the SWIM port, see [#freeClusterPort].
    public static int freeTcpAndUdpPort() {
        return freeTcpAndUdpPort(ClusterTestPorts::udpCandidate);
    }

    static int freeTcpAndUdpPort(IntSupplier candidates) {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            var candidate = candidates.getAsInt();

            if (tcpFree(candidate) && udpFree(candidate) && ISSUED.add(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("no port free on both TCP and UDP after " + ATTEMPTS + " attempts");
    }

    /// True when every port a node on `clusterPort` binds is free right now.
    public static boolean isFreeClusterPort(int clusterPort) {
        return udpFree(clusterPort) && tcpFree(clusterPort) && udpFree(clusterPort + SWIM_OFFSET);
    }

    static boolean udpFree(int port) {
        try (var socket = new DatagramSocket(null)) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static boolean tcpFree(int port) {
        try (var socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static int udpCandidate() {
        try (var socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
