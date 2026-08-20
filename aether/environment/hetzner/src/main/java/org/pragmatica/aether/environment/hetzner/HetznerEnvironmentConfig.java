// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment.hetzner;

import java.util.List;

import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.cloud.hetzner.HetznerConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;


public record HetznerEnvironmentConfig(HetznerConfig hetznerConfig,
                                       String serverType,
                                       String image,
                                       String region,
                                       List<Long> sshKeyIds,
                                       List<Long> networkIds,
                                       List<Long> firewallIds,
                                       String userData,
                                       Option<HetznerLbConfig> loadBalancer,
                                       Option<ClusterName> clusterName,
                                       Option<Long> selfServerId,
                                       long discoveryPollIntervalMs) {
    private static final long DEFAULT_POLL_INTERVAL_MS = 30_000L;

    public record HetznerLbConfig(long loadBalancerId, int destinationPort) {
        public static Result<HetznerLbConfig> hetznerLbConfig(long loadBalancerId, int destinationPort) {
            return success(new HetznerLbConfig(loadBalancerId, destinationPort));
        }
    }

    public static Result<HetznerEnvironmentConfig> hetznerEnvironmentConfig(HetznerConfig hetznerConfig,
                                                                            String serverType,
                                                                            String image,
                                                                            String region,
                                                                            List<Long> sshKeyIds,
                                                                            List<Long> networkIds,
                                                                            List<Long> firewallIds,
                                                                            String userData) {
        return success(new HetznerEnvironmentConfig(hetznerConfig,
                                                    serverType,
                                                    image,
                                                    region,
                                                    List.copyOf(sshKeyIds),
                                                    List.copyOf(networkIds),
                                                    List.copyOf(firewallIds),
                                                    userData,
                                                    Option.empty(),
                                                    Option.empty(),
                                                    Option.empty(),
                                                    DEFAULT_POLL_INTERVAL_MS));
    }

    public static Result<HetznerEnvironmentConfig> hetznerEnvironmentConfig(HetznerConfig hetznerConfig,
                                                                            String serverType,
                                                                            String image,
                                                                            String region,
                                                                            List<Long> sshKeyIds,
                                                                            List<Long> networkIds,
                                                                            List<Long> firewallIds,
                                                                            String userData,
                                                                            HetznerLbConfig loadBalancer) {
        return success(new HetznerEnvironmentConfig(hetznerConfig,
                                                    serverType,
                                                    image,
                                                    region,
                                                    List.copyOf(sshKeyIds),
                                                    List.copyOf(networkIds),
                                                    List.copyOf(firewallIds),
                                                    userData,
                                                    some(loadBalancer),
                                                    Option.empty(),
                                                    Option.empty(),
                                                    DEFAULT_POLL_INTERVAL_MS));
    }

    public HetznerEnvironmentConfig withDiscovery(ClusterName clusterLabel) {
        return new HetznerEnvironmentConfig(hetznerConfig,
                                            serverType,
                                            image,
                                            region,
                                            sshKeyIds,
                                            networkIds,
                                            firewallIds,
                                            userData,
                                            loadBalancer,
                                            some(clusterLabel),
                                            selfServerId,
                                            discoveryPollIntervalMs);
    }

    public HetznerEnvironmentConfig withSelfServerId(long serverId) {
        return new HetznerEnvironmentConfig(hetznerConfig,
                                            serverType,
                                            image,
                                            region,
                                            sshKeyIds,
                                            networkIds,
                                            firewallIds,
                                            userData,
                                            loadBalancer,
                                            clusterName,
                                            some(serverId),
                                            discoveryPollIntervalMs);
    }

    public HetznerEnvironmentConfig withDiscoveryPollInterval(long intervalMs) {
        return new HetznerEnvironmentConfig(hetznerConfig,
                                            serverType,
                                            image,
                                            region,
                                            sshKeyIds,
                                            networkIds,
                                            firewallIds,
                                            userData,
                                            loadBalancer,
                                            clusterName,
                                            selfServerId,
                                            intervalMs);
    }
}
