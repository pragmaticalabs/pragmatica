// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;

/// #2077: the ISR a promotion decision reads is the committed record's own, straight from the store.
class KvCommittedStreamOwnerSourceTest {
    private static final String STREAM = "orders";
    private static final NodeId XX = NodeId.nodeId("node-xx").unwrap();
    private static final NodeId YY = NodeId.nodeId("node-yy").unwrap();

    private KVStore<AetherKey, AetherValue> kvStore;

    @BeforeEach
    void setUp() {
        kvStore = new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
    }

    @Test
    void committedIsr_noRecord_isEmpty() {
        assertThat(KvCommittedStreamOwnerSource.kvCommittedStreamOwnerSource(kvStore).committedIsr(STREAM, 0)).isEmpty();
    }

    @Test
    void committedIsr_returnsTheRecordsIsr_evenWhenTheOwnerIsNotAmongTheLiveMembers() {
        var record = StreamPartitionOwnershipValue.streamPartitionOwnershipValue(XX, Epoch.epoch(1L, 1L, 1L), 1L, HlcTimestamp.ZERO, List.of(XX, YY), 3L);

        kvStore.process(kvStore.createBatch(List.<KVCommand<AetherKey>>of(new KVCommand.Put<>(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(STREAM, 0), record))));

        assertThat(KvCommittedStreamOwnerSource.kvCommittedStreamOwnerSource(kvStore).committedIsr(STREAM, 0)).containsExactly(XX, YY);
        assertThat(KvCommittedStreamOwnerSource.kvCommittedStreamOwnerSource(kvStore).committedIsr(STREAM, 1)).as("another partition").isEmpty();
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
                return null;
            }
        };
    }
}
