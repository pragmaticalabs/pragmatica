// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster.init;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;

/// Local network interface enumeration. Used by the wizard to suggest a default
/// admin CIDR for the RESTRICTIVE firewall preset. Strictly offline — no
/// external HTTP calls. Returns IPv4 addresses suggested as `<ip>/32` candidates.
///
/// If multiple non-loopback IPv4 addresses are present (VPN, bridge, etc.), the
/// caller asks the operator to pick. If the lookup fails or returns nothing, the
/// caller falls back to manual entry.
public sealed interface IpDetector {

    /// All non-loopback IPv4 addresses on UP interfaces, in interface enumeration order.
    /// Empty list on lookup failure or if no qualifying address is found.
    @SuppressWarnings("JBCT-EX-01") static List<String> nonLoopbackIPv4() {
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
        } catch (SocketException ignored) {
            // best-effort — caller falls back to manual entry on empty list
        }
        return List.copyOf(result);
    }

    /// Suggest a `<ip>/32` CIDR for the restrictive preset's admin source.
    /// Returns the first non-loopback IPv4 address as `<ip>/32`, or empty string
    /// if none found.
    static String suggestAdminCidr() {
        var addrs = nonLoopbackIPv4();
        return addrs.isEmpty() ? "" : addrs.getFirst() + "/32";
    }

    record unused() implements IpDetector {}
}
