// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.environment;

public record ProvisionedNode(String nodeId, String serverId, String publicIp) {
    public static ProvisionedNode provisionedNode(String nodeId, String serverId, String publicIp) {
        return new ProvisionedNode(nodeId, serverId, publicIp);
    }
}
