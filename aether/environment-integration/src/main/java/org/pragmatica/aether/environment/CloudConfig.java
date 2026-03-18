/*
 *  Copyright (c) 2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.pragmatica.aether.environment;

import org.pragmatica.lang.Result;

import java.util.Map;

import static org.pragmatica.lang.Result.success;

/// Generic cloud provider configuration parsed from the [cloud] TOML section.
/// Uses string maps so the config layer doesn't depend on provider-specific types.
/// Each EnvironmentIntegrationFactory knows how to interpret these maps.
public record CloudConfig(String provider,
                           Map<String, String> credentials,
                           Map<String, String> compute,
                           Map<String, String> loadBalancer,
                           Map<String, String> discovery,
                           Map<String, String> secrets) {
    public static Result<CloudConfig> cloudConfig(String provider,
                                                   Map<String, String> credentials,
                                                   Map<String, String> compute) {
        return success(new CloudConfig(provider, Map.copyOf(credentials), Map.copyOf(compute),
                                       Map.of(), Map.of(), Map.of()));
    }

    public CloudConfig withLoadBalancer(Map<String, String> loadBalancer) {
        return new CloudConfig(provider, credentials, compute, Map.copyOf(loadBalancer), discovery, secrets);
    }

    public CloudConfig withDiscovery(Map<String, String> discovery) {
        return new CloudConfig(provider, credentials, compute, loadBalancer, Map.copyOf(discovery), secrets);
    }

    public CloudConfig withSecrets(Map<String, String> secrets) {
        return new CloudConfig(provider, credentials, compute, loadBalancer, discovery, Map.copyOf(secrets));
    }
}
