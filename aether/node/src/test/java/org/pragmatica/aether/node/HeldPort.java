// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.net.DatagramSocket;
import java.net.SocketException;

/// The one place a node test builds a port-0 socket by itself (#1826): `FlakeScan`'s `PICK_THEN_REBIND` pass flags every other port-0
/// construction in this tree unless its line is marked `// port0-held: <reason>`. The socket comes back OPEN, bound to the port the
/// kernel chose, and holds that port until the caller closes it, so the port is safe to use for exactly as long as the socket is
/// open: `try (var held = HeldPort.udp()) { ... held.getLocalPort() ... }`. A port read here and used after the close is the
/// pick-then-rebind hazard (another process can take it first): take such a port from [ClusterTestPorts] instead.
final class HeldPort {
    private HeldPort() {}

    static DatagramSocket udp() throws SocketException {
        return new DatagramSocket(0);
    }
}
