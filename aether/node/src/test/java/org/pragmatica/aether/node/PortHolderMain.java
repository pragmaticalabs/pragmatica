// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;

import org.pragmatica.portbind.HeldPort;

/// #2731 fixture: a second JVM that allocates ports and then sits in the window between allocation and a node's bind
/// (it never binds them), until its stdin closes or it is killed.
///
/// `new <n>`: `n` cluster ports through [ClusterTestPorts]. `legacy`: one port the pre-#2731 way, a port-0 probe from [HeldPort]
/// that is released at once, which is the red control. The port-bind gate (#3801) checks where a socket is built (here, only in
/// `HeldPort`), not when it is closed, so it does not see this close: the control exists to be pick-then-close.
/// Prints `PORT <p>` per port, then `READY`.
public final class PortHolderMain {
    private PortHolderMain() {}

    public static void main(String[] args) throws IOException {
        if (args[0].equals("legacy")) {
            try (var probe = HeldPort.udp()) {
                System.out.println("PORT " + probe.getLocalPort());
            }
        } else {
            for (int i = 0, n = Integer.parseInt(args[1]); i < n; i++) {
                System.out.println("PORT " + ClusterTestPorts.freeClusterPort());
            }
        }
        System.out.println("READY");
        System.out.flush();
        while (System.in.read() >= 0) {
            // hold until the parent closes stdin or kills this process
        }
    }
}
