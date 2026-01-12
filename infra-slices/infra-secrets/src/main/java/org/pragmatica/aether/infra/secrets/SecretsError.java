package org.pragmatica.aether.infra.secrets;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;

/**
 * Error types for secrets manager operations.
 */
public sealed interface SecretsError extends Cause {
    /**
     * Secret does not exist.
     */
    record SecretNotFound(String name) implements SecretsError {
        @Override
        public String message() {
            return "Secret not found: " + name;
        }
    }

    /**
     * Secret already exists.
     */
    record SecretAlreadyExists(String name) implements SecretsError {
        @Override
        public String message() {
            return "Secret already exists: " + name;
        }
    }

    /**
     * Secret version not found.
     */
    record VersionNotFound(String name, int version) implements SecretsError {
        @Override
        public String message() {
            return "Version " + version + " not found for secret: " + name;
        }
    }

    /**
     * Invalid secret name.
     */
    record InvalidSecretName(String name, String reason) implements SecretsError {
        @Override
        public String message() {
            return "Invalid secret name '" + name + "': " + reason;
        }
    }

    /**
     * Invalid configuration.
     */
    record InvalidConfiguration(String reason) implements SecretsError {
        @Override
        public String message() {
            return "Invalid secrets configuration: " + reason;
        }
    }

    /**
     * Encryption/decryption failed.
     */
    record CryptoError(String operation, Option<Throwable> cause) implements SecretsError {
        @Override
        public String message() {
            return "Crypto operation failed: " + operation + cause.fold(() -> "", c -> " - " + c.getMessage());
        }
    }

    /**
     * Access denied to secret.
     */
    record AccessDenied(String name, String reason) implements SecretsError {
        @Override
        public String message() {
            return "Access denied to secret '" + name + "': " + reason;
        }
    }

    // Factory methods
    static SecretNotFound secretNotFound(String name) {
        return new SecretNotFound(name);
    }

    static SecretAlreadyExists secretAlreadyExists(String name) {
        return new SecretAlreadyExists(name);
    }

    static VersionNotFound versionNotFound(String name, int version) {
        return new VersionNotFound(name, version);
    }

    static InvalidSecretName invalidSecretName(String name, String reason) {
        return new InvalidSecretName(name, reason);
    }

    static InvalidConfiguration invalidConfiguration(String reason) {
        return new InvalidConfiguration(reason);
    }

    static CryptoError cryptoError(String operation) {
        return new CryptoError(operation, Option.none());
    }

    static CryptoError cryptoError(String operation, Throwable cause) {
        return new CryptoError(operation, Option.option(cause));
    }

    static AccessDenied accessDenied(String name, String reason) {
        return new AccessDenied(name, reason);
    }
}
