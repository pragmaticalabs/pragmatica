// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.deployment;

import java.util.List;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterIncarnationKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterIncarnationValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;

/// F1 mutation survivor pin (run s1, `nodedep-EngineMarkerGuard-00`): the production reader reports an incarnation exactly when one is
/// committed in the node's mirror. Red when `incarnationPresent` is inverted. The guard's own suites drive it through hand-written readers, and
/// the production harness always commits the marker beside the incarnation, so the inversion never changed a verdict there.
class EngineMarkerGuardS12PinsTest {
    private final KVStore<AetherKey, AetherValue> store = new KVStore<>(MessageRouter.mutable(), new Serializer() {
        @Override
        public <T> void write(ByteBuf byteBuf, T object) {}
    }, new Deserializer() {
        @Override
        public <T> T read(ByteBuf byteBuf) {
            return null;
        }
    });

    @Test
    void readerOver_reportsNoIncarnation_onAnEmptyMirror() {
        assertThat(EngineMarkerGuard.readerOver(store).incarnationPresent()).isFalse();
    }

    @Test
    void readerOver_reportsTheIncarnation_onceOneIsCommitted() {
        store.process(store.createBatch(List.of(new KVCommand.Put<>(ClusterIncarnationKey.clusterIncarnationKey(),
                                                                    ClusterIncarnationValue.genesis("lineage-pin", "id-pin")))));

        assertThat(EngineMarkerGuard.readerOver(store).incarnationPresent()).isTrue();
    }
}
