// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember.ports;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;


/// Bind-and-release probes for a port: a good guess, never a guarantee (the claim's locks are the guarantee against
/// concurrent claimants; this only detects a binder that does not use the locks).
public sealed interface BindProbe {
    /// True when every port of the block can be bound for TCP and UDP right now.
    static boolean blockFree(PortBlock block) {
        return block.ports()
                    .allMatch(port -> tcpFree(port) && udpFree(port));
    }

    /// The transport's bind failure, as the cluster start reports it.
    static boolean isBindCollision(String startFailure) {
        return startFailure.contains("Address already in use") || startFailure.contains("BindException");
    }

    static boolean tcpFree(int port) {
        return Result.lift(Causes::fromThrowable,
                           () -> new ServerSocket())
                     .flatMap(socket -> bindAndClose(socket, port))
                     .isSuccess();
    }

    static boolean udpFree(int port) {
        return Result.lift(Causes::fromThrowable,
                           () -> new DatagramSocket(null))
                     .flatMap(socket -> bindAndClose(socket, port))
                     .isSuccess();
    }

    private static Result<Unit> bindAndClose(ServerSocket socket, int port) {
        return Result.lift(Causes::fromThrowable,
                           () -> socket.setReuseAddress(false))
                     .flatMap(_ -> Result.lift(Causes::fromThrowable,
                                               () -> socket.bind(loopback(port))))
                     .onResultRun(() -> close(socket));
    }

    private static Result<Unit> bindAndClose(DatagramSocket socket, int port) {
        return Result.lift(Causes::fromThrowable,
                           () -> socket.setReuseAddress(false))
                     .flatMap(_ -> Result.lift(Causes::fromThrowable,
                                               () -> socket.bind(loopback(port))))
                     .onResultRun(socket::close);
    }

    @Contract
    private static void close(ServerSocket socket) {
        Result.lift(Causes::fromThrowable, socket::close).run(BindProbe::ignore, BindProbe::ignore);
    }

    @Contract
    private static void ignore(Cause cause) {}

    @Contract
    private static void ignore(Unit unit) {}

    private static InetSocketAddress loopback(int port) {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
    }

    record unused() implements BindProbe {}
}
