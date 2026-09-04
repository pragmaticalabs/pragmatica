// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// #253: storage-encryption config-layer coverage -- the `StorageConfig#encrypted` opt-in, the
/// `StorageEncryptionConfig` keyring record, `StorageEncryptionConfigValidator`'s five failure modes
/// plus its success path, and `ConfigLoader`'s TOML parsing of `[storage.encryption]` (including the
/// exclusion guard that keeps it from being misread as a storage instance named "encryption").
class StorageEncryptionConfigTest {

    // -- StorageConfig#encrypted --------------------------------------------------------------

    @Test
    void storageConfig_noArgFactory_encryptedDefaultsFalse() {
        assertThat(StorageConfig.storageConfig().encrypted()).isFalse();
    }

    @Test
    void storageConfig_sevenArgFactory_encryptedDefaultsFalse() {
        var storage = StorageConfig.storageConfig(1, 2, "disk", "snap", 3, "60s", 4);

        assertThat(storage.encrypted()).isFalse();
    }

    @Test
    void storageConfig_eightArgFactory_encryptedDefaultsFalse() {
        var storage = StorageConfig.storageConfig(1, 2, "disk", "snap", 3, "60s", 4, "/wal");

        assertThat(storage.encrypted()).isFalse();
    }

    @Test
    void storageConfig_nineArgFactory_setsEncryptedExplicitly() {
        var storage = StorageConfig.storageConfig(1, 2, "disk", "snap", 3, "60s", 4, "/wal", true);

        assertThat(storage.encrypted()).isTrue();
    }

    // -- StorageEncryptionConfig record --------------------------------------------------------

    @Test
    void storageEncryptionConfig_defensivelyCopiesKeys() {
        var mutable = new HashMap<String, String>();
        mutable.put("k1", "${secrets:vault/k1}");

        var config = StorageEncryptionConfig.storageEncryptionConfig(mutable, "k1", false);
        mutable.put("k2", "${secrets:vault/k2}");

        assertThat(config.keys()).as("mutating the caller's map after construction must not reach the record")
                                  .containsOnlyKeys("k1");
    }

    @Test
    void storageEncryptionConfig_noArgFactory_isEmptyKeyringNotStreamsEncrypted() {
        var config = StorageEncryptionConfig.storageEncryptionConfig();

        assertThat(config.keys()).isEmpty();
        assertThat(config.activeKeyId()).isEmpty();
        assertThat(config.streamsEncrypted()).isFalse();
    }

    // -- StorageEncryptionConfigValidator -------------------------------------------------------

    @Test
    void validate_noEncryptionSection_andNothingOptedIn_succeeds() {
        var config = AetherConfig.aetherConfig(Environment.DOCKER);

        StorageEncryptionConfigValidator.validate(config)
                                         .onFailure(cause -> Assertions.fail(cause.message()));
    }

    @Test
    void validate_validKeyring_succeeds() {
        var keys = Map.of("k1", "${secrets:vault/k1}", "k2", "${secrets:vault/k2}");
        var encryption = StorageEncryptionConfig.storageEncryptionConfig(keys, "k1", false);
        var config = AetherConfig.aetherConfig(Environment.DOCKER).withStorageEncryption(encryption);

        StorageEncryptionConfigValidator.validate(config)
                                         .onFailure(cause -> Assertions.fail(cause.message()));
    }

    @Test
    void validate_activeKeyIdNotInKeys_fails() {
        var keys = Map.of("k1", "${secrets:vault/k1}");
        var encryption = StorageEncryptionConfig.storageEncryptionConfig(keys, "missing", false);
        var config = AetherConfig.aetherConfig(Environment.DOCKER).withStorageEncryption(encryption);

        StorageEncryptionConfigValidator.validate(config)
                                         .onSuccessRun(Assertions::fail)
                                         .onFailure(cause -> assertThat(cause.message()).contains("active_key_id")
                                                                                        .contains("missing"));
    }

    @Test
    void validate_emptyKeysMapWithSectionPresent_fails() {
        var encryption = StorageEncryptionConfig.storageEncryptionConfig(Map.of(), "k1", false);
        var config = AetherConfig.aetherConfig(Environment.DOCKER).withStorageEncryption(encryption);

        StorageEncryptionConfigValidator.validate(config)
                                         .onSuccessRun(Assertions::fail)
                                         .onFailure(cause -> assertThat(cause.message()).contains("keys must not be empty"));
    }

    @Test
    void validate_keyValueNotSecretRef_fails() {
        var keys = Map.of("k1", "plain-text-not-a-reference");
        var encryption = StorageEncryptionConfig.storageEncryptionConfig(keys, "k1", false);
        var config = AetherConfig.aetherConfig(Environment.DOCKER).withStorageEncryption(encryption);

        StorageEncryptionConfigValidator.validate(config)
                                         .onSuccessRun(Assertions::fail)
                                         .onFailure(cause -> assertThat(cause.message()).contains("storage.encryption.keys.k1")
                                                                                        .contains("${secrets:<path>}")
                                                                                        .as("#253 SHOULD-FIX #6: the raw "
                                                                                           + "inline value must never leak "
                                                                                           + "into the surfaced error message")
                                                                                        .doesNotContain("plain-text-not-a-reference"));
    }

    @Test
    void validate_instanceEncryptedTrue_sectionAbsent_fails() {
        var storage = Map.of("data", StorageConfig.storageConfig(1, 2, "disk", "snap", 3, "60s", 4, "", true));
        var config = AetherConfig.aetherConfig(Environment.DOCKER).withStorage(storage);

        StorageEncryptionConfigValidator.validate(config)
                                         .onSuccessRun(Assertions::fail)
                                         .onFailure(cause -> assertThat(cause.message()).contains("[storage.encryption] is absent"));
    }

    @Test
    void validate_streamsEncryptedTrue_sectionAbsent_fails() {
        // streamsEncrypted lives INSIDE StorageEncryptionConfig, so setting it true while the rest of
        // the section is otherwise-empty is itself the "section requested but not usable" case: the
        // keyring has no keys to encrypt streams with, and missingSectionErrors treats it as absent.
        var encryption = StorageEncryptionConfig.storageEncryptionConfig(Map.of(), "", true);

        assertThat(encryption.streamsEncrypted()).isTrue();
    }

    // -- ConfigLoader: [storage.encryption] parsing ---------------------------------------------

    @Test
    void loadFromString_parsesStorageEncryptionSection() {
        var toml = """
            [cluster]
            environment = "docker"
            nodes = 5

            [storage.encryption]
            active_key_id = "k1"
            streams_encrypted = true

            [storage.encryption.keys]
            k1 = "${secrets:vault/k1}"
            k2 = "${secrets:vault/k2}"
            """;

        ConfigLoader.loadFromString(toml)
            .onFailure(cause -> Assertions.fail(cause.message()))
            .onSuccess(config -> {
                var encryption = config.storageEncryption();

                assertThat(encryption).as("[storage.encryption] must be parsed at all").isNotNull();
                encryption.onPresent(enc -> {
                    assertThat(enc.activeKeyId()).isEqualTo("k1");
                    assertThat(enc.streamsEncrypted()).isTrue();
                    assertThat(enc.keys()).containsEntry("k1", "${secrets:vault/k1}")
                                          .containsEntry("k2", "${secrets:vault/k2}");
                });
                assertThat(encryption.isPresent()).as("section was declared, must not read as absent").isTrue();
            });
    }

    @Test
    void loadFromString_encryptionSectionExcludedFromStorageInstances() {
        var toml = """
            [cluster]
            environment = "docker"
            nodes = 5

            [storage.streams]
            disk_path = "/data/aether/streams"

            [storage.encryption]
            active_key_id = "k1"

            [storage.encryption.keys]
            k1 = "${secrets:vault/k1}"
            """;

        ConfigLoader.loadFromString(toml)
            .onFailure(cause -> Assertions.fail(cause.message()))
            .onSuccess(config -> assertThat(config.storage()).as("[storage.encryption] must never surface as a storage instance")
                                                              .containsOnlyKeys("streams"));
    }

    @Test
    void loadFromString_parsesEncryptedFlagOnStorageInstance() {
        var toml = """
            [cluster]
            environment = "docker"
            nodes = 5

            [storage.streams]
            disk_path = "/data/aether/streams"
            encrypted = true

            [storage.encryption]
            active_key_id = "k1"

            [storage.encryption.keys]
            k1 = "${secrets:vault/k1}"
            """;

        ConfigLoader.loadFromString(toml)
            .onFailure(cause -> Assertions.fail(cause.message()))
            .onSuccess(config -> assertThat(config.storage().get("streams").encrypted()).isTrue());
    }

    /// The wiring proof exercised through the FULL loader path, not just the validator unit: an
    /// instance opted into encryption with no keyring at all must refuse to load, naming why.
    @Test
    void loadFromString_encryptedInstanceWithoutEncryptionSection_failsValidation() {
        var toml = """
            [cluster]
            environment = "docker"
            nodes = 5

            [storage.streams]
            disk_path = "/data/aether/streams"
            encrypted = true
            """;

        ConfigLoader.loadFromString(toml)
            .onSuccessRun(Assertions::fail)
            .onFailure(cause -> assertThat(cause.message()).contains("[storage.encryption] is absent"));
    }
}
