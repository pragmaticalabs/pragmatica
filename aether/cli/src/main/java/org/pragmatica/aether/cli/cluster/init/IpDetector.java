// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster.init;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;


public sealed interface IpDetector {
    @SuppressWarnings("JBCT-EX-01")
    static List<String> nonLoopbackIPv4() {
        var result = new ArrayList<String>();

        try {
            var ifaces = NetworkInterface.getNetworkInterfaces();

            while (ifaces.hasMoreElements()) {
                var iface = ifaces.nextElement();

                if (iface.isLoopback() || !iface.isUp()) {
                    continue;
                }

                var addrs = iface.getInetAddresses();

                while (addrs.hasMoreElements()) {
                    var addr = addrs.nextElement();

                    if (addr instanceof Inet4Address ip4 && !ip4.isLoopbackAddress() && !ip4.isLinkLocalAddress()) {
                        result.add(ip4.getHostAddress());
                    }
                }
            }
        } catch (SocketException ignored) {}

        return List.copyOf(result);
    }

    static String suggestAdminCidr() {
        var addrs = nonLoopbackIPv4();

        return addrs.isEmpty()
               ? ""
               : addrs.getFirst() + "/32";
    }

    record unused() implements IpDetector {}
}
