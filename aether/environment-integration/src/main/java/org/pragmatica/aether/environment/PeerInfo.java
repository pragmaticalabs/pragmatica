// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import org.pragmatica.lang.Result;

import java.util.Map;

import static org.pragmatica.lang.Result.success;


public record PeerInfo(String host, int port, Map<String, String> metadata) {
    public static Result<PeerInfo> peerInfo(String host, int port, Map<String, String> metadata) {
        return success(new PeerInfo(host, port, Map.copyOf(metadata)));
    }

    public static Result<PeerInfo> peerInfo(String host, int port) {
        return peerInfo(host, port, Map.of());
    }
}
