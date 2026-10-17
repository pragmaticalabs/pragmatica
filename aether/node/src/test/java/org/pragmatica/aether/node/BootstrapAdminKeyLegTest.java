// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
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
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.security.ClusterSecretDerivation;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// #290 — proves the cluster-write leg: it registers an ADMIN key when none exists (committing its
/// SHA-256 hash through the applier) and is a no-op when an admin key already exists. The committed
/// hash must equal `hashKey(plaintext)` so the printed key authenticates via the validator.
///
/// #980 — and proves the key is now DERIVED from the cluster secret rather than randomised, which is
/// what lets `aether cluster bootstrap` authenticate its own quorum poll against the cluster it just
/// formed. The properties that matter are determinism (both sides reach the same key), separation
/// (two clusters do not), and that deriving changed NOTHING about registration — the key is still
/// committed to KV under `ApiKeyKey`/`ApiKeyValue` with an audit entry, which is what keeps it
/// enumerable via `GET /api/v1/cluster/keys`, revocable and auditable.
class BootstrapAdminKeyLegTest {
    private static final String SECRET = "leg-test-cluster-secret";
    private static final Option<String> NO_SECRET = Option.none();

    private MessageRouter router;
    private KVStore<AetherKey, AetherValue> kvStore;
    private List<KVCommand<AetherKey>> captured;

    @BeforeEach
    void setUp() {
        router = MessageRouter.mutable();
        kvStore = new KVStore<>(router, stubSerializer(), stubDeserializer());
        captured = new ArrayList<>();
    }

    @Test
    void leg_emptyStore_generatesAdminKeyAndCommitsMatchingHash() {
        var leg = BootstrapAdminKeyLeg.bootstrapAdminKeyLeg(() -> kvStore, () -> true, this::applyAndSeed, NO_SECRET);

        var result = leg.get();
        var plaintext = await(result);

        assertThat(plaintext.isPresent()).as("a new key must be generated for an empty store").isTrue();
        var key = plaintext.unwrap();
        assertThat(key).startsWith(BootstrapAdminKeyLeg.KEY_PREFIX);

        var stored = (ApiKeyValue) kvStore.get(ApiKeyKey.apiKeyKey(BootstrapAdminKeyLeg.KEY_ID)).unwrap();
        assertThat(stored.authorizationRole()).isEqualTo(AuthorizationRole.ADMIN.name());
        assertThat(stored.isValidForAuth()).isTrue();
        assertThat(stored.keyHash())
            .as("stored hash must equal hashKey(plaintext) so the printed key authenticates")
            .isEqualTo(BootstrapAdminKeyLeg.hashKey(key));
    }

    @Test
    void leg_existingAdminKey_isNoOp() {
        seedAdminKey();
        captured.clear();
        var leg = BootstrapAdminKeyLeg.bootstrapAdminKeyLeg(() -> kvStore, () -> true, this::applyAndSeed, secret());

        var plaintext = await(leg.get());

        assertThat(plaintext.isEmpty()).as("an existing admin key must short-circuit to no-op").isTrue();
        assertThat(captured).as("no commands committed when a key already exists").isEmpty();
    }

    @Test
    void leg_notLeader_failsWithoutWriting() {
        var leg = BootstrapAdminKeyLeg.bootstrapAdminKeyLeg(() -> kvStore, () -> false, this::applyAndSeed, secret());

        var result = leg.get();

        assertThat(result.await().isFailure()).as("a non-leader must not write").isTrue();
        assertThat(captured).isEmpty();
    }

    /// #980 — the derivation properties `aether cluster bootstrap` depends on.
    @Nested
    class DerivationFromClusterSecret {
        /// THE fix. The node's key must be exactly what the CLI computes from the same secret, with
        /// none of it having crossed the wire — otherwise phase 7 keeps taking a 401 from a healthy
        /// cluster.
        @Test
        void leg_withClusterSecret_derivesTheKeyTheCliWillDerive() {
            var leg = BootstrapAdminKeyLeg.bootstrapAdminKeyLeg(() -> kvStore,
                                                                () -> true,
                                                                BootstrapAdminKeyLegTest.this::applyAndSeed,
                                                                secret());

            var key = await(leg.get()).unwrap();

            assertThat(key).as("the CLI derives this same value locally and authenticates with it")
                      .isEqualTo(ClusterSecretDerivation.bootstrapAdminKey(SECRET).unwrap());
        }

        /// Determinism across independent invocations against independent stores — the two sides of
        /// a bootstrap never share state, so agreeing once is not enough.
        @Test
        void leg_sameSecretTwoFreshClusters_producesTheSameKey() {
            var first = await(runFreshLeg(secret())).unwrap();
            var second = await(runFreshLeg(secret())).unwrap();

            assertThat(first).isEqualTo(second);
        }

        /// Separation. One cluster's operator must not hold another cluster's admin credential.
        @Test
        void leg_differentSecrets_produceDifferentKeys() {
            var first = await(runFreshLeg(secret())).unwrap();
            var second = await(runFreshLeg(Option.some(SECRET + "-other"))).unwrap();

            assertThat(first).isNotEqualTo(second);
        }

        /// Deriving must not have changed WHAT gets registered. This is the shape-A property: the key
        /// lands in KV under the same key/value the `GET /api/v1/cluster/keys` handler enumerates
        /// (`ApiKeyRoutes.handleListKeys` reads `forEach(ApiKeyKey.class, ApiKeyValue.class, …)`),
        /// with an audit entry beside it — the enumerability, revocability and auditability that a
        /// boot-time injection into the validator set would have discarded.
        @Test
        void leg_withClusterSecret_registersKeyAndAuditEntryInKvStore() {
            var leg = BootstrapAdminKeyLeg.bootstrapAdminKeyLeg(() -> kvStore,
                                                                () -> true,
                                                                BootstrapAdminKeyLegTest.this::applyAndSeed,
                                                                secret());

            var key = await(leg.get()).unwrap();

            var enumerated = new ArrayList<ApiKeyValue>();

            kvStore.forEach(ApiKeyKey.class, ApiKeyValue.class, (_, value) -> enumerated.add(value));

            assertThat(enumerated).as("the derived key must be enumerable via GET /api/v1/cluster/keys")
                      .anyMatch(value -> value.keyId().equals(BootstrapAdminKeyLeg.KEY_ID)
                                         && value.keyHash().equals(BootstrapAdminKeyLeg.hashKey(key))
                                         && value.authorizationRole().equals(AuthorizationRole.ADMIN.name()));

            var audits = new ArrayList<ApiKeyAuditValue>();

            kvStore.forEach(ApiKeyAuditKey.class, ApiKeyAuditValue.class, (_, value) -> audits.add(value));

            assertThat(audits).as("registration must stay auditable")
                      .anyMatch(value -> value.keyId().equals(BootstrapAdminKeyLeg.KEY_ID)
                                         && value.action().equals(ApiKeyAuditValue.ACTION_CREATED));
        }

        /// Idempotency on a second call against the SAME store — the registrar re-arms on every
        /// leader-gain, so a re-elected leader must not mint a second key. Distinct from
        /// `leg_existingAdminKey_isNoOp`, which seeds a foreign key rather than the leg's own.
        @Test
        void leg_secondCallAgainstItsOwnRegistration_isNoOp() {
            var leg = BootstrapAdminKeyLeg.bootstrapAdminKeyLeg(() -> kvStore,
                                                                () -> true,
                                                                BootstrapAdminKeyLegTest.this::applyAndSeed,
                                                                secret());

            assertThat(await(leg.get()).isPresent()).isTrue();
            captured.clear();

            assertThat(await(leg.get()).isEmpty()).as("a re-elected leader must not mint a second key")
                      .isTrue();
            assertThat(captured).isEmpty();
        }

        /// The in-JVM harness case (Ember/Forge nodes booted without a secret) keeps the pre-#980
        /// random key. A blank secret counts as absent: `BootstrapContext` defaults it to `""`, and
        /// deriving a cluster-wide ADMIN credential from the empty string would give every such
        /// cluster the same publicly-computable key.
        @Test
        void leg_blankOrAbsentSecret_fallsBackToARandomKey() {
            var absent = await(runFreshLeg(NO_SECRET)).unwrap();
            var blank = await(runFreshLeg(Option.some("   "))).unwrap();

            assertThat(absent).startsWith(BootstrapAdminKeyLeg.KEY_PREFIX)
                      .as("no secret means no derivation, so two such clusters must not collide")
                      .isNotEqualTo(blank);
            assertThat(blank).isNotEqualTo(ClusterSecretDerivation.bootstrapAdminKey("   ").unwrap());
        }
    }

    /// One leg over its OWN empty store, so determinism and separation are measured across
    /// independent registrations rather than against shared state.
    private static Promise<Option<String>> runFreshLeg(Option<String> clusterSecret) {
        var store = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());

        return BootstrapAdminKeyLeg.bootstrapAdminKeyLeg(() -> store,
                                                         () -> true,
                                                         commands -> applyTo(store, commands),
                                                         clusterSecret)
                                   .get();
    }

    private static Option<String> secret() {
        return Option.some(SECRET);
    }

    private void seedAdminKey() {
        var value = ApiKeyValue.apiKeyValue("existing-admin", "deadbeef", 0L, AuthorizationRole.ADMIN.name());

        commit(List.of(castPut(new KVCommand.Put<>(ApiKeyKey.apiKeyKey("existing-admin"), value))));
    }

    private Promise<List<Object>> applyAndSeed(List<KVCommand<AetherKey>> commands) {
        captured.addAll(commands);

        return applyTo(kvStore, commands);
    }

    private static Promise<List<Object>> applyTo(KVStore<AetherKey, AetherValue> store,
                                                 List<KVCommand<AetherKey>> commands) {
        store.process(store.createBatch(commands));

        return Promise.success(List.of());
    }

    private void commit(List<KVCommand<AetherKey>> commands) {
        kvStore.process(kvStore.createBatch(commands));
    }

    @SuppressWarnings("unchecked")
    private static KVCommand<AetherKey> castPut(KVCommand.Put<ApiKeyKey, AetherValue> put) {
        return (KVCommand<AetherKey>) (KVCommand<?>) put;
    }

    private static Option<String> await(Promise<Option<String>> promise) {
        return promise.await().unwrap();
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }
}
