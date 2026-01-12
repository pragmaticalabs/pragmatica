package org.pragmatica.aether.infra.secrets;

import org.pragmatica.lang.Option;

import java.time.Instant;
import java.util.Map;

/**
 * Metadata about a secret (does not contain the secret value).
 *
 * @param name        Secret name
 * @param version     Current version number
 * @param createdAt   When the secret was first created
 * @param updatedAt   When the secret was last updated
 * @param rotatedAt   When the secret was last rotated (if ever)
 * @param tags        Optional key-value tags for categorization
 */
public record SecretMetadata(String name,
                             int version,
                             Instant createdAt,
                             Instant updatedAt,
                             Option<Instant> rotatedAt,
                             Map<String, String> tags) {
    /**
     * Creates metadata with all fields.
     */
    public static SecretMetadata secretMetadata(String name,
                                                int version,
                                                Instant createdAt,
                                                Instant updatedAt,
                                                Option<Instant> rotatedAt,
                                                Map<String, String> tags) {
        return new SecretMetadata(name, version, createdAt, updatedAt, rotatedAt, Map.copyOf(tags));
    }

    /**
     * Creates metadata for a new secret.
     */
    public static SecretMetadata secretMetadata(String name) {
        var now = Instant.now();
        return new SecretMetadata(name, 1, now, now, Option.none(), Map.of());
    }

    /**
     * Creates metadata for a new secret with tags.
     */
    public static SecretMetadata secretMetadata(String name, Map<String, String> tags) {
        var now = Instant.now();
        return new SecretMetadata(name, 1, now, now, Option.none(), Map.copyOf(tags));
    }

    /**
     * Creates a new metadata with incremented version.
     */
    public SecretMetadata withNewVersion() {
        return new SecretMetadata(name, version + 1, createdAt, Instant.now(), rotatedAt, tags);
    }

    /**
     * Creates a new metadata marked as rotated.
     */
    public SecretMetadata withRotation() {
        var now = Instant.now();
        return new SecretMetadata(name, version + 1, createdAt, now, Option.option(now), tags);
    }

    /**
     * Creates a new metadata with updated tags.
     */
    public SecretMetadata withTags(Map<String, String> newTags) {
        return new SecretMetadata(name, version, createdAt, Instant.now(), rotatedAt, Map.copyOf(newTags));
    }
}
