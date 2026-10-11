// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config;

import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


public record PortsConfig(int management, int cluster, HttpProtocol managementHttpProtocol) {
    public static final int DEFAULT_MANAGEMENT_PORT = 8080;
    public static final int DEFAULT_CLUSTER_PORT = 8090;

    public static Result<PortsConfig> portsConfig(int management, int cluster, HttpProtocol managementHttpProtocol) {
        return success(new PortsConfig(management, cluster, managementHttpProtocol));
    }

    public static Result<PortsConfig> portsConfig(int management, int cluster) {
        return success(new PortsConfig(management, cluster, HttpProtocol.H1));
    }

    public static PortsConfig portsConfig() {
        return portsConfig(DEFAULT_MANAGEMENT_PORT, DEFAULT_CLUSTER_PORT, HttpProtocol.H1).unwrap();
    }

    public int managementPortFor(int nodeIndex) {
        return management + nodeIndex;
    }

    public int clusterPortFor(int nodeIndex) {
        return cluster + nodeIndex;
    }
}
