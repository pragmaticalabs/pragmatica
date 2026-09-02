// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


public record ClusterConfig(Environment environment,
                            int nodes,
                            boolean tls,
                            PortsConfig ports,
                            int coreMax,
                            int maxNodes) {
    /// #298 — no fleet cap. Follows `coreMax`'s convention in this record: 0 means "unset", so an
    /// existing config that never mentions `max_nodes` provisions unbounded exactly as before. A
    /// default numeric cap would silently refuse provisioning on any cluster already larger than it.
    public static final int UNBOUNDED = 0;

    public static Result<ClusterConfig> clusterConfig(Environment environment,
                                                      int nodes,
                                                      boolean tls,
                                                      PortsConfig ports,
                                                      int coreMax,
                                                      int maxNodes) {
        return success(new ClusterConfig(environment, nodes, tls, ports, coreMax, maxNodes));
    }

    public static Result<ClusterConfig> clusterConfig(Environment environment,
                                                      int nodes,
                                                      boolean tls,
                                                      PortsConfig ports,
                                                      int coreMax) {
        return clusterConfig(environment, nodes, tls, ports, coreMax, UNBOUNDED);
    }

    public static Result<ClusterConfig> clusterConfig(Environment environment,
                                                      int nodes,
                                                      boolean tls,
                                                      PortsConfig ports) {
        return clusterConfig(environment, nodes, tls, ports, 0);
    }

    public static ClusterConfig clusterConfig(Environment env) {
        return clusterConfig(env, env.defaultNodes(), env.defaultTls(), PortsConfig.portsConfig(), 0).unwrap();
    }

    public ClusterConfig withNodes(int nodes) {
        return clusterConfig(environment, nodes, tls, ports, coreMax, maxNodes).unwrap();
    }

    public ClusterConfig withTls(boolean tls) {
        return clusterConfig(environment, nodes, tls, ports, coreMax, maxNodes).unwrap();
    }

    public ClusterConfig withPorts(PortsConfig ports) {
        return clusterConfig(environment, nodes, tls, ports, coreMax, maxNodes).unwrap();
    }

    public ClusterConfig withCoreMax(int coreMax) {
        return clusterConfig(environment, nodes, tls, ports, coreMax, maxNodes).unwrap();
    }

    public ClusterConfig withMaxNodes(int maxNodes) {
        return clusterConfig(environment, nodes, tls, ports, coreMax, maxNodes).unwrap();
    }
}
