// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.ConfigLoader;
import org.pragmatica.aether.config.StorageConfig;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #249 × #2333: a `[storage.<name>] promotion = false` survives every shape the storage map is derived through on its
/// way to the node (`Main.resolveStorage`): the loader's pathless marking, the shared-tier attachment and the own-
/// directory placement each copy the configuration, and a copy that dropped the switch would turn background promotion
/// back on silently. The value itself is asserted, so a `false` reset to the default `true` shows.
///
/// Mutations that redden it: `StorageConfig#withPaths`, `#withShared` or `#derivingPaths` passing `true` for
/// `promotion`.
class MainDerivedStoragePromotionTest {

    private static Map<String, StorageConfig> storageOf(String toml) {
        return Main.resolveStorage(Option.some(ConfigLoader.loadFromString(toml)
                                                           .fold(cause -> fail(cause.message()), config -> config)));
    }

    private static void assertPromotionOff(String shape, String toml, String... instances) {
        var storage = storageOf(toml);

        for (var instance : instances) {
            assertThat(storage.get(instance).derivesPaths()).as("%s: '%s' reaches the node derived", shape, instance).isFalse();
            assertThat(storage.get(instance).promotion()).as("%s: '%s' keeps promotion = false", shape, instance).isFalse();
        }
    }

    @Test
    void promotionOff_survivesEveryDerivationShape() {
        assertPromotionOff("empty section", "[storage.vault]\npromotion = false\n", "vault");
        assertPromotionOff("empty section with a shared tier",
                           "[storage.vault]\npromotion = false\n[storage.vault.shared]\nkind = \"dht\"\n",
                           "vault");
        assertPromotionOff("explicit paths",
                           "[storage.vault]\ndisk_path = \"/srv/vault/blocks\"\nsnapshot_path = \"/srv/vault/snapshots\"\npromotion = false\n",
                           "vault");
        assertPromotionOff("artifacts with a shared tier",
                           "[storage.artifacts]\npromotion = false\n[storage.artifacts.shared]\nkind = \"dht\"\n",
                           "artifacts");
        assertPromotionOff("content with a shared tier",
                           "[storage.content]\npromotion = false\n[storage.content.shared]\nkind = \"dht\"\n",
                           "content");
        assertPromotionOff("two sibling instances",
                           "[storage.vault]\npromotion = false\n[storage.ledger]\npromotion = false\n",
                           "vault",
                           "ledger");
        assertPromotionOff("empty section under a keyring",
                           "[storage.encryption]\nactive_key_id = \"k1\"\n[storage.encryption.keys]\nk1 = \"${secrets:k1}\"\n"
                           + "[storage.vault]\npromotion = false\n",
                           "vault");
    }

    /// A shared-only instance has no section to switch promotion in, so it keeps the default.
    @Test
    void sharedOnlyInstance_keepsTheDefaultPromotion() {
        assertThat(storageOf("[storage.ledger.shared]\nkind = \"dht\"\n").get("ledger").promotion()).isTrue();
    }
}
