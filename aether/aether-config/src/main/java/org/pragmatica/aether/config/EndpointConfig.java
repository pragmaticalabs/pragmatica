// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

public record EndpointConfig(String host, int port, String username, String password) {
    public static EndpointConfig endpointConfig(String host, int port, String username, String password) {
        return new EndpointConfig(host, port, username, password);
    }

    @Override public String toString() {
        return "EndpointConfig[host=" + host + ", port=" + port + ", username=" + username + ", password=***]";
    }
}
