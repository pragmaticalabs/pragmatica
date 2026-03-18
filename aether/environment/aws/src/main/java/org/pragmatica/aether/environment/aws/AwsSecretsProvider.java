package org.pragmatica.aether.environment.aws;

import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.SecretsProvider;
import org.pragmatica.cloud.aws.AwsClient;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;

/// AWS Secrets Manager implementation of the SecretsProvider SPI.
/// Resolves secrets by fetching them from AWS Secrets Manager using the secret path as the secret ID.
public record AwsSecretsProvider(AwsClient client) implements SecretsProvider {
    /// Factory method for creating an AwsSecretsProvider.
    public static Result<AwsSecretsProvider> awsSecretsProvider(AwsClient client) {
        return success(new AwsSecretsProvider(client));
    }

    @Override
    public Promise<String> resolveSecret(String secretPath) {
        return client.getSecretValue(secretPath)
                     .mapError(cause -> toSecretError(secretPath, cause));
    }

    // --- Leaf: map cause to secret resolution error ---
    private static EnvironmentError toSecretError(String path, Cause cause) {
        return EnvironmentError.secretResolutionFailed(path, new RuntimeException(cause.message()));
    }
}
