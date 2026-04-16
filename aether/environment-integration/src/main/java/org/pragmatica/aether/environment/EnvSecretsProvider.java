// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;


/// SecretsProvider that reads secrets from environment variables.
/// Path conversion: `database/password` becomes env var `AETHER_SECRET_DATABASE_PASSWORD`.
public record EnvSecretsProvider() implements SecretsProvider {
    private static final String PREFIX = "AETHER_SECRET_";

    public static EnvSecretsProvider envSecretsProvider() {
        return new EnvSecretsProvider();
    }

    @Override public Promise<String> resolveSecret(String secretPath) {
        return Option.option(System.getenv(toEnvVarName(secretPath)))
                            .async(EnvironmentError.secretResolutionFailed(secretPath,
                                                                           new IllegalStateException("Environment variable not set")));
    }

    static String toEnvVarName(String path) {
        return PREFIX + path.replace('/', '_').toUpperCase();
    }
}
