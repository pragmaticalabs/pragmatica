// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.entityforward;

import java.util.List;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.EntityKeyspaceRegistrationKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.EntityKeyspaceRegistrationValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.node.entityforward.CommittedEntityStake.committedEntityStake;

/// The test that decides whether a node with no forward target is mid-handoff (transient) or was never
/// told it serves the keyspace (terminal). Every row pairs a state that must answer true with the nearest
/// state that must not, so the predicate cannot drift to "always true" or "always false" unnoticed.
class CommittedEntityStakeTest {
    private static final String KEYSPACE = "orders";
    private static final NodeId SELF = new NodeId("node-self");
    private static final NodeId OTHER = new NodeId("node-other");

    @Test
    void committedHostRegistration_isAStake() {
        var store = emptyStore();

        seed(store, registrationKey(KEYSPACE, SELF), EntityKeyspaceRegistrationValue.entityKeyspaceRegistrationValue(8));

        assertThat(committedEntityStake(store, SELF).test(KEYSPACE)).isTrue();
        assertThat(committedEntityStake(store, OTHER).test(KEYSPACE))
            .as("another node's registration is not this node's stake — else the predicate is constant true")
            .isFalse();
    }

    /// The window between this node's registration prune and the leader's re-mint: the registration is
    /// gone, the ownership record still names this node. Answering terminally here is the 02w defect.
    @Test
    void committedOwnershipOfAnArc_isAStake_afterTheRegistrationIsPruned() {
        var store = emptyStore();

        seed(store, ownershipKey(EntityPartitionArc.arcName(KEYSPACE), 3), ownedBy(SELF));

        assertThat(committedEntityStake(store, SELF).test(KEYSPACE)).isTrue();
        assertThat(committedEntityStake(store, OTHER).test(KEYSPACE))
            .as("ownership naming another node is no stake for this one")
            .isFalse();
    }

    @Test
    void unknownKeyspace_isNoStake_evenWhenTheNodeOwnsOtherThings() {
        var store = emptyStore();

        seed(store, registrationKey("invoices", SELF), EntityKeyspaceRegistrationValue.entityKeyspaceRegistrationValue(8));
        seed(store, ownershipKey(EntityPartitionArc.arcName("invoices"), 0), ownedBy(SELF));

        assertThat(committedEntityStake(store, SELF).test("invoices")).as("armed: the other keyspace IS a stake").isTrue();
        assertThat(committedEntityStake(store, SELF).test(KEYSPACE)).isFalse();
        assertThat(committedEntityStake(emptyStore(), SELF).test(KEYSPACE)).isFalse();
    }

    /// An entity keyspace and a stream of the same bare name share the ownership record family; the
    /// `entity:` namespace is what keeps them apart, so a stream named `orders` owned here is no stake in
    /// the `orders` entity keyspace.
    @Test
    void aStreamOfTheSameBareName_isNoStake() {
        var store = emptyStore();

        seed(store, ownershipKey(KEYSPACE, 0), ownedBy(SELF));

        assertThat(committedEntityStake(store, SELF).test(KEYSPACE)).isFalse();
    }

    private static StreamPartitionOwnershipKey ownershipKey(String stream, int partition) {
        return StreamPartitionOwnershipKey.streamPartitionOwnershipKey(stream, partition);
    }

    private static StreamPartitionOwnershipValue ownedBy(NodeId owner) {
        return StreamPartitionOwnershipValue.streamPartitionOwnershipValue(owner,
                                                                           Epoch.ZERO,
                                                                           Epoch.ZERO.localCounter(),
                                                                           HlcTimestamp.ZERO);
    }

    private static EntityKeyspaceRegistrationKey registrationKey(String keyspace, NodeId node) {
        return EntityKeyspaceRegistrationKey.entityKeyspaceRegistrationKey(keyspace, node);
    }

    private static void seed(KVStore<AetherKey, AetherValue> store, AetherKey key, AetherValue value) {
        store.process(store.createBatch(List.of(new KVCommand.Put<AetherKey, AetherValue>(key, value))));
    }

    private static KVStore<AetherKey, AetherValue> emptyStore() {
        return new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                throw new UnsupportedOperationException("not used by this test");
            }
        };
    }
}
