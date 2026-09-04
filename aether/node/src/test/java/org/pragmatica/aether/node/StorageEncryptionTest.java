// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.StorageEncryptionConfig;
import org.pragmatica.aether.environment.SecretsProvider;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.storage.EncryptionError;
import org.pragmatica.storage.EncryptionKeyring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #253: [StorageEncryption#resolveKeyring] -- the only place a raw AES key is derived from a
/// `${secrets:<path>}` reference.
///
/// Every test configures exactly ONE key. `StorageEncryptionConfig#keys` is a `Map`, so the
/// encounter order `resolveKeyring` folds over is unspecified; a multi-key "first failure wins"
/// assertion would pin nothing and flake instead.
///
/// The `SecretsProvider` doubles are path-KEYED lookups rather than constant-returning lambdas on
/// purpose: that makes the regex capture in `extractPath` load-bearing for the success test too --
/// a mutation returning the whole `${secrets:...}` reference (group 0) instead of the bare path
/// (group 1) misses the lookup and turns the success assertion red.
class StorageEncryptionTest {

    private static final String KEY_ID = "k1";
    private static final String SECRET_PATH = "path/to/k1";
    private static final String SECRET_REF = "${secrets:" + SECRET_PATH + "}";
    private static final String LITERAL_NOT_A_REF = "plain-value-not-a-ref";
    private static final String NOT_BASE64 = "not valid base64!!";
    private static final String VALID_AES256_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private static final Cause SECRETS_BACKEND_UNREACHABLE = Causes.cause("secrets backend unreachable");

    private static StorageEncryptionConfig configOf(Map<String, String> keys, String activeKeyId) {
        return StorageEncryptionConfig.storageEncryptionConfig(keys, activeKeyId, false);
    }

    /// Resolves only `SECRET_PATH`, and only to `secret`. Anything else -- including the full
    /// reference string -- is an unresolvable path, exactly as a real backend would treat it.
    private static SecretsProvider providerFor(String secret) {
        return path -> Option.option(Map.of(SECRET_PATH, secret).get(path))
                             .async(Causes.cause("no secret configured at " + path));
    }

    private static void assertActiveKeyIsPresent(EncryptionKeyring keyring) {
        assertThat(keyring.activeKeyId()).isEqualTo(KEY_ID);
        assertThat(keyring.keys()).containsOnlyKeys(KEY_ID);
    }

    private static void assertMalformedSecretRef(Cause cause) {
        assertThat(cause).isInstanceOf(StorageEncryption.MalformedSecretRef.class);

        var malformed = (StorageEncryption.MalformedSecretRef) cause;

        assertThat(malformed.keyId()).isEqualTo(KEY_ID);
        assertThat(malformed.ref()).isEqualTo(LITERAL_NOT_A_REF);
    }

    private static void assertInvalidSecretEncoding(Cause cause) {
        assertThat(cause).isInstanceOf(StorageEncryption.InvalidSecretEncoding.class);

        var invalid = (StorageEncryption.InvalidSecretEncoding) cause;

        assertThat(invalid.keyId()).isEqualTo(KEY_ID);
    }

    private static void assertUnknownActiveKeyId(Cause cause) {
        assertThat(cause).isInstanceOf(EncryptionError.UnknownKeyId.class);

        var unknown = (EncryptionError.UnknownKeyId) cause;

        assertThat(unknown.keyId()).isEqualTo("missing");
    }

    @Test
    void resolveKeyring_succeeds_withValidBase64KeyAndMatchingActiveKeyId() {
        StorageEncryption.resolveKeyring(providerFor(VALID_AES256_KEY),
                                          configOf(Map.of(KEY_ID, SECRET_REF), KEY_ID))
                          .onFailure(cause -> fail("keyring resolution must succeed: " + cause.message()))
                          .onSuccess(StorageEncryptionTest::assertActiveKeyIsPresent);
    }

    /// `StorageEncryptionConfigValidator` normally rejects this before boot reaches `resolveKeyring`;
    /// this pins the second line of defence for a config that bypassed the validator. Asserting the
    /// cause TYPE plus both of its fields is what makes the test sensitive to `extractPath`'s
    /// `matcher.matches()` branch specifically, rather than to any failure at all.
    @Test
    void resolveKeyring_fails_withMalformedSecretRef_whenValueIsNotSecretsSyntax() {
        StorageEncryption.resolveKeyring(providerFor(VALID_AES256_KEY),
                                          configOf(Map.of(KEY_ID, LITERAL_NOT_A_REF), KEY_ID))
                          .onSuccess(_ -> fail("a key value that is not a '${secrets:<path>}' reference must not resolve"))
                          .onFailure(StorageEncryptionTest::assertMalformedSecretRef);
    }

    @Test
    void resolveKeyring_fails_withInvalidSecretEncoding_whenResolvedSecretIsNotBase64() {
        StorageEncryption.resolveKeyring(providerFor(NOT_BASE64),
                                          configOf(Map.of(KEY_ID, SECRET_REF), KEY_ID))
                          .onSuccess(_ -> fail("a resolved secret that is not Base64 must not become an AES key"))
                          .onFailure(StorageEncryptionTest::assertInvalidSecretEncoding);
    }

    /// Mutation target: the `EncryptionKeyring.encryptionKeyring(keys, config.activeKeyId())` step
    /// inside `resolveKeyring`. Every configured key resolves cleanly here, so the ONLY thing that
    /// can fail is the active-key membership check -- proving that check is actually wired through
    /// rather than bypassed by constructing the ring directly.
    @Test
    void resolveKeyring_fails_whenActiveKeyIdNotAmongKeys() {
        StorageEncryption.resolveKeyring(providerFor(VALID_AES256_KEY),
                                          configOf(Map.of(KEY_ID, SECRET_REF), "missing"))
                          .onSuccess(_ -> fail("an active key id absent from the ring must not produce a keyring"))
                          .onFailure(StorageEncryptionTest::assertUnknownActiveKeyId);
    }

    /// Mutation target: the `.flatMap(secret -> ...)` chain in `resolveOne`. Asserting the SAME
    /// cause instance -- not merely "some failure" -- is what proves the provider's failure is
    /// propagated verbatim rather than swallowed and replaced by a generic resolution error.
    @Test
    void resolveKeyring_propagatesProviderFailure_whenSecretsProviderFails() {
        SecretsProvider failing = _ -> SECRETS_BACKEND_UNREACHABLE.promise();

        StorageEncryption.resolveKeyring(failing, configOf(Map.of(KEY_ID, SECRET_REF), KEY_ID))
                          .onSuccess(_ -> fail("a failing SecretsProvider must fail the whole keyring, not yield a partial ring"))
                          .onFailure(cause -> assertThat(cause).isSameAs(SECRETS_BACKEND_UNREACHABLE));
    }
}
