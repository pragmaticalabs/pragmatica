// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.controller.fsm;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.controller.AllInstancesFailedDetector;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/// F5b (pragmatica #2765; phase-3 design 8.2 D-10): the all-instances-failed detector's FSM2 source. Under FSM2 no
/// `NodeArtifactKey` is written, so the legacy source returns nothing and #1573 auto-rollback never fires; this source reads
/// the current `SliceObservedKey` rows (R-1.6.2) instead.
class ObservedActiveInstancesTest {
    private static final NodeId SELF = NodeId.nodeId("observed-active-self").unwrap();
    private static final NodeId A = NodeId.nodeId("observed-active-a").unwrap();
    private static final NodeId B = NodeId.nodeId("observed-active-b").unwrap();
    private static final NodeId C = NodeId.nodeId("observed-active-c").unwrap();
    private static final Artifact V1 = Artifact.artifact("org.example:observed:1.0.0").unwrap();
    private static final Artifact V2 = Artifact.artifact("org.example:observed:2.0.0").unwrap();
    private static final long TOKEN = 11L;

    @Test
    void observedActiveInstances_activeCurrentRowsOnCountedNodes_areTheHosts() {
        var store = storeWith(Map.of(new SliceObservedKey(A, V1), SliceObservedState.ACTIVE,
                                     new SliceObservedKey(B, V1), SliceObservedState.ACTIVE));

        assertThat(AllInstancesFailedDetector.observedActiveInstances(store, _ -> true, _ -> TOKEN))
            .isEqualTo(Map.of(V1, Set.of(A, B)));
    }

    /// A version with rows, none ACTIVE, is present with no host, as the legacy source has it, so the detector clears its latch.
    @Test
    void observedActiveInstances_nonActiveRows_versionPresentWithoutHosts() {
        var store = storeWith(Map.of(new SliceObservedKey(A, V1), SliceObservedState.ACTIVE,
                                     new SliceObservedKey(A, V2), SliceObservedState.LOADING,
                                     new SliceObservedKey(B, V2), SliceObservedState.FAILED));

        assertThat(AllInstancesFailedDetector.observedActiveInstances(store, _ -> true, _ -> TOKEN))
            .isEqualTo(Map.of(V1, Set.of(A), V2, Set.of()));
    }

    @Test
    void observedActiveInstances_uncountedNode_isNoHost() {
        var store = storeWith(Map.of(new SliceObservedKey(A, V1), SliceObservedState.ACTIVE,
                                     new SliceObservedKey(C, V1), SliceObservedState.ACTIVE));

        assertThat(AllInstancesFailedDetector.observedActiveInstances(store, node -> !node.equals(C), _ -> TOKEN))
            .isEqualTo(Map.of(V1, Set.of(A)));
    }

    /// R-1.6.2: a row whose boot token differs from the node's known admitted token is stale and reads as absent.
    @Test
    void observedActiveInstances_staleRow_readsAsAbsent() {
        var store = storeWith(Map.of(new SliceObservedKey(A, V1), SliceObservedState.ACTIVE,
                                     new SliceObservedKey(B, V2), SliceObservedState.ACTIVE));

        assertThat(AllInstancesFailedDetector.observedActiveInstances(store,
                                                                      _ -> true,
                                                                      node -> node.equals(B)
                                                                              ? TOKEN + 1
                                                                              : TOKEN))
            .isEqualTo(Map.of(V1, Set.of(A)));
    }

    /// R-1.6.2 (C8): staleness fails open; an unknown admitted token (0) leaves the row current.
    @Test
    void observedActiveInstances_unknownAdmittedToken_rowIsCurrent() {
        var store = storeWith(Map.of(new SliceObservedKey(A, V1), SliceObservedState.ACTIVE));

        assertThat(AllInstancesFailedDetector.observedActiveInstances(store, _ -> true, _ -> 0L))
            .isEqualTo(Map.of(V1, Set.of(A)));
    }

    @Test
    void observedActiveInstances_noRows_isEmpty() {
        assertThat(AllInstancesFailedDetector.observedActiveInstances(FencedTargetWrites.fencedStore(SELF),
                                                                      _ -> true,
                                                                      _ -> TOKEN)).isEmpty();
    }

    private static KVStore<AetherKey, AetherValue> storeWith(Map<SliceObservedKey, SliceObservedState> rows) {
        var store = FencedTargetWrites.fencedStore(SELF);

        rows.forEach((key, state) -> store.process(store.createBatch(List.<KVCommand<AetherKey>> of(new KVCommand.Put<AetherKey, AetherValue>(key,
                                                                                                                                              ControlLoopObservedSourceTest.row(state,
                                                                                                                                                                                TOKEN))))));

        return store;
    }
}
