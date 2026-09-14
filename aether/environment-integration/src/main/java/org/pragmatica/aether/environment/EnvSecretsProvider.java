// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
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
