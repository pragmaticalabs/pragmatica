// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import org.pragmatica.lang.Option;


/// A provisioned node. `managementPort` is the node's own host-published management port when it is not the cluster-wide configured one (a
/// docker node, #2089); see [NodeAddress].
public record ProvisionedNode(String nodeId, String serverId, String publicIp, Option<Integer> managementPort) {
    public ProvisionedNode(String nodeId, String serverId, String publicIp) {
        this(nodeId, serverId, publicIp, Option.none());
    }

    public static ProvisionedNode provisionedNode(String nodeId, String serverId, String publicIp) {
        return new ProvisionedNode(nodeId, serverId, publicIp);
    }

    public static ProvisionedNode provisionedNode(String nodeId,
                                                  String serverId,
                                                  String publicIp,
                                                  Option<Integer> managementPort) {
        return new ProvisionedNode(nodeId, serverId, publicIp, managementPort);
    }
}
