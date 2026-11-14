// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.Generation;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.aether.slice.kvstore.SliceRowSource;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// FSM2 (deployment state machine spec 1.6.1): the hosting hint names a node with a CURRENT ACTIVE observed row; a stale row, a tombstone and a
/// `NodeArtifactKey` row are never named. Red if the hint reads only `NodeArtifactKey` rows.
class ScheduledTaskRoutesObservedRowsTest {
    private static final NodeId SELF = NodeId.nodeId("core-1").unwrap();
    private static final NodeId HOST = NodeId.nodeId("core-2").unwrap();
    private static final NodeId STALE = NodeId.nodeId("core-3").unwrap();
    private static final NodeId REGISTERED_BY = NodeId.nodeId("core-4").unwrap();
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:orders:1.0.0").unwrap();

    private final MapStore store = new MapStore();
    private final Map<NodeId, Long> tokens = new HashMap<>();
    private final SliceRowSource rows = SliceRowSource.observed(node -> tokens.getOrDefault(node, 0L));

    @Test
    void hint_namesTheNodeWithACurrentActiveObservedRow() {
        store.put(SliceObservedKey.sliceObservedKey(HOST, ARTIFACT), row(SliceObservedState.ACTIVE, 0L));

        assertThat(ScheduledTaskRoutes.selectHostingHint(store, rows, SELF, ARTIFACT, REGISTERED_BY)).isEqualTo(HOST.id());
    }

    @Test
    void hint_skipsAStaleRowAndATombstone_andIgnoresNodeArtifactRows() {
        store.put(SliceObservedKey.sliceObservedKey(STALE, ARTIFACT), row(SliceObservedState.ACTIVE, 5L));
        store.put(SliceObservedKey.sliceObservedKey(HOST, ARTIFACT), row(SliceObservedState.UNLOADED, 0L));
        store.put(NodeArtifactKey.nodeArtifactKey(HOST, ARTIFACT), NodeArtifactValue.nodeArtifactValue(SliceState.ACTIVE));
        tokens.put(STALE, 9L);

        assertThat(ScheduledTaskRoutes.selectHostingHint(store, rows, SELF, ARTIFACT, REGISTERED_BY)).isEqualTo(REGISTERED_BY.id());
    }

    private static SliceObservedValue row(SliceObservedState state, long token) {
        return new SliceObservedValue(state, 1L, new Generation(1L, 1L), new ProcessIncarnation(1L, token), Option.none(), false, 0, List.of(), 0L);
    }

    private static final class MapStore extends KVStore<AetherKey, AetherValue> {
        private final Map<AetherKey, AetherValue> storage = new HashMap<>();

        MapStore() {
            super(null, null, null);
        }

        void put(AetherKey key, AetherValue value) {
            storage.put(key, value);
        }

        @Override
        public <KK, VV> void forEach(Class<KK> keyClass, Class<VV> valueClass, BiConsumer<KK, VV> consumer) {
            storage.forEach((key, value) -> accept(keyClass, valueClass, consumer, key, value));
        }

        @SuppressWarnings("unchecked")
        private static <KK, VV> void accept(Class<KK> keyClass, Class<VV> valueClass, BiConsumer<KK, VV> consumer, Object key, Object value) {
            if (keyClass.isInstance(key) && valueClass.isInstance(value)) {
                consumer.accept((KK) key, (VV) value);
            }
        }
    }
}
