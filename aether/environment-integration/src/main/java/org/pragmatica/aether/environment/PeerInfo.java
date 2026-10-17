// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.environment;

import java.util.Map;

import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


public record PeerInfo(String host, int port, Map<String, String> metadata) {
    public static Result<PeerInfo> peerInfo(String host, int port, Map<String, String> metadata) {
        return success(new PeerInfo(host, port, Map.copyOf(metadata)));
    }

    public static Result<PeerInfo> peerInfo(String host, int port) {
        return peerInfo(host, port, Map.of());
    }
}
