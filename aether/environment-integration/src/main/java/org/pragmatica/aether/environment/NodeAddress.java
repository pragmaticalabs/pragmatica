// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.environment;

import org.pragmatica.lang.Option;


public record NodeAddress(String nodeId, String publicIp, Option<String> privateIp) {
    public static NodeAddress nodeAddress(String nodeId, String publicIp, Option<String> privateIp) {
        return new NodeAddress(nodeId, publicIp, privateIp);
    }
}
