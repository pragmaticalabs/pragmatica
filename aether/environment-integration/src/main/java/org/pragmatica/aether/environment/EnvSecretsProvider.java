// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.environment;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;


public record EnvSecretsProvider() implements SecretsProvider {
    private static final String PREFIX = "AETHER_SECRET_";

    public static EnvSecretsProvider envSecretsProvider() {
        return new EnvSecretsProvider();
    }

    /// The failure names the variable the operator has to set, not only the path it was derived
    /// from (#904).
    @Override
    public Promise<String> resolveSecret(String secretPath) {
        var envVarName = toEnvVarName(secretPath);

        return Option.option(System.getenv(envVarName)).async(EnvironmentError.secretResolutionFailed(secretPath,
                                                                                                      new IllegalStateException("environment variable " + envVarName
                                                                                                                               + " is not set")));
    }

    static String toEnvVarName(String path) {
        return PREFIX + path.replace('/', '_')
                            .toUpperCase();
    }
}
