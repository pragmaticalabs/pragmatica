// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment.aws;

import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.SecretsProvider;
import org.pragmatica.cloud.aws.AwsClient;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


public record AwsSecretsProvider(AwsClient client) implements SecretsProvider {
    public static Result<AwsSecretsProvider> awsSecretsProvider(AwsClient client) {
        return success(new AwsSecretsProvider(client));
    }

    @Override
    public Promise<String> resolveSecret(String secretPath) {
        return client.getSecretValue(secretPath)
                     .mapError(cause -> toSecretError(secretPath, cause));
    }

    private static EnvironmentError toSecretError(String path, Cause cause) {
        return EnvironmentError.secretResolutionFailed(path, new RuntimeException(cause.message()));
    }
}
