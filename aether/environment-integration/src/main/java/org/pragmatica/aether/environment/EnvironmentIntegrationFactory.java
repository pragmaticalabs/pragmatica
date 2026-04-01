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

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.ServiceLoader;

/// SPI for creating EnvironmentIntegration instances from generic CloudConfig.
/// Each cloud provider module registers its factory via ServiceLoader.
/// The node bootstrap uses the provider name to select the correct factory.
public interface EnvironmentIntegrationFactory {
    /// The provider name this factory handles (e.g., "hetzner", "aws", "gcp", "azure").
    String providerName();

    /// Create an EnvironmentIntegration from the given cloud configuration.
    Result<EnvironmentIntegration> create(CloudConfig config);

    /// Look up a factory by provider name via ServiceLoader.
    static Option<EnvironmentIntegrationFactory> forProvider(String providerName) {
        return Option.from(ServiceLoader.load(EnvironmentIntegrationFactory.class).stream()
                                             .map(ServiceLoader.Provider::get)
                                             .filter(f -> f.providerName().equals(providerName))
                                             .findFirst());
    }

    /// Create an EnvironmentIntegration for the given CloudConfig by looking up the factory.
    static Result<EnvironmentIntegration> createFromConfig(CloudConfig config) {
        return forProvider(config.provider()).toResult(EnvironmentError.operationNotSupported("Unknown cloud provider: " + config.provider()))
                          .flatMap(factory -> factory.create(config));
    }
}
