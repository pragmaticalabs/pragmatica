package org.pragmatica.aether.environment;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// SPI for resolving secrets from external backends (Vault, AWS Secrets Manager, etc.).
/// Used internally by the resource provisioning pipeline to resolve `${secrets:...}` placeholders
/// in configuration before passing to ResourceFactory implementations.
///
/// Application code never interacts with this interface directly.
public interface SecretsProvider {
    Promise<String> resolveSecret(String secretPath);

    /// Resolve a secret with metadata (version, expiry).
    /// Default wraps the plain value with empty metadata.
    default Promise<SecretValue> resolveSecretWithMetadata(String secretPath) {
        return resolveSecret(secretPath).map(value -> new SecretValue(value, Option.empty(), Option.empty()));
    }

    /// Resolve multiple secrets in a single call (batch).
    /// Implementations may optimize with batch APIs. Default resolves sequentially via allOf.
    default Promise<Map<String, String>> resolveSecrets(List<String> secretPaths) {
        var futures = secretPaths.stream().map(path -> resolveSecret(path).map(value -> Map.entry(path, value)))
                                        .toList();
        return Promise.allOf(futures).map(SecretsProvider::collectEntries);
    }

    /// Register a callback for secret rotation events. Default: no-op.
    default Promise<Unit> watchRotation(String secretPath, SecretRotationCallback callback) {
        return Promise.success(Unit.unit());
    }

    private static Map<String, String> collectEntries(List<Result<Map.Entry<String, String>>> results) {
        var map = new HashMap<String, String>();
        for ( var result : results) {
        result.onSuccess(entry -> map.put(entry.getKey(), entry.getValue()));}
        return Map.copyOf(map);
    }
}
