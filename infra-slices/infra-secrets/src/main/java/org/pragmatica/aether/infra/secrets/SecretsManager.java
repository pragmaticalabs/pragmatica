package org.pragmatica.aether.infra.secrets;

import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Secrets manager providing secure storage for sensitive data.
 * Supports versioning, rotation, and metadata-only listing.
 */
public interface SecretsManager extends Slice {
    // ========== Basic Operations ==========
    /**
     * Creates a new secret.
     *
     * @param name  Secret name
     * @param value Secret value
     * @return Metadata of the created secret
     */
    Promise<SecretMetadata> createSecret(String name, SecretValue value);

    /**
     * Creates a new secret with tags.
     *
     * @param name  Secret name
     * @param value Secret value
     * @param tags  Key-value tags for categorization
     * @return Metadata of the created secret
     */
    Promise<SecretMetadata> createSecret(String name, SecretValue value, Map<String, String> tags);

    /**
     * Gets the current value of a secret.
     *
     * @param name Secret name
     * @return The secret value
     */
    Promise<SecretValue> getSecret(String name);

    /**
     * Gets a specific version of a secret.
     *
     * @param name    Secret name
     * @param version Version number
     * @return The secret value for that version
     */
    Promise<SecretValue> getSecretVersion(String name, int version);

    /**
     * Updates a secret with a new value.
     *
     * @param name  Secret name
     * @param value New secret value
     * @return Metadata of the updated secret
     */
    Promise<SecretMetadata> updateSecret(String name, SecretValue value);

    /**
     * Deletes a secret and all its versions.
     *
     * @param name Secret name
     * @return true if secret existed and was deleted
     */
    Promise<Boolean> deleteSecret(String name);

    /**
     * Checks if a secret exists.
     *
     * @param name Secret name
     * @return true if secret exists
     */
    Promise<Boolean> secretExists(String name);

    // ========== Metadata Operations ==========
    /**
     * Gets metadata for a secret (without the value).
     *
     * @param name Secret name
     * @return Option containing metadata if exists
     */
    Promise<Option<SecretMetadata>> getMetadata(String name);

    /**
     * Lists all secret names (metadata only, no values).
     *
     * @return Set of secret names
     */
    Promise<Set<String>> listSecrets();

    /**
     * Lists secrets matching a pattern.
     *
     * @param pattern Pattern with * wildcard
     * @return Set of matching secret names
     */
    Promise<Set<String>> listSecrets(String pattern);

    /**
     * Lists secrets with a specific tag.
     *
     * @param tagKey   Tag key to match
     * @param tagValue Tag value to match
     * @return Set of matching secret names
     */
    Promise<Set<String>> listSecretsByTag(String tagKey, String tagValue);

    /**
     * Updates tags for a secret.
     *
     * @param name Secret name
     * @param tags New tags
     * @return Updated metadata
     */
    Promise<SecretMetadata> updateTags(String name, Map<String, String> tags);

    // ========== Version Management ==========
    /**
     * Gets all version numbers for a secret.
     *
     * @param name Secret name
     * @return List of version numbers (newest first)
     */
    Promise<List<Integer>> listVersions(String name);

    /**
     * Deletes a specific version of a secret.
     *
     * @param name    Secret name
     * @param version Version to delete
     * @return true if version existed and was deleted
     */
    Promise<Boolean> deleteVersion(String name, int version);

    // ========== Rotation ==========
    /**
     * Rotates a secret with a new value.
     * Creates a new version and marks the secret as rotated.
     *
     * @param name     Secret name
     * @param newValue New secret value
     * @return Metadata with rotation timestamp
     */
    Promise<SecretMetadata> rotateSecret(String name, SecretValue newValue);

    // ========== Factory Methods ==========
    /**
     * Creates an in-memory secrets manager with default configuration.
     */
    static SecretsManager secretsManager() {
        return InMemorySecretsManager.inMemorySecretsManager();
    }

    /**
     * Creates an in-memory secrets manager with custom configuration.
     */
    static SecretsManager secretsManager(SecretsConfig config) {
        return InMemorySecretsManager.inMemorySecretsManager(config);
    }

    // ========== Slice Lifecycle ==========
    @Override
    default Promise<Unit> start() {
        return Promise.success(Unit.unit());
    }

    @Override
    default Promise<Unit> stop() {
        return Promise.success(Unit.unit());
    }

    @Override
    default List<SliceMethod< ?, ?>> methods() {
        return List.of();
    }
}
