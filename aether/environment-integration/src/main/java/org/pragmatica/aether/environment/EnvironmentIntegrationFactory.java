// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.environment;

import java.util.ServiceLoader;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


public interface EnvironmentIntegrationFactory {
    String providerName();
    Result<EnvironmentIntegration> create(CloudConfig config);

    static Option<EnvironmentIntegrationFactory> forProvider(String providerName) {
        return Option.from(ServiceLoader.load(EnvironmentIntegrationFactory.class)
                                        .stream()
                                        .map(ServiceLoader.Provider::get)
                                        .filter(f -> f.providerName()
                                                      .equals(providerName))
                                        .findFirst());
    }

    static Result<EnvironmentIntegration> createFromConfig(CloudConfig config) {
        return forProvider(config.provider()).toResult(EnvironmentError.operationNotSupported("Unknown cloud provider: " + config.provider()))
                          .flatMap(factory -> factory.create(config));
    }
}
