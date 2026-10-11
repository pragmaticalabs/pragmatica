// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.environment;

import org.pragmatica.lang.Option;


/// Where a node is reached from the operator's host. `managementPort` is present when the node's management API is NOT on the cluster-wide
/// configured port: a docker node publishes its management port on a per-node ephemeral HOST port, so the one configured port names none
/// of them (#2089). Absent means "the configured management port", which is what every cloud and ssh node uses.
public record NodeAddress(String nodeId, String publicIp, Option<String> privateIp, Option<Integer> managementPort) {
    public NodeAddress(String nodeId, String publicIp, Option<String> privateIp) {
        this(nodeId, publicIp, privateIp, Option.none());
    }

    public static NodeAddress nodeAddress(String nodeId, String publicIp, Option<String> privateIp) {
        return new NodeAddress(nodeId, publicIp, privateIp);
    }

    public static NodeAddress nodeAddress(String nodeId,
                                          String publicIp,
                                          Option<String> privateIp,
                                          Option<Integer> managementPort) {
        return new NodeAddress(nodeId, publicIp, privateIp, managementPort);
    }

    /// `host:port` of the management API: this node's own port when it has one, else `defaultManagementPort`.
    public String managementHostPort(int defaultManagementPort) {
        return urlHost(publicIp) + ":" + managementPort.or(defaultManagementPort);
    }

    /// The form a node's address is persisted in: the bare host, or `host:port` when the node carries its own management port.
    public String persisted() {
        return managementPort.map(port -> urlHost(publicIp) + ":" + port)
                             .or(publicIp);
    }

    /// A host as it appears before a `:port` or in a URL: an IPv6 literal is bracketed (`[::1]`), anything else is unchanged. This is the ONE
    /// place the persisted `host:port` form is produced, so [#fromPersisted] needs to accept only that form.
    public static String urlHost(String host) {
        return host.contains(":") && !host.startsWith("[")
               ? "[" + host + "]"
               : host;
    }

    /// Inverse of [#persisted]: a trailing `:digits` after a single colon is the node's own management port.
    public static NodeAddress fromPersisted(String nodeId, String persisted) {
        var close = persisted.indexOf("]:");

        if (persisted.startsWith("[") && close > 0 && persisted.substring(close + 2)
                                                               .chars()
                                                               .allMatch(Character::isDigit) && close + 2 < persisted.length()) {
            return nodeAddress(nodeId,
                               persisted.substring(1, close),
                               Option.none(),
                               Option.some(Integer.parseInt(persisted.substring(close + 2))));
        }

        var colon = persisted.lastIndexOf(':');
        var single = colon > 0 && persisted.indexOf(':') == colon;

        if (single && persisted.substring(colon + 1).chars().allMatch(Character::isDigit) && colon < persisted.length() - 1) {
            return nodeAddress(nodeId,
                               persisted.substring(0, colon),
                               Option.none(),
                               Option.some(Integer.parseInt(persisted.substring(colon + 1))));
        }

        return nodeAddress(nodeId, persisted, Option.none());
    }
}
