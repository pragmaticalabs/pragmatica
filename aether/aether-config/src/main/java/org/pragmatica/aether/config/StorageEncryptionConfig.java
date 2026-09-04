// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import java.util.Map;


/// #253: the global `[storage.encryption]` section -- one keyring shared by every storage instance
/// that opts in (`StorageConfig#encrypted`) plus, separately, the built-in `streams` instance
/// (`streamsEncrypted`, since `streams` has no `StorageConfig` of its own -- see `StorageFactory`).
///
/// `keys` holds each key id's SECRET REFERENCE, not the secret itself: a `${secrets:<path>}` string
/// resolved once, at boot, through the live `SecretsProvider` (`aether/node`'s
/// `StorageEncryption.resolveKeyring`) -- config loading and validation never see the actual key
/// bytes. `activeKeyId` selects which entry encrypts new writes; every entry in `keys` remains
/// readable for existing blocks (key rotation keeps old keys resolvable without re-encrypting data).
///
/// Structural shape (active id present in keys, non-empty when required, each value matching the
/// `${secrets:...}` grammar) is checked by `StorageEncryptionConfigValidator`, deliberately kept out
/// of `ConfigValidator` since it owns a section `ConfigValidator` has no other reason to know about.
///
/// @param keys             key id -> `${secrets:<path>}` reference
/// @param activeKeyId      the key id new writes encrypt under; must be a key present in `keys`
/// @param streamsEncrypted opts the built-in `streams` storage instance into encryption
public record StorageEncryptionConfig(Map<String, String> keys, String activeKeyId, boolean streamsEncrypted) {
    public StorageEncryptionConfig {
        keys = Map.copyOf(keys);
    }

    public static StorageEncryptionConfig storageEncryptionConfig(Map<String, String> keys,
                                                                   String activeKeyId,
                                                                   boolean streamsEncrypted) {
        return new StorageEncryptionConfig(keys, activeKeyId, streamsEncrypted);
    }

    public static StorageEncryptionConfig storageEncryptionConfig() {
        return new StorageEncryptionConfig(Map.of(), "", false);
    }
}
