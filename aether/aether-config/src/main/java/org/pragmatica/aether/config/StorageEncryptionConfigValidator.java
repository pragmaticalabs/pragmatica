// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


/// #253: structural/syntactic validation of `[storage.encryption]` -- confirms the KEYRING SHAPE is
/// usable before boot ever tries to resolve anything. Deliberately does not touch `SecretsProvider`
/// and never resolves a secret value: that happens exactly once, at boot, in `aether/node`'s
/// `StorageEncryption.resolveKeyring`, bounded by a timeout (owner condition 5). Kept out of
/// `ConfigValidator` since it validates a section `ConfigValidator` has no other reason to know
/// about (#253 territory).
public final class StorageEncryptionConfigValidator {
    private static final Pattern SECRET_REF = Pattern.compile("^\\$\\{secrets:([^}]+)}$");

    private StorageEncryptionConfigValidator() {}

    public static Result<AetherConfig> validate(AetherConfig config) {
        var errors = new ArrayList<String>();

        config.storageEncryption().onPresent(enc -> encryptionSectionErrors(enc, errors));
        missingSectionErrors(config, errors);

        return errors.isEmpty()
               ? success(config)
               : ConfigValidator.ConfigError.validationFailed(errors).result();
    }

    private static void encryptionSectionErrors(StorageEncryptionConfig enc, List<String> errors) {
        if (enc.keys().isEmpty()) {
            errors.add("storage.encryption.keys must not be empty when [storage.encryption] is present");

            return;
        }

        if (!enc.keys().containsKey(enc.activeKeyId())) {
            errors.add("storage.encryption.active_key_id '" + enc.activeKeyId()
                      + "' is not present in storage.encryption.keys " + enc.keys().keySet());
        }

        enc.keys().forEach((keyId, ref) -> secretRefErrors(keyId, ref, errors));
    }

    private static void secretRefErrors(String keyId, String ref, List<String> errors) {
        if (!SECRET_REF.matcher(ref).matches()) {
            errors.add("storage.encryption.keys." + keyId + " must be a '${secrets:<path>}' reference. Got: " + ref);
        }
    }

    /// An instance opted into encryption (`StorageConfig#encrypted`), or `streams` via
    /// `streamsEncrypted`, needs a keyring to encrypt into. Caught here rather than left for
    /// `StorageFactory` to discover at boot, so the operator sees one collected config report
    /// instead of a late, less specific failure.
    private static void missingSectionErrors(AetherConfig config, List<String> errors) {
        var anyInstanceEncrypted = config.storage().values().stream().anyMatch(StorageConfig::encrypted);
        var streamsEncrypted = config.storageEncryption().map(StorageEncryptionConfig::streamsEncrypted).or(false);

        if ((anyInstanceEncrypted || streamsEncrypted) && config.storageEncryption().isEmpty()) {
            errors.add("storage encryption requested (an instance has encrypted = true, or "
                      + "storage.encryption.streams_encrypted = true) but [storage.encryption] is absent");
        }
    }
}
