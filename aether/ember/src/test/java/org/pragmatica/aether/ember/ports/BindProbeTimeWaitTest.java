// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember.ports;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;


/// The TCP probe binds with SO_REUSEADDR off, so a port whose last connection sits in TIME_WAIT reads as taken, as it
/// does for a starting cluster that binds without reuse. With reuse on (the `ServerSocket` default on Linux) such a port
/// reads free and a claim would hand out a block the bind then refuses (v3043 should-fix, mutant vb).
class BindProbeTimeWaitTest {
    @Test
    @Timeout(60)
    void tcpFree_portWithAConnectionInTimeWait_isNotFree() throws Exception {
        var port = leaveTimeWaitOnTheServerSide();
        var deadline = System.nanoTime() + 5_000_000_000L;

        while (BindProbe.tcpFree(port) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(BindProbe.tcpFree(port)).as("TIME_WAIT on port %d must read as not free", port).isFalse();
    }

    private static int leaveTimeWaitOnTheServerSide() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
            var accepted = server.accept();

            accepted.close();
            assertThat(client.getInputStream().read()).as("the client saw the server's FIN").isEqualTo(-1);
            return server.getLocalPort();
        }
    }
}
