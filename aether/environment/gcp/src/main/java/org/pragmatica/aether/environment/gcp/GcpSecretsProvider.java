package org.pragmatica.aether.environment.gcp;

import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.SecretsProvider;
import org.pragmatica.cloud.gcp.GcpClient;
import org.pragmatica.lang.Promise;

/// GCP Secret Manager implementation of the SecretsProvider SPI.
/// Resolves secrets by calling the GCP Secret Manager API via the GcpClient.
public record GcpSecretsProvider(GcpClient client) implements SecretsProvider {

    /// Factory method for creating a GcpSecretsProvider.
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
