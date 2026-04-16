// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment.gcp;

import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.SecretsProvider;
import org.pragmatica.cloud.gcp.GcpClient;
import org.pragmatica.lang.Promise;


/// GCP Secret Manager implementation of the SecretsProvider SPI.
/// Resolves secrets by calling the GCP Secret Manager API via the GcpClient.
public record GcpSecretsProvider(GcpClient client) implements SecretsProvider {
    public static GcpSecretsProvider gcpSecretsProvider(GcpClient client) {
        return new GcpSecretsProvider(client);
    }

    @Override public Promise<String> resolveSecret(String secretPath) {
        return client.accessSecretVersion(secretPath)
                                         .mapError(cause -> EnvironmentError.secretResolutionFailed(secretPath,
                                                                                                    new RuntimeException(cause.message())));
    }
}
