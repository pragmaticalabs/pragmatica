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
import java.util.function.Function;
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
import org.pragmatica.lang.Result;
import org.pragmatica.net.tcp.security.ClusterSecretDerivation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// #290 — the cluster-write leg used by [`BootstrapAdminKeyRegistrar`]. Pure of any scheduling /
/// retry concern: it (1) checks the KV store for an existing active ADMIN key and short-circuits to
/// `Some(None)` when one is present (idempotent), or (2) produces a key, commits its hash + audit
/// entry through consensus, and resolves to `Some(plaintext)` for one-time display.
///
/// The hash is SHA-256 hex, byte-for-byte the format
/// [`org.pragmatica.aether.http.security.KvStoreApiKeyValidator`] compares against, so the printed
/// plaintext authenticates cluster-wide via the `X-API-Key` header.
///
/// #980 — the key is DERIVED from the cluster secret via
/// [`ClusterSecretDerivation#bootstrapAdminKey`] rather than randomised, so `aether cluster
/// bootstrap` can authenticate its own quorum poll. The CLI mints the cluster secret in phase 1 and
/// the node cannot boot without it, so both sides reach the same key with none of it crossing the
/// wire. Registration is unchanged: hash + audit entry, committed through consensus at first
/// leadership, which keeps the key enumerable via `GET /api/v1/cluster/keys`, revocable and
/// auditable — the properties a boot-time injection into the validator set would have discarded.
///
/// Without a cluster secret the key stays RANDOM, exactly as before #980. That is the in-JVM
/// harness case (Ember/Forge nodes that never pass through `Main`), and the fallback is strictly
/// stronger, never weaker — a random key is unguessable; it is simply not re-derivable by an
/// operator holding the secret. Which path ran is logged.
public sealed interface BootstrapAdminKeyLeg {
    String KEY_ID = "bootstrap-admin";
    String KEY_PREFIX = ClusterSecretDerivation.BOOTSTRAP_ADMIN_KEY_PREFIX;

    int KEY_BYTES = ClusterSecretDerivation.BOOTSTRAP_ADMIN_KEY_BYTES;

    /// Build the [`BootstrapAdminKeyRegistrar`] leg bound to a live node's KV store and consensus
    /// command applier. The applier is the same `clusterNode.apply` path used by the API-key routes.
    ///
    /// @param clusterSecret this node's cluster secret, when it has one. Present for any node booted
    ///                      through `Main` (a missing secret aborts startup) and for Ember clusters;
    ///                      absent only where no secret exists at all, which selects the random key.
    static Supplier<Promise<Option<String>>> bootstrapAdminKeyLeg(Supplier<KVStore<AetherKey, AetherValue>> kvStoreSupplier,
                                                                  Supplier<Boolean> isLeader,
                                                                  Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                                                  Option<String> clusterSecret) {
        return () -> attempt(kvStoreSupplier.get(), isLeader.get(), applier, clusterSecret);
    }

    private static Promise<Option<String>> attempt(KVStore<AetherKey, AetherValue> kvStore,
                                                   boolean isLeader,
                                                   Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                                   Option<String> clusterSecret) {
        if (!isLeader) {
            return LegError.notLeader().promise();
        }

        return hasActiveAdminKey(kvStore)
               ? Promise.success(Option.none())
               : generateAndCommit(applier, clusterSecret);
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

    private static Promise<Option<String>> generateAndCommit(Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                                             Option<String> clusterSecret) {
        return generateKey(clusterSecret).async()
                          .flatMap(plaintext -> commit(applier, plaintext));
    }

    private static Promise<Option<String>> commit(Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                                  String plaintext) {
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

    /// #980 — derived from the cluster secret when the node has one, random otherwise. A blank
    /// secret is treated as absent: `BootstrapContext` defaults it to `""`, and deriving a
    /// cluster-wide ADMIN credential from the empty string would be a fixed, publicly-computable key.
    private static Result<String> generateKey(Option<String> clusterSecret) {
        return clusterSecret.filter(secret -> !secret.isBlank())
                            .map(BootstrapAdminKeyLeg::derivedKey)
                            .or(() -> Result.success(randomKey()));
    }

    private static Result<String> derivedKey(String clusterSecret) {
        LOG.info("Bootstrap admin key: deriving from the cluster secret (HKDF info label '{}')",
                 ClusterSecretDerivation.BOOTSTRAP_ADMIN_KEY_INFO);

        return ClusterSecretDerivation.bootstrapAdminKey(clusterSecret);
    }

    /// WARN, not INFO, and deliberately blunt about the consequence. This branch is a FAIL-OPEN: it
    /// substitutes a plausible-looking credential for the derivation that did not happen, and the
    /// operator-visible result is `aether cluster bootstrap` taking a 401 from a healthy cluster —
    /// **precisely the defect #980 exists to fix**, with nothing saying the derived path was skipped.
    /// A silent substitution that masks a refusal is a shape this project has shipped before, so the
    /// message names what did not happen, what will break because of it, and how to fix it.
    ///
    /// It is unreachable through `Main`: a node with no cluster secret does not boot
    /// (`Main.resolveTls` fails and `run()` `.expect`s it, pinned by
    /// `MainClusterSecretStampTest#resolveTls_noClusterSecretAnywhere_failsSoTheNodeCannotBoot`), and
    /// `EmberCluster` always supplies its own. It remains reachable for anything constructing
    /// `AetherNodeConfig` directly without a secret, which is why it warns rather than being deleted.
    private static String randomKey() {
        LOG.warn("Bootstrap admin key: NO CLUSTER SECRET — the key was NOT derived and is RANDOM. "
                + "`aether cluster bootstrap` derives its credential from the cluster secret, so it "
                + "CANNOT match this key and its quorum poll will fail authentication with 401. The "
                + "key is still enumerable and revocable via /api/v1/cluster/keys and is printed once "
                + "below. Set `[tls] cluster_secret` or AETHER_CLUSTER_SECRET so the key is derived.");
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

    Logger LOG = LoggerFactory.getLogger(BootstrapAdminKeyLeg.class);
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
