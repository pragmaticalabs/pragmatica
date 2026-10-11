// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.environment;

import java.util.List;
import java.util.Map;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

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
