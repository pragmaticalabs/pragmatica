package org.pragmatica.aether.infra.secrets;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.Result.success;

/**
 * Configuration for secrets manager.
 *
 * @param name              Service name for identification
 * @param encryptionEnabled Whether to encrypt secrets at rest
 * @param rotationPeriod    How often to rotate secrets (Optional)
 * @param maxVersions       Maximum number of versions to keep per secret
 */
public record SecretsConfig(String name,
                            boolean encryptionEnabled,
                            Option<TimeSpan> rotationPeriod,
                            int maxVersions) {
    private static final String DEFAULT_NAME = "default";
    private static final boolean DEFAULT_ENCRYPTION_ENABLED = true;
    private static final int DEFAULT_MAX_VERSIONS = 10;

    /**
     * Creates default configuration.
     */
    public static Result<SecretsConfig> secretsConfig() {
        return success(new SecretsConfig(DEFAULT_NAME, DEFAULT_ENCRYPTION_ENABLED, Option.none(), DEFAULT_MAX_VERSIONS));
    }

    /**
     * Creates configuration with specified name.
     */
    public static Result<SecretsConfig> secretsConfig(String name) {
        return validateName(name)
                           .map(n -> new SecretsConfig(n,
                                                       DEFAULT_ENCRYPTION_ENABLED,
                                                       Option.none(),
                                                       DEFAULT_MAX_VERSIONS));
    }

    /**
     * Creates configuration with specified name and encryption setting.
     */
    public static Result<SecretsConfig> secretsConfig(String name, boolean encryptionEnabled) {
        return validateName(name)
                           .map(n -> new SecretsConfig(n,
                                                       encryptionEnabled,
                                                       Option.none(),
                                                       DEFAULT_MAX_VERSIONS));
    }

    private static Result<String> validateName(String name) {
        return Option.option(name)
                     .filter(n -> !n.isBlank())
                     .map(String::trim)
                     .toResult(SecretsError.invalidConfiguration("Service name cannot be null or empty"));
    }

    /**
     * Creates a new configuration with the specified name.
     */
    public SecretsConfig withName(String name) {
        return new SecretsConfig(name, encryptionEnabled, rotationPeriod, maxVersions);
    }

    /**
     * Creates a new configuration with encryption enabled/disabled.
     */
    public SecretsConfig withEncryption(boolean enabled) {
        return new SecretsConfig(name, enabled, rotationPeriod, maxVersions);
    }

    /**
     * Creates a new configuration with the specified rotation period.
     */
    public SecretsConfig withRotationPeriod(TimeSpan period) {
        return new SecretsConfig(name, encryptionEnabled, Option.option(period), maxVersions);
    }

    /**
     * Creates a new configuration with the specified max versions.
     */
    public SecretsConfig withMaxVersions(int max) {
        return new SecretsConfig(name, encryptionEnabled, rotationPeriod, max);
    }
}
