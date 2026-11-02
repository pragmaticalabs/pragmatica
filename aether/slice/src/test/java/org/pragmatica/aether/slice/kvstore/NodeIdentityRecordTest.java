// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import java.util.List;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterStateKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeIdentityKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.RuntimeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.IdentityFacet;
import org.pragmatica.aether.slice.kvstore.AetherValue.IdentityState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderAuthorized;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;

/// O3-1: the shape of the lifecycle record's key and value. The key is a [RuntimeKey] (never in a backup; the identity LEDGER, not the
/// KV, is what survives the loss of every holder's memory), the value is [LeaderAuthorized] (the applier drops a bare Put and a bare
/// Remove, so only the committed leader writes or removes it).
class NodeIdentityRecordTest {
    private static final NodeId NODE = NodeId.nodeId("core-7").unwrap();
    private static final LeaderValue LEADER = new LeaderValue(NODE, 5L);
    private static final ProcessIncarnation PROCESS = new ProcessIncarnation(1_760_000_000_123L, 0x5EEDFACE0BADL);

    private KVStore<StructuredKey, Object> store;

    @BeforeEach
    void setUp() {
        store = new KVStore<>(MessageRouter.mutable(), new Serializer() {
            @Override
            public <T> void write(ByteBuf buffer, T value) {}
        }, new Deserializer() {
            @Override
            public <T> T read(ByteBuf buffer) {
                return null;
            }
        });
        store.process(store.createBatch(List.of(new KVCommand.Put<>(LeaderKey.INSTANCE, LEADER))));
    }

    private static NodeLifecycleValue admitted() {
        return new NodeLifecycleValue(Option.some(new IdentityFacet(IdentityState.ADMITTED, 1L, PROCESS, "core", Option.none(), 10L)), Option.none());
    }

    @Test
    void key_roundTripsThroughItsString() {
        var key = NodeIdentityKey.nodeIdentityKey(NODE);

        assertThat(key.asString()).isEqualTo("node-identity/core-7");
        assertThat(NodeIdentityKey.nodeIdentityKey(key.asString()).unwrap()).isEqualTo(key);
    }

    @Test
    void key_refusesAWrongPrefixAndAnEmptyNodeId() {
        assertThat(NodeIdentityKey.nodeIdentityKey("node-departure/core-7").isFailure()).isTrue();
        assertThat(NodeIdentityKey.nodeIdentityKey("node-identity/").isFailure()).isTrue();
    }

    @Test
    void key_isARuntimeKey_neverBackedUp() {
        var key = NodeIdentityKey.nodeIdentityKey(NODE);

        assertThat(key).isInstanceOf(RuntimeKey.class).isNotInstanceOf(ClusterStateKey.class);
    }

    @Test
    void value_isLeaderAuthorized_enforcedByTheApplier() {
        var key = NodeIdentityKey.nodeIdentityKey(NODE);
        var value = admitted();

        assertThat(value).isInstanceOf(LeaderAuthorized.class);

        store.process(store.createBatch(List.of(new KVCommand.Put<StructuredKey, Object>(key, value))));
        assertThat(store.get(key).isEmpty()).as("a bare Put of the record is dropped").isTrue();

        var mutation = new KVCommand.Mutation<StructuredKey, Object>(key, Option.none(), Option.some(value));
        var committed = store.process(store.createBatch(List.of(new KVCommand.LeaderTransaction<StructuredKey, Object>(key, "tx", LEADER, List.of(), List.of(mutation)))));

        assertThat(((KVCommand.TransactionResult) committed.getFirst()).accepted()).as("the committed leader's transaction").isTrue();
        assertThat(store.get(key).or((Object) null)).isEqualTo(value);

        store.process(store.createBatch(List.of(new KVCommand.Remove<StructuredKey>(key))));
        assertThat(store.get(key).or((Object) null)).as("a bare Remove is dropped").isEqualTo(value);
    }
}
