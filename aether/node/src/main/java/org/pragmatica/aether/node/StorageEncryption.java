// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.pragmatica.aether.config.StorageEncryptionConfig;
import org.pragmatica.aether.environment.SecretsProvider;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.storage.BlockEncryptor;
import org.pragmatica.storage.EncryptionKeyring;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// #253: resolves the boot-time storage-encryption keyring from the configured `${secrets:<path>}`
/// references, via the existing [SecretsProvider] SPI (owner ruling: Vault/#119 deferred, the
/// keyring rides the SPI already in place).
///
/// This is the ONLY place a raw key byte array is derived from a secret: `StorageEncryptionConfig`
/// and its validator (`aether-config`) only ever see the `${secrets:...}` reference string, never
/// the resolved value, and `EncryptionKeyring`/`BlockEncryptor` (`integrations/storage`) never touch
/// secret resolution or config parsing.
///
/// Resolution is bounded by [#RESOLUTION_TIMEOUT] (owner condition 5) so a hung or slow secrets
/// backend cannot hang boot indefinitely. Every configured key must resolve or the whole keyring
/// fails closed -- there is no partial ring, because a block written under a key that failed to
/// resolve THIS boot would be unreadable at every future boot until that key resolves.
public final class StorageEncryption {
    /// Boot-time keyring resolution must be bounded (owner condition 5) but has no dedicated,
    /// already-verified config field to draw from -- adding one would reopen the committed
    /// `aether-config` layer for a single fixed constant. A `SecretsProvider` round-trip is a
    /// local/short-lived-process call in every implementation this SPI currently has; 30s gives
    /// ample headroom over that without leaving boot to hang on a wedged backend.
    static final TimeSpan RESOLUTION_TIMEOUT = timeSpan(30).seconds();
    private static final Pattern SECRET_REF = Pattern.compile("^\\$\\{secrets:(.+)}$");

    private StorageEncryption() {}

    /// Resolves every key in `config.keys()` through `provider`, decodes each resolved secret as a
    /// Base64-encoded AES-256 key, and assembles the result into an [EncryptionKeyring]. Fails with
    /// the FIRST encountered failure (malformed reference, resolution failure, bad encoding, or
    /// wrong key length) if any key fails to resolve, or if `config.activeKeyId()` names a key not
    /// present in `config.keys()`.
    public static Result<EncryptionKeyring> resolveKeyring(SecretsProvider provider, StorageEncryptionConfig config) {
        var pending = config.keys()
                            .entrySet()
                            .stream()
                            .map(entry -> resolveOne(provider,
                                                     entry.getKey(),
                                                     entry.getValue()))
                            .toList();

        return Promise.allOf(pending)
                      .flatMap(results -> Promise.resolved(collectOrFirstFailure(results)))
                      .flatMap(keys -> Promise.resolved(EncryptionKeyring.encryptionKeyring(keys,
                                                                                            config.activeKeyId())))
                      .await(RESOLUTION_TIMEOUT);
    }

    private static Promise<Map.Entry<String, BlockEncryptor>> resolveOne(SecretsProvider provider,
                                                                         String keyId,
                                                                         String ref) {
        return extractPath(keyId, ref).fold(Promise::failure,
                                            path -> provider.resolveSecret(path)
                                                            .flatMap(secret -> Promise.resolved(decodeKey(keyId, secret)))
                                                            .flatMap(keyBytes -> Promise.resolved(BlockEncryptor.aesGcm(keyBytes,
                                                                                                                        keyId)))
                                                            .map(encryptor -> Map.entry(keyId, encryptor)));
    }

    private static Result<String> extractPath(String keyId, String ref) {
        var matcher = SECRET_REF.matcher(ref);

        return matcher.matches()
               ? Result.success(matcher.group(1))
               : Result.failure(new MalformedSecretRef(keyId, ref));
    }

    private static Result<byte[]> decodeKey(String keyId, String secret) {
        return Result.lift(cause -> new InvalidSecretEncoding(keyId, cause.getMessage()),
                           () -> Base64.getDecoder().decode(secret));
    }

    /// Fails on the first per-key failure found (in encounter order); otherwise collects every
    /// resolved entry. Deliberately NOT [SecretsProvider#resolveSecrets(List)] -- that default
    /// silently drops failed resolutions, which is exactly the outcome owner condition 5 forbids.
    private static Result<Map<String, BlockEncryptor>> collectOrFirstFailure(List<Result<Map.Entry<String, BlockEncryptor>>> results) {
        var keys = new HashMap<String, BlockEncryptor>();

        for (var result : results) {
            if (result.isFailure()) {
                return result.map(entry -> Map.of());
            }

            result.onSuccess(entry -> keys.put(entry.getKey(), entry.getValue()));
        }

        return Result.success(Map.copyOf(keys));
    }

    /// A `storage.encryption.keys` entry doesn't match the required `${secrets:<path>}` shape.
    /// `StorageEncryptionConfigValidator` already rejects this before boot ever reaches here on the
    /// normal load path; this only fires if `resolveKeyring` is invoked with a config that bypassed
    /// that validator.
    public record MalformedSecretRef(String keyId, String ref) implements Cause {
        @Override
        public String message() {
            return "storage.encryption.keys." + keyId + " is not a '${secrets:<path>}' reference (inline value)";
        }
    }

    /// The secret resolved for `keyId` is not valid Base64 -- the required encoding for a raw AES
    /// key (the same convention `GossipKeyRotationHandler` uses elsewhere in this module).
    public record InvalidSecretEncoding(String keyId, String reason) implements Cause {
        @Override
        public String message() {
            return "storage.encryption.keys." + keyId + " did not decode as Base64: " + reason;
        }
    }
}
