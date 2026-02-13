package org.pragmatica.aether.environment;

import org.pragmatica.lang.Promise;

/// SPI for resolving secrets from external backends (Vault, AWS Secrets Manager, etc.).
/// Used internally by the resource provisioning pipeline to resolve `${secrets:...}` placeholders
/// in configuration before passing to ResourceFactory implementations.
///
/// Application code never interacts with this interface directly.
public interface SecretsProvider {
    Promise<String> resolveSecret(String secretPath);
}
