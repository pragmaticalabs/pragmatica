package org.pragmatica.aether.infra.secrets;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Unit.unit;

/**
 * In-memory implementation of SecretsManager.
 *
 * <p><strong>WARNING: This implementation does NOT encrypt values.</strong>
 * The {@code encryptionEnabled} config flag is ignored - secrets are stored in plain text.
 * Use this implementation ONLY for testing and development. For production, use a proper
 * secrets manager with actual encryption (e.g., HashiCorp Vault, AWS Secrets Manager).
 */
final class InMemorySecretsManager implements SecretsManager {
    private static final SecretsConfig DEFAULT_CONFIG = new SecretsConfig("default", false, Option.none(), 10);

    private final SecretsConfig config;
    private final ConcurrentHashMap<String, SecretEntry> secrets = new ConcurrentHashMap<>();

    private InMemorySecretsManager(SecretsConfig config) {
        this.config = config;
    }

    static InMemorySecretsManager inMemorySecretsManager() {
        return new InMemorySecretsManager(SecretsConfig.secretsConfig()
                                                       .fold(err -> DEFAULT_CONFIG, c -> c));
    }

    static InMemorySecretsManager inMemorySecretsManager(SecretsConfig config) {
        return new InMemorySecretsManager(config);
    }

    // ========== Basic Operations ==========
    @Override
    public Promise<SecretMetadata> createSecret(String name, SecretValue value) {
        return createSecret(name, value, Map.of());
    }

    @Override
    public Promise<SecretMetadata> createSecret(String name, SecretValue value, Map<String, String> tags) {
        return validateSecretName(name).flatMap(validName -> createNewSecret(validName, value, tags));
    }

    private Promise<SecretMetadata> createNewSecret(String name, SecretValue value, Map<String, String> tags) {
        var metadata = SecretMetadata.secretMetadata(name, tags);
        var entry = SecretEntry.secretEntry(metadata);
        entry.versions()
             .put(1, value);
        return option(secrets.putIfAbsent(name, entry))
        .fold(() -> Promise.success(metadata),
              existing -> SecretsError.secretAlreadyExists(name)
                                      .promise());
    }

    @Override
    public Promise<SecretValue> getSecret(String name) {
        return getSecretEntryOrFail(name).map(entry -> entry.versions()
                                                            .get(entry.metadata()
                                                                      .version()));
    }

    @Override
    public Promise<SecretValue> getSecretVersion(String name, int version) {
        return getSecretEntryOrFail(name).flatMap(entry -> getVersionOrFail(entry, name, version));
    }

    private Promise<SecretValue> getVersionOrFail(SecretEntry entry, String name, int version) {
        return option(entry.versions()
                           .get(version))
        .fold(() -> SecretsError.versionNotFound(name, version)
                                .<SecretValue> promise(), Promise::success);
    }

    @Override
    public Promise<SecretMetadata> updateSecret(String name, SecretValue value) {
        return getSecretEntryOrFail(name).map(entry -> updateSecretEntry(entry, value));
    }

    private SecretMetadata updateSecretEntry(SecretEntry entry, SecretValue value) {
        var newMetadata = entry.metadata()
                               .withNewVersion();
        entry.setMetadata(newMetadata);
        entry.versions()
             .put(newMetadata.version(),
                  value);
        pruneOldVersions(entry);
        return newMetadata;
    }

    @Override
    public Promise<Boolean> deleteSecret(String name) {
        return Promise.success(option(secrets.remove(name)).onPresent(entry -> clearAllVersions(entry))
                                     .isPresent());
    }

    private void clearAllVersions(SecretEntry entry) {
        entry.versions()
             .values()
             .forEach(SecretValue::clear);
    }

    @Override
    public Promise<Boolean> secretExists(String name) {
        return Promise.success(secrets.containsKey(name));
    }

    // ========== Metadata Operations ==========
    @Override
    public Promise<Option<SecretMetadata>> getMetadata(String name) {
        return Promise.success(option(secrets.get(name)).map(SecretEntry::metadata));
    }

    @Override
    public Promise<Set<String>> listSecrets() {
        return Promise.success(Set.copyOf(secrets.keySet()));
    }

    @Override
    public Promise<Set<String>> listSecrets(String pattern) {
        var regex = Pattern.compile(pattern.replace("*", ".*"));
        return Promise.success(secrets.keySet()
                                      .stream()
                                      .filter(name -> regex.matcher(name)
                                                           .matches())
                                      .collect(Collectors.toSet()));
    }

    @Override
    public Promise<Set<String>> listSecretsByTag(String tagKey, String tagValue) {
        return Promise.success(secrets.entrySet()
                                      .stream()
                                      .filter(e -> matchesTag(e.getValue(),
                                                              tagKey,
                                                              tagValue))
                                      .map(Map.Entry::getKey)
                                      .collect(Collectors.toSet()));
    }

    private boolean matchesTag(SecretEntry entry, String tagKey, String tagValue) {
        return tagValue.equals(entry.metadata()
                                    .tags()
                                    .get(tagKey));
    }

    @Override
    public Promise<SecretMetadata> updateTags(String name, Map<String, String> tags) {
        return getSecretEntryOrFail(name).map(entry -> updateEntryTags(entry, tags));
    }

    private SecretMetadata updateEntryTags(SecretEntry entry, Map<String, String> tags) {
        var newMetadata = entry.metadata()
                               .withTags(tags);
        entry.setMetadata(newMetadata);
        return newMetadata;
    }

    // ========== Version Management ==========
    @Override
    public Promise<List<Integer>> listVersions(String name) {
        return getSecretEntryOrFail(name).map(this::listEntryVersions);
    }

    private List<Integer> listEntryVersions(SecretEntry entry) {
        return entry.versions()
                    .keySet()
                    .stream()
                    .sorted(Comparator.reverseOrder())
                    .toList();
    }

    @Override
    public Promise<Boolean> deleteVersion(String name, int version) {
        return getSecretEntryOrFail(name).map(entry -> deleteEntryVersion(entry, version));
    }

    private boolean deleteEntryVersion(SecretEntry entry, int version) {
        if (entry.versions()
                 .size() <= 1) {
            return false;
        }
        return option(entry.versions()
                           .remove(version)).onPresent(SecretValue::clear)
                     .isPresent();
    }

    // ========== Rotation ==========
    @Override
    public Promise<SecretMetadata> rotateSecret(String name, SecretValue newValue) {
        return getSecretEntryOrFail(name).map(entry -> rotateSecretEntry(entry, newValue));
    }

    private SecretMetadata rotateSecretEntry(SecretEntry entry, SecretValue newValue) {
        var newMetadata = entry.metadata()
                               .withRotation();
        entry.setMetadata(newMetadata);
        entry.versions()
             .put(newMetadata.version(),
                  newValue);
        pruneOldVersions(entry);
        return newMetadata;
    }

    // ========== Lifecycle ==========
    @Override
    public Promise<Unit> stop() {
        secrets.values()
               .forEach(this::clearAllVersions);
        secrets.clear();
        return Promise.success(unit());
    }

    // ========== Internal Helpers ==========
    private Promise<String> validateSecretName(String name) {
        return option(name).filter(n -> !n.isBlank())
                     .filter(n -> n.matches("^[a-zA-Z][a-zA-Z0-9._-]*$"))
                     .map(String::trim)
                     .fold(() -> SecretsError.invalidSecretName(name,
                                                                "Name must start with letter and contain only letters, numbers, dots, underscores, and hyphens")
                                             .<String> promise(),
                           Promise::success);
    }

    private Promise<SecretEntry> getSecretEntryOrFail(String name) {
        return option(secrets.get(name))
        .fold(() -> SecretsError.secretNotFound(name)
                                .<SecretEntry> promise(), Promise::success);
    }

    private void pruneOldVersions(SecretEntry entry) {
        while (entry.versions()
                    .size() > config.maxVersions()) {
            var removed = findAndRemoveOldestVersion(entry);
            if (!removed) {
                break;
            }
        }
    }

    private boolean findAndRemoveOldestVersion(SecretEntry entry) {
        return entry.versions()
                    .keySet()
                    .stream()
                    .min(Integer::compareTo)
                    .filter(oldest -> oldest < entry.metadata()
                                                    .version())
                    .map(oldest -> removeVersion(entry, oldest))
                    .isPresent();
    }

    private boolean removeVersion(SecretEntry entry, int version) {
        option(entry.versions()
                    .remove(version)).onPresent(SecretValue::clear);
        return true;
    }

    // ========== Internal Classes ==========
    private record SecretEntry(AtomicReference<SecretMetadata> metadataRef,
                               ConcurrentHashMap<Integer, SecretValue> versions) {
        static SecretEntry secretEntry(SecretMetadata metadata) {
            return new SecretEntry(new AtomicReference<>(metadata), new ConcurrentHashMap<>());
        }

        SecretMetadata metadata() {
            return metadataRef.get();
        }

        void setMetadata(SecretMetadata metadata) {
            metadataRef.set(metadata);
        }
    }
}
