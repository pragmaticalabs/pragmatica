// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.Generation;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// Spec 1.6.1, R-1.6.2, C8: the staleness rule every reader outside the FSMs applies, the state mapping, and the engine's row source.
class ObservedSlicesTest {
    private static final NodeId NODE = NodeId.nodeId("core-1").unwrap();
    private static final NodeId OTHER = NodeId.nodeId("core-2").unwrap();
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:orders:1.0.0").unwrap();

    static SliceObservedValue row(SliceObservedState state, long token) {
        return new SliceObservedValue(state,
                                      1L,
                                      new Generation(1L, 1L),
                                      new ProcessIncarnation(1L, token),
                                      Option.none(),
                                      false,
                                      3,
                                      List.of("handle"),
                                      0L);
    }

    @Test
    void isStale_onlyWhenBothTokensKnownAndDifferent() {
        assertThat(ObservedSlices.isStale(row(SliceObservedState.ACTIVE, 7L), 9L)).as("both known, different").isTrue();
        assertThat(ObservedSlices.isStale(row(SliceObservedState.ACTIVE, 7L), 7L)).as("equal").isFalse();
        assertThat(ObservedSlices.isStale(row(SliceObservedState.ACTIVE, 7L), 0L)).as("registry token unknown (C8)").isFalse();
        assertThat(ObservedSlices.isStale(row(SliceObservedState.ACTIVE, 0L), 9L)).as("row token unknown (C8)").isFalse();
    }

    @Test
    void sliceState_mapsEveryObservedStateAndReadsTheTombstoneAsAbsent() {
        assertThat(ObservedSlices.sliceState(SliceObservedState.LOADING)).isEqualTo(Option.some(SliceState.LOADING));
        assertThat(ObservedSlices.sliceState(SliceObservedState.LOADED)).isEqualTo(Option.some(SliceState.LOADED));
        assertThat(ObservedSlices.sliceState(SliceObservedState.ACTIVATING)).isEqualTo(Option.some(SliceState.ACTIVATING));
        assertThat(ObservedSlices.sliceState(SliceObservedState.ACTIVE)).isEqualTo(Option.some(SliceState.ACTIVE));
        assertThat(ObservedSlices.sliceState(SliceObservedState.DEACTIVATING)).isEqualTo(Option.some(SliceState.DEACTIVATING));
        assertThat(ObservedSlices.sliceState(SliceObservedState.FAILED)).isEqualTo(Option.some(SliceState.FAILED));
        assertThat(ObservedSlices.sliceState(SliceObservedState.UNLOADING)).isEqualTo(Option.some(SliceState.UNLOADING));
        assertThat(ObservedSlices.sliceState(SliceObservedState.UNKNOWN)).isEqualTo(Option.some(SliceState.UNKNOWN));
        assertThat(ObservedSlices.sliceState(SliceObservedState.UNLOADED)).isEqualTo(Option.none());
    }

    @Test
    void currentState_isAbsentForAStaleRow_andTheStateOtherwise() {
        assertThat(ObservedSlices.currentState(row(SliceObservedState.ACTIVE, 7L), 9L)).isEqualTo(Option.none());
        assertThat(ObservedSlices.currentState(row(SliceObservedState.ACTIVE, 7L), 0L)).isEqualTo(Option.some(SliceState.ACTIVE));
        assertThat(ObservedSlices.currentState(row(SliceObservedState.UNLOADED, 7L), 7L)).isEqualTo(Option.none());
    }

    @Test
    void observedSource_readsCurrentObservedRowsOnly_notLegacyRows() {
        var store = new MapStore();

        store.put(NodeArtifactKey.nodeArtifactKey(OTHER, ARTIFACT), NodeArtifactValue.nodeArtifactValue(SliceState.ACTIVE));
        store.put(SliceObservedKey.sliceObservedKey(NODE, ARTIFACT), row(SliceObservedState.ACTIVE, 0L));

        assertThat(rows(SliceRowSource.observed(_ -> 0L), store)).containsExactly("core-1 ACTIVE");
    }

    @Test
    void observedSource_skipsStaleRowsAndTombstones_andKeepsUnknownTokenRows() {
        var store = new MapStore();
        var third = NodeId.nodeId("core-3").unwrap();

        store.put(SliceObservedKey.sliceObservedKey(NODE, ARTIFACT), row(SliceObservedState.ACTIVE, 7L));
        store.put(SliceObservedKey.sliceObservedKey(OTHER, ARTIFACT), row(SliceObservedState.UNLOADED, 5L));
        store.put(SliceObservedKey.sliceObservedKey(third, ARTIFACT), row(SliceObservedState.LOADED, 4L));

        var tokens = Map.of(NODE, 9L, OTHER, 5L);

        assertThat(rows(SliceRowSource.observed(node -> tokens.getOrDefault(node, 0L)), store)).containsExactly("core-3 LOADED");
    }

    @Test
    void legacySource_readsNodeArtifactRowsOnly() {
        var store = new MapStore();

        store.put(NodeArtifactKey.nodeArtifactKey(OTHER, ARTIFACT), NodeArtifactValue.nodeArtifactValue(SliceState.LOADING));
        store.put(SliceObservedKey.sliceObservedKey(NODE, ARTIFACT), row(SliceObservedState.ACTIVE, 0L));

        assertThat(rows(SliceRowSource.legacy(), store)).containsExactly("core-2 LOADING");
    }

    private static List<String> rows(SliceRowSource source, KVStore<AetherKey, AetherValue> store) {
        var seen = new ArrayList<String>();

        source.forEachRow(store, (node, artifact, state) -> seen.add(node.id() + " " + state));

        return seen;
    }

    /// A store whose `forEach` reads a plain map: the row sources only scan.
    static final class MapStore extends KVStore<AetherKey, AetherValue> {
        private final Map<AetherKey, AetherValue> storage = new HashMap<>();

        MapStore() {
            super(null, null, null);
        }

        void put(AetherKey key, AetherValue value) {
            storage.put(key, value);
        }

        @Override
        @SuppressWarnings("unchecked")
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
