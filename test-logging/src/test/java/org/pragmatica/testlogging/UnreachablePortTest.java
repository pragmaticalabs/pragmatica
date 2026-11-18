// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.testlogging;

import java.io.IOException;
import java.net.BindException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// The hold is what makes "unreachable" true by construction (#3319, #3321): the connect is refused, and no rival can
/// take the port while it is held. Linux only: elsewhere the helper releases the port and promises nothing.
class UnreachablePortTest {
    private static void onLinux() {
        assumeTrue(System.getProperty("os.name").startsWith("Linux"), "the hold exists on Linux only");
    }

    @Test
    void hold_connectIsRefused() throws IOException {
        onLinux();

        try (var unreachable = UnreachablePort.hold(); var client = new Socket()) {
            assertThatThrownBy(() -> client.connect(new InetSocketAddress("127.0.0.1", unreachable.port()), 2_000))
                .isInstanceOf(ConnectException.class);
        }
    }

    @Test
    void hold_rivalCannotListenOnTheHeldPort() throws IOException {
        onLinux();

        try (var unreachable = UnreachablePort.hold(); var rival = new ServerSocket()) {
            rival.setReuseAddress(true);

            assertThatThrownBy(() -> rival.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), unreachable.port())))
                .isInstanceOf(BindException.class);
        }
    }

    @Test
    void hold_concurrentHoldsGetDistinctPorts() throws IOException {
        onLinux();

        try (var first = UnreachablePort.hold(); var second = UnreachablePort.hold()) {
            assertThat(first.port()).isNotEqualTo(second.port());
        }
    }

    @Test
    void close_releasesTheHeldPort() throws IOException {
        onLinux();

        int port;

        try (var unreachable = UnreachablePort.hold()) {
            port = unreachable.port();
        }

        try (var afterwards = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
            assertThat(afterwards.getLocalPort()).isEqualTo(port);
        }
    }
}
