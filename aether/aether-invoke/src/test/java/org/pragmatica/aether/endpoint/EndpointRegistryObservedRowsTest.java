// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.endpoint;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeDepartureKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureCause;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureState;
import org.pragmatica.aether.slice.kvstore.AetherValue.Generation;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeDepartureValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.aether.slice.kvstore.ObservedSliceFeed;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// FSM2 (deployment state machine spec 1.6.1, R-1.6.6, NT-95, NT-117): the endpoint registry on the traffic feed of the observed rows. Every
/// test is red if the registry reads only `NodeArtifactKey` rows, which FSM2 never writes.
class EndpointRegistryObservedRowsTest {
    private static final NodeId NODE = NodeId.nodeId("core-1").unwrap();
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:orders:1.0.0").unwrap();
    private static final MethodName METHOD = MethodName.methodName("handle").unwrap();
    private static final NodeId PEER = NodeId.nodeId("core-2").unwrap();
    private static final Artifact OTHER = Artifact.artifact("org.example:billing:1.0.0").unwrap();
    private static final SliceObservedKey KEY = SliceObservedKey.sliceObservedKey(NODE, ARTIFACT);

    private final Map<NodeId, Long> tokens = new HashMap<>();
    private final EndpointRegistry registry = EndpointRegistry.endpointRegistry();
    private final ObservedSliceFeed feed = ObservedSliceFeed.trafficFeed(node -> tokens.getOrDefault(node, 0L), List.of(registry));

    @Test
    void observedActiveRow_registersItsMethods() {
        feed.onObservedPut(put(SliceObservedState.ACTIVE, 7L));

        assertThat(routedNodes()).containsExactly(NODE);
    }

    @Test
    void observedRowLeavingActive_unregisters() {
        feed.onObservedPut(put(SliceObservedState.ACTIVE, 7L));
        feed.onObservedPut(put(SliceObservedState.DEACTIVATING, 7L));

        assertThat(routedNodes()).isEmpty();
    }

    @Test
    void tombstone_unregisters() {
        feed.onObservedPut(put(SliceObservedState.ACTIVE, 7L));
        feed.onObservedPut(put(SliceObservedState.UNLOADED, 7L));

        assertThat(routedNodes()).isEmpty();
    }

    /// NT-117: no route to a node after its DEPARTED record commits; the record superseded (ADMITTED) routes again.
    @Test
    void departedNode_isNotRouted_untilItsRecordIsSuperseded() {
        feed.onObservedPut(put(SliceObservedState.ACTIVE, 7L));
        feed.onDeparturePut(departure(DepartureState.DEPARTED, DepartureCause.STALE));

        assertThat(routedNodes()).as("DEPARTED, including a C17s (STALE) record").isEmpty();

        feed.onDeparturePut(departure(DepartureState.ADMITTED, DepartureCause.STALE));

        assertThat(routedNodes()).containsExactly(NODE);
    }

    /// NT-117: no route to a row stale on positive evidence (both tokens known and different).
    @Test
    void rowStaleOnPositiveEvidence_isNotRouted() {
        tokens.put(NODE, 9L);
        feed.onObservedPut(put(SliceObservedState.ACTIVE, 7L));

        assertThat(routedNodes()).isEmpty();
    }

    /// NT-117 unknown-token arm (C8): a registry with no token for the node keeps routing to its rows.
    @Test
    void unknownToken_keepsRouting() {
        feed.onObservedPut(put(SliceObservedState.ACTIVE, 7L));

        assertThat(routedNodes()).containsExactly(NODE);
    }

    /// Unregistering is scoped to the (node, artifact) pair: the same artifact on another node, and another artifact on the same node, keep routing.
    @Test
    void rowLeavingActive_unregistersOnlyItsOwnNodeAndArtifactPair() {
        feed.onObservedPut(put(KEY, SliceObservedState.ACTIVE, 7L));
        feed.onObservedPut(put(SliceObservedKey.sliceObservedKey(PEER, ARTIFACT), SliceObservedState.ACTIVE, 7L));
        feed.onObservedPut(put(SliceObservedKey.sliceObservedKey(NODE, OTHER), SliceObservedState.ACTIVE, 7L));

        feed.onObservedPut(put(KEY, SliceObservedState.DEACTIVATING, 7L));

        assertThat(routedNodes()).as("same artifact, other node").containsExactly(PEER);
        assertThat(registry.findEndpoints(OTHER, METHOD)
                           .stream()
                           .map(EndpointRegistry.Endpoint::nodeId)
                           .toList()).as("same node, other artifact").containsExactly(NODE);
    }

    private List<NodeId> routedNodes() {
        return registry.findEndpoints(ARTIFACT, METHOD)
                       .stream()
                       .map(EndpointRegistry.Endpoint::nodeId)
                       .toList();
    }

    private static ValuePut<SliceObservedKey, SliceObservedValue> put(SliceObservedState state, long token) {
        return put(KEY, state, token);
    }

    private static ValuePut<SliceObservedKey, SliceObservedValue> put(SliceObservedKey key, SliceObservedState state, long token) {
        var row = new SliceObservedValue(state,
                                         1L,
                                         new Generation(1L, 1L),
                                         new ProcessIncarnation(1L, token),
                                         Option.none(),
                                         false,
                                         2,
                                         List.of("handle"),
                                         0L);

        return new ValuePut<>(new KVCommand.Put<>(key, row), Option.none());
    }

    private static ValuePut<NodeDepartureKey, NodeDepartureValue> departure(DepartureState state, DepartureCause cause) {
        var value = new NodeDepartureValue(state, 1L, new ProcessIncarnation(1L, 7L), Option.some(cause), 0L);

        return new ValuePut<>(new KVCommand.Put<>(NodeDepartureKey.nodeDepartureKey(NODE), value), Option.none());
    }
}
