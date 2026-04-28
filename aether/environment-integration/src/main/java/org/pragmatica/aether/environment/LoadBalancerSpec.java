// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.List;
import java.util.Map;

import static org.pragmatica.lang.Result.success;


public record LoadBalancerSpec(String name,
                               String algorithm,
                               List<ServicePort> servicePorts,
                               Option<String> region,
                               Map<String, String> tags) {
    public record ServicePort(String protocol, int listenPort, int destinationPort) {
        public static Result<ServicePort> servicePort(String protocol, int listenPort, int destinationPort) {
            return success(new ServicePort(protocol, listenPort, destinationPort));
        }
    }

    public static Result<LoadBalancerSpec> loadBalancerSpec(String name,
                                                            String algorithm,
                                                            List<ServicePort> servicePorts) {
        return success(new LoadBalancerSpec(name, algorithm, List.copyOf(servicePorts), Option.empty(), Map.of()));
    }
}
