// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.metadata;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey.EndpointKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceDesiredKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.EndpointValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.Generation;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// FSM2 (deployment state machine spec 1.6.1): the pair keys of the observed-row family are indexed under their node and artifact scopes, a
/// desired goal of LOADED or ACTIVE assigns the artifact to the worker, and an ACTIVE observed row is an endpoint peer. Red if the index knows
/// only the `NodeArtifactKey` family.
class WorkerMetadataIndexObservedRowsTest {
    private static final NodeId WORKER = new NodeId("worker");
    private static final NodeId CORE = new NodeId("core");
    private static final Artifact ORDERS = Artifact.artifact("org.example:orders:1.0.0").unwrap();
    private static final EndpointKey ENDPOINT = new EndpointKey(ORDERS, MethodName.methodName("place").unwrap(), 0);

    @Test
    void activeOrLoadedGoal_assignsTheArtifactToTheWorker_unloadedGoalDoesNot() {
        for (var goal : List.of(SliceGoal.LOADED, SliceGoal.ACTIVE)) {
            var index = WorkerMetadataIndex.workerMetadataIndex();

            index.put(ENDPOINT, new EndpointValue(CORE));
            index.put(new SliceDesiredKey(WORKER, ORDERS), new SliceDesiredValue(goal, new Generation(1L, 1L), 1L));

            assertThat(scopeKeys(index)).as("goal %s", goal).contains(ENDPOINT);
        }

        var unloaded = WorkerMetadataIndex.workerMetadataIndex();

        unloaded.put(ENDPOINT, new EndpointValue(CORE));
        unloaded.put(new SliceDesiredKey(WORKER, ORDERS), new SliceDesiredValue(SliceGoal.UNLOADED, new Generation(1L, 1L), 1L));

        assertThat(scopeKeys(unloaded)).as("goal UNLOADED assigns nothing").doesNotContain(ENDPOINT);
    }

    @Test
    void activeObservedRow_isAnEndpointPeer_otherStatesAreNot() {
        var index = WorkerMetadataIndex.workerMetadataIndex();

        index.put(new SliceDesiredKey(WORKER, ORDERS), new SliceDesiredValue(SliceGoal.ACTIVE, new Generation(1L, 1L), 1L));
        index.put(SliceObservedKey.sliceObservedKey(CORE, ORDERS), observed(SliceObservedState.LOADING));

        assertThat(index.endpointPeersForWorker(WORKER)).isEmpty();

        index.put(SliceObservedKey.sliceObservedKey(CORE, ORDERS), observed(SliceObservedState.ACTIVE));

        assertThat(index.endpointPeersForWorker(WORKER)).containsExactly(CORE);
    }

    private static java.util.Set<org.pragmatica.cluster.state.kvstore.StructuredKey> scopeKeys(WorkerMetadataIndex index) {
        return index.scopesForWorker(WORKER)
                    .stream()
                    .flatMap(scope -> index.snapshot(scope).keySet().stream())
                    .collect(java.util.stream.Collectors.toSet());
    }

    private static SliceObservedValue observed(SliceObservedState state) {
        return new SliceObservedValue(state, 1L, new Generation(1L, 1L), new ProcessIncarnation(1L, 0L), Option.none(), false, 0, List.of(), 0L);
    }
}
