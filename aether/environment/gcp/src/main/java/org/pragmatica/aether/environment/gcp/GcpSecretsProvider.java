// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.environment.gcp;

import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.SecretsProvider;
import org.pragmatica.cloud.gcp.GcpClient;
import org.pragmatica.lang.Promise;


public record GcpSecretsProvider(GcpClient client) implements SecretsProvider {
    public static GcpSecretsProvider gcpSecretsProvider(GcpClient client) {
        return new GcpSecretsProvider(client);
    }

    @Override
    public Promise<String> resolveSecret(String secretPath) {
        return client.accessSecretVersion(secretPath)
                     .mapError(cause -> EnvironmentError.secretResolutionFailed(secretPath,
                                                                                new RuntimeException(cause.message())));
    }
}
