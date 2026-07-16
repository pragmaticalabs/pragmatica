// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.pragmatica.aether.http.handler.security.AuthorizationRole;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ApiKeyAuditKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ApiKeyKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ApiKeyAuditValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ApiKeyValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;


/// #290 — the cluster-write leg used by [`BootstrapAdminKeyRegistrar`]. Pure of any scheduling /
/// retry concern: it (1) checks the KV store for an existing active ADMIN key and short-circuits to
/// `Some(None)` when one is present (idempotent), or (2) generates a random key, commits its hash +
/// audit entry through consensus, and resolves to `Some(plaintext)` for one-time display.
///
/// The hash is SHA-256 hex, byte-for-byte the format
/// [`org.pragmatica.aether.http.security.KvStoreApiKeyValidator`] compares against, so the printed
/// plaintext authenticates cluster-wide via the `X-API-Key` header.
public sealed interface BootstrapAdminKeyLeg {
    String KEY_ID = "bootstrap-admin";
    String KEY_PREFIX = "aeth_";
    int KEY_BYTES = 32;

    /// Build the [`BootstrapAdminKeyRegistrar`] leg bound to a live node's KV store and consensus
    /// command applier. The applier is the same `clusterNode.apply` path used by the API-key routes.
    static Supplier<Promise<Option<String>>> bootstrapAdminKeyLeg(Supplier<KVStore<AetherKey, AetherValue>> kvStoreSupplier,
                                                                  Supplier<Boolean> isLeader,
                                                                  java.util.function.Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier) {
        return () -> attempt(kvStoreSupplier.get(), isLeader.get(), applier);
    }

    private static Promise<Option<String>> attempt(KVStore<AetherKey, AetherValue> kvStore,
                                                   boolean isLeader,
                                                   java.util.function.Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier) {
        if (!isLeader) {
            return LegError.notLeader().promise();
        }

        return hasActiveAdminKey(kvStore)
               ? Promise.success(Option.none())
               : generateAndCommit(applier);
    }

    private static boolean hasActiveAdminKey(KVStore<AetherKey, AetherValue> kvStore) {
        var found = new AtomicBoolean(false);

        kvStore.forEach(ApiKeyKey.class, ApiKeyValue.class, (_, value) -> markIfActiveAdmin(found, value));

        return found.get();
    }

    private static void markIfActiveAdmin(AtomicBoolean found, ApiKeyValue value) {
        if (value.isValidForAuth() && AuthorizationRole.ADMIN.name().equalsIgnoreCase(value.authorizationRole())) {
            found.set(true);
        }
    }

    private static Promise<Option<String>> generateAndCommit(java.util.function.Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier) {
        var plaintext = generateKey();
        var keyValue = ApiKeyValue.apiKeyValue(KEY_ID, hashKey(plaintext), 0L, AuthorizationRole.ADMIN.name());
        var auditValue = ApiKeyAuditValue.apiKeyAuditValue(KEY_ID,
                                                           ApiKeyAuditValue.ACTION_CREATED,
                                                           "cluster-formation-bootstrap");

        return applier.apply(List.of(putKey(keyValue),
                                     putAudit(auditValue)))
                      .map(_ -> Option.some(plaintext));
    }

    private static KVCommand<AetherKey> putKey(ApiKeyValue keyValue) {
        return castCommand(new KVCommand.Put<>(ApiKeyKey.apiKeyKey(KEY_ID), keyValue));
    }

    private static KVCommand<AetherKey> putAudit(ApiKeyAuditValue auditValue) {
        var auditId = KEY_ID + "-" + System.currentTimeMillis();

        return castCommand(new KVCommand.Put<>(ApiKeyAuditKey.apiKeyAuditKey(auditId), auditValue));
    }

    @SuppressWarnings("unchecked")
    private static KVCommand<AetherKey> castCommand(KVCommand<?> command) {
        return (KVCommand<AetherKey>) command;
    }

    private static String generateKey() {
        var bytes = new byte[KEY_BYTES];

        RANDOM.nextBytes(bytes);

        return KEY_PREFIX + Base64.getUrlEncoder()
                                  .withoutPadding()
                                  .encodeToString(bytes);
    }

    @SuppressWarnings({"JBCT-UTIL-01", "JBCT-EX-01"})
    static String hashKey(String key) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hash = digest.digest(key.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available", e);
        }
    }

    SecureRandom RANDOM = new SecureRandom();

    enum LegError implements org.pragmatica.lang.Cause {
        NOT_LEADER("Bootstrap admin key: node is not leader");
        private final String message;
        LegError(String message) {
            this.message = message;
        }
        static LegError notLeader() {
            return NOT_LEADER;
        }
        @Override
        public String message() {
            return message;
        }
    }

    record unused() implements BootstrapAdminKeyLeg {}
}
