// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.security.AuthorizationRole;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ApiKeyKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ApiKeyValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// #290 — proves the cluster-write leg: it generates an ADMIN key when none exists (committing its
/// SHA-256 hash through the applier) and is a no-op when an admin key already exists. The committed
/// hash must equal `hashKey(plaintext)` so the printed key authenticates via the validator.
class BootstrapAdminKeyLegTest {
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
        var leg = BootstrapAdminKeyLeg.bootstrapAdminKeyLeg(() -> kvStore, () -> true, this::applyAndSeed);

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
        var leg = BootstrapAdminKeyLeg.bootstrapAdminKeyLeg(() -> kvStore, () -> true, this::applyAndSeed);

        var plaintext = await(leg.get());

        assertThat(plaintext.isEmpty()).as("an existing admin key must short-circuit to no-op").isTrue();
        assertThat(captured).as("no commands committed when a key already exists").isEmpty();
    }

    @Test
    void leg_notLeader_failsWithoutWriting() {
        var leg = BootstrapAdminKeyLeg.bootstrapAdminKeyLeg(() -> kvStore, () -> false, this::applyAndSeed);

        var result = leg.get();

        assertThat(result.await().isFailure()).as("a non-leader must not write").isTrue();
        assertThat(captured).isEmpty();
    }

    private void seedAdminKey() {
        var value = ApiKeyValue.apiKeyValue("existing-admin", "deadbeef", 0L, AuthorizationRole.ADMIN.name());

        commit(List.of(castPut(new KVCommand.Put<>(ApiKeyKey.apiKeyKey("existing-admin"), value))));
    }

    private Promise<List<Object>> applyAndSeed(List<KVCommand<AetherKey>> commands) {
        captured.addAll(commands);
        commit(commands);

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
