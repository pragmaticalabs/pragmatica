// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config;

public record EndpointConfig(String host, int port, String username, String password) {
    public static EndpointConfig endpointConfig(String host, int port, String username, String password) {
        return new EndpointConfig(host, port, username, password);
    }

    @Override
    public String toString() {
        return "EndpointConfig[host=" + host + ", port=" + port + ", username=" + username + ", password=***]";
    }
}
