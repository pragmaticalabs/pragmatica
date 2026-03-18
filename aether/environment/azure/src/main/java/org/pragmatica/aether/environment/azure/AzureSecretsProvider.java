package org.pragmatica.aether.environment.azure;

import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.SecretsProvider;
import org.pragmatica.cloud.azure.AzureClient;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;

/// Azure Key Vault implementation of the SecretsProvider SPI.
/// Resolves secrets from Azure Key Vault using path format: `{vaultName}/{secretName}`.
public record AzureSecretsProvider(AzureClient client) implements SecretsProvider {
    /// Factory method for creating an AzureSecretsProvider.
    public static Result<AzureSecretsProvider> azureSecretsProvider(AzureClient client) {
        return success(new AzureSecretsProvider(client));
    }

    @Override
    public Promise<String> resolveSecret(String secretPath) {
        return splitPath(secretPath).async()
                     .flatMap(this::fetchSecret)
                     .mapError(cause -> EnvironmentError.secretResolutionFailed(secretPath,
                                                                                new RuntimeException(cause.message())));
    }

    // --- Leaf: split secret path into vault name and secret name ---
    static Result<VaultAndSecret> splitPath(String path) {
        var slashIndex = path.indexOf('/');
        if (slashIndex <= 0 || slashIndex >= path.length() - 1) {
            return EnvironmentError.secretResolutionFailed(path,
                                                           new IllegalArgumentException("Path must be in format: vaultName/secretName"))
                                   .result();
        }
        return success(new VaultAndSecret(path.substring(0, slashIndex),
                                           path.substring(slashIndex + 1)));
    }

    // --- Leaf: fetch a secret from Key Vault ---
    private Promise<String> fetchSecret(VaultAndSecret vas) {
        return client.getSecret(vas.vaultName(), vas.secretName());
    }

    /// Internal pair of vault name and secret name.
    record VaultAndSecret(String vaultName, String secretName) {}
}
