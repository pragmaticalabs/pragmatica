// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.environment.GossipKeyMaterial;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GossipKeyRotationKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GossipKeyRotationValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;

/// #1200 — what the provisioning leader hands the nodes it mints is read from the REPLICATED rotation record, in the form
/// [GossipKeyMaterial] parses on the receiving side.
class ClusterEventsAssemblyGossipKeyTest {
    private static final String CURRENT_KEY = Base64.getEncoder().encodeToString(filled((byte) 0x07));
    private static final String NEWEST_KEY = Base64.getEncoder().encodeToString(filled((byte) 0x09));

    private final KVStore<AetherKey, AetherValue> store = new KVStore<>(MessageRouter.mutable(), new Serializer() {
        @Override
        public <T> void write(ByteBuf buffer, T value) {}
    }, new Deserializer() {
        @Override
        public <T> T read(ByteBuf buffer) {
            return null;
        }
    });

    @Test
    void deliveredGossipKey_noRotationCommitted_isAbsent() {
        assertThat(ClusterEventsAssembly.deliveredGossipKey(store).isEmpty())
            .as("while the derived key is current there is nothing to deliver")
            .isTrue();
    }

    @Test
    void deliveredGossipKey_afterRotation_isTheCurrentKeyInTheDeliveredForm() {
        commit(GossipKeyRotationValue.gossipKeyRotationValue(5, CURRENT_KEY));

        var delivered = ClusterEventsAssembly.deliveredGossipKey(store);

        assertThat(delivered.or("")).isEqualTo("5:" + CURRENT_KEY);
        var parsed = GossipKeyMaterial.gossipKeyMaterial(delivered.or("")).unwrap();

        assertThat(parsed.keyId()).isEqualTo(5);
    }

    /// A later rotation replaces the record; the leader hands out the newest, never the previous key the record also carries.
    @Test
    void deliveredGossipKey_secondRotation_isTheNewestKeyNotThePreviousOne() {
        commit(GossipKeyRotationValue.gossipKeyRotationValue(5, CURRENT_KEY));
        commit(GossipKeyRotationValue.gossipKeyRotationValue(6, NEWEST_KEY, 5, CURRENT_KEY));

        assertThat(ClusterEventsAssembly.deliveredGossipKey(store).or("")).startsWith("6:").doesNotContain(CURRENT_KEY);
    }

    private static byte[] filled(byte value) {
        var key = new byte[32];

        Arrays.fill(key, value);

        return key;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void commit(GossipKeyRotationValue value) {
        store.process(store.createBatch((List) List.of(new KVCommand.Put<>(GossipKeyRotationKey.gossipKeyRotationKey(), value))));
    }
}
