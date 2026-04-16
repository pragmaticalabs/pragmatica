// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.ServiceLoader;


/// SPI for creating EnvironmentIntegration instances from generic CloudConfig.
/// Each cloud provider module registers its factory via ServiceLoader.
/// The node bootstrap uses the provider name to select the correct factory.
public interface EnvironmentIntegrationFactory {
    String providerName();
    Result<EnvironmentIntegration> create(CloudConfig config);

    static Option<EnvironmentIntegrationFactory> forProvider(String providerName) {
        return Option.from(ServiceLoader.load(EnvironmentIntegrationFactory.class).stream()
                                             .map(ServiceLoader.Provider::get)
                                             .filter(f -> f.providerName().equals(providerName))
                                             .findFirst());
    }

    static Result<EnvironmentIntegration> createFromConfig(CloudConfig config) {
        return forProvider(config.provider()).toResult(EnvironmentError.operationNotSupported("Unknown cloud provider: " + config.provider()))
                          .flatMap(factory -> factory.create(config));
    }
}
