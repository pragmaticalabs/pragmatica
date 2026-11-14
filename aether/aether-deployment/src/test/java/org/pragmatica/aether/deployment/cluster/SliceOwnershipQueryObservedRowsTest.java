// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.Generation;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.aether.slice.kvstore.SliceRowSource;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// FSM2 (deployment state machine spec 1.6.1, NT-110, NT-95): the drain guard and the ownership predicate read the current observed rows, and
/// the deployment map follows the rows the state feed shows. Each is red if the reader reads only `NodeArtifactKey` rows.
class SliceOwnershipQueryObservedRowsTest {
    private static final NodeId OWNER = NodeId.nodeId("core-1").unwrap();
    private static final NodeId PEER = NodeId.nodeId("core-2").unwrap();
    private static final NodeId THIRD = NodeId.nodeId("core-3").unwrap();
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:orders:1.0.0").unwrap();
    private static final Artifact OTHER = Artifact.artifact("org.example:billing:1.0.0").unwrap();

    private final MapStore store = new MapStore();
    private final Map<NodeId, Long> tokens = new HashMap<>();
    private final SliceRowSource rows = SliceRowSource.observed(node -> tokens.getOrDefault(node, 0L));

    @Test
    void ownsActiveSlices_readsAnObservedLiveRow() {
        store.put(SliceObservedKey.sliceObservedKey(OWNER, ARTIFACT), row(SliceObservedState.LOADING, 0L));

        assertThat(SliceOwnershipQuery.ownsActiveSlices(store, rows).test(OWNER)).isTrue();
    }

    /// NT-110: a tombstone-only node, or one with only stale rows, owns nothing.
    @Test
    void ownsActiveSlices_falseForATombstoneOnlyOrStaleOnlyNode() {
        store.put(SliceObservedKey.sliceObservedKey(OWNER, ARTIFACT), row(SliceObservedState.UNLOADED, 7L));
        store.put(SliceObservedKey.sliceObservedKey(PEER, ARTIFACT), row(SliceObservedState.ACTIVE, 7L));
        tokens.put(PEER, 9L);

        assertThat(SliceOwnershipQuery.ownsActiveSlices(store, rows).test(OWNER)).as("tombstone only").isFalse();
        assertThat(SliceOwnershipQuery.ownsActiveSlices(store, rows).test(PEER)).as("stale only").isFalse();
    }

    /// NT-110: a stale ACTIVE row is not counted in `remainingActive`, so draining the only current ACTIVE owner is refused.
    @Test
    void drainGuard_doesNotCountAStaleRowInRemainingActive() {
        store.put(SliceObservedKey.sliceObservedKey(OWNER, ARTIFACT), row(SliceObservedState.ACTIVE, 7L));
        store.put(SliceObservedKey.sliceObservedKey(PEER, ARTIFACT), row(SliceObservedState.ACTIVE, 5L));
        tokens.put(PEER, 6L);

        var refusal = SliceOwnershipQuery.minAvailableDrainGuard(store, rows).apply(OWNER, Set.of(PEER));

        assertThat(refusal.map(SliceOwnershipQuery.DrainRefusal::remainingActive)).isEqualTo(Option.some(0L));
    }

    /// C8: an unknown token reads current, so the peer's ACTIVE row is remaining capacity and the drain is allowed.
    @Test
    void drainGuard_countsAnUnknownTokenRow() {
        store.put(SliceObservedKey.sliceObservedKey(OWNER, ARTIFACT), row(SliceObservedState.ACTIVE, 7L));
        store.put(SliceObservedKey.sliceObservedKey(PEER, ARTIFACT), row(SliceObservedState.ACTIVE, 5L));

        assertThat(SliceOwnershipQuery.minAvailableDrainViolations(store, rows).apply(OWNER, Set.of(PEER))).isEmpty();
    }

    /// Ownership is per node: another node's live row does not make this node an owner.
    @Test
    void ownsActiveSlices_falseForANodeWhoseOnlyNeighbourHoldsALiveRow() {
        store.put(SliceObservedKey.sliceObservedKey(PEER, ARTIFACT), row(SliceObservedState.ACTIVE, 0L));

        assertThat(SliceOwnershipQuery.ownsActiveSlices(store, rows).test(OWNER)).isFalse();
    }

    /// Teardown/failed states are not ownership: a node holding only a current FAILED row owns nothing.
    @Test
    void ownsActiveSlices_falseForACurrentFailedRow() {
        store.put(SliceObservedKey.sliceObservedKey(OWNER, ARTIFACT), row(SliceObservedState.FAILED, 0L));

        assertThat(SliceOwnershipQuery.ownsActiveSlices(store, rows).test(OWNER)).isFalse();
    }

    /// The drain guard is evaluated only for artifacts the candidate hosts: an artifact held only by a third node is none of its business.
    @Test
    void drainViolations_ignoreAnArtifactTheCandidateDoesNotHost() {
        store.put(SliceObservedKey.sliceObservedKey(OWNER, ARTIFACT), row(SliceObservedState.ACTIVE, 0L));
        store.put(SliceObservedKey.sliceObservedKey(PEER, ARTIFACT), row(SliceObservedState.ACTIVE, 0L));
        store.put(SliceObservedKey.sliceObservedKey(THIRD, OTHER), row(SliceObservedState.ACTIVE, 0L));

        assertThat(SliceOwnershipQuery.minAvailableDrainViolations(store, rows).apply(OWNER, Set.of(PEER))).isEmpty();
    }

    /// A current FAILED row is not a live placement: the candidate does not "host" that artifact, so it cannot block the drain.
    @Test
    void drainViolations_ignoreAnArtifactTheCandidateOnlyHoldsAFailedRowFor() {
        store.put(SliceObservedKey.sliceObservedKey(OWNER, ARTIFACT), row(SliceObservedState.ACTIVE, 0L));
        store.put(SliceObservedKey.sliceObservedKey(PEER, ARTIFACT), row(SliceObservedState.ACTIVE, 0L));
        store.put(SliceObservedKey.sliceObservedKey(OWNER, OTHER), row(SliceObservedState.FAILED, 0L));

        assertThat(SliceOwnershipQuery.minAvailableDrainViolations(store, rows).apply(OWNER, Set.of(PEER))).isEmpty();
    }

    /// The violations path reads the observed rows too: the sole ACTIVE owner of an artifact is reported.
    @Test
    void drainViolations_reportTheSoleObservedOwner() {
        store.put(SliceObservedKey.sliceObservedKey(OWNER, ARTIFACT), row(SliceObservedState.ACTIVE, 0L));

        var violations = SliceOwnershipQuery.minAvailableDrainViolations(store, rows).apply(OWNER, Set.of(PEER));

        assertThat(violations).extracting(SliceOwnershipQuery.DrainRefusal::artifact).containsExactly(ARTIFACT);
    }

    @Test
    void deploymentMap_followsShownRows_inTheLegacyVocabulary() {
        var map = DeploymentMap.deploymentMap();
        var key = SliceObservedKey.sliceObservedKey(OWNER, ARTIFACT);

        map.onObservedSlice(key, row(SliceObservedState.ACTIVATING, 0L));

        assertThat(map.byArtifact(ARTIFACT)).isEqualTo(Map.of(OWNER, SliceState.ACTIVATING));

        map.onObservedSliceGone(key);

        assertThat(map.byArtifact(ARTIFACT)).isEmpty();
    }

    private static SliceObservedValue row(SliceObservedState state, long token) {
        return new SliceObservedValue(state, 1L, new Generation(1L, 1L), new ProcessIncarnation(1L, token), Option.none(), false, 0, List.of(), 0L);
    }

    /// A store whose `forEach` and `get` read a plain map: the readers only scan and look up slice targets.
    private static final class MapStore extends KVStore<AetherKey, AetherValue> {
        private final Map<AetherKey, AetherValue> storage = new HashMap<>();

        MapStore() {
            super(null, null, null);
        }

        void put(AetherKey key, AetherValue value) {
            storage.put(key, value);
        }

        @Override
        public Option<AetherValue> get(AetherKey key) {
            return Option.option(storage.get(key));
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
