// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.deployment;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeDepartureKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureState;
import org.pragmatica.aether.slice.kvstore.AetherValue.Generation;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeDepartureValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.aether.slice.kvstore.ObservedSliceListener;
import org.pragmatica.cluster.state.kvstore.KVNotificationRouter;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// B21 (deployment state machine spec 1.6.1, R-1.6.6): the readers outside the two FSMs are bound by `DeploymentWiring.registerReaders` and its
/// row source by `sliceRows`, per engine. Each arm is red if the readers stay on the `NodeArtifactKey` rows under FSM2.
class DeploymentWiringReadersTest {
    private static final Artifact ORDERS = Artifact.artifact("org.example:orders:1.0.0").unwrap();
    private static final NodeId PEER = Fsm2Harness.PEER;

    /// Under FSM2 the state listeners follow the observed rows, the traffic listeners also the departure records; both read tokens through the seam.
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void fsm2_followsObservedRows_trafficHidesADepartedNode_stateDoesNot_staleRowIsGone() {
        try (var harness = new Fsm2Harness(DeploymentEngine.FSM2, false)) {
            var state = new Recorder();
            var traffic = new Recorder();

            wireReaders(harness, state, traffic);
            var key = SliceObservedKey.sliceObservedKey(PEER, ORDERS);

            harness.put(key, row(SliceObservedState.ACTIVE, 0L));

            assertThat(state.shown).as("state reader sees the row").containsExactly(key);
            assertThat(traffic.shown).as("traffic reader sees the row").containsExactly(key);

            harness.commitLeader();
            harness.leaderPut(NodeDepartureKey.nodeDepartureKey(PEER), departure(DepartureState.DEPARTED));

            assertThat(traffic.gone).as("DEPARTED record hides the node for the traffic reader").containsExactly(key);
            assertThat(state.gone).as("state reader ignores departure records").isEmpty();

            harness.leaderPut(NodeDepartureKey.nodeDepartureKey(PEER), departure(DepartureState.ADMITTED));

            assertThat(traffic.shown).as("supersession shows the row again").containsExactly(key, key);

            harness.bootTokens.admit(PEER, 99L);
            harness.put(key, row(SliceObservedState.ACTIVE, 2L, 5L));

            assertThat(state.gone).as("a row with a differing known token is stale: gone").containsExactly(key);
        }
    }

    @Test
    void legacy_registersNoObservedRoutes_andKeepsTheNodeArtifactRows() {
        try (var harness = new Fsm2Harness(DeploymentEngine.LEGACY, false)) {
            var state = new Recorder();
            var traffic = new Recorder();

            wireReaders(harness, state, traffic);
            harness.put(SliceObservedKey.sliceObservedKey(PEER, ORDERS), row(SliceObservedState.ACTIVE, 0L));
            harness.put(NodeArtifactKey.nodeArtifactKey(PEER, ORDERS), NodeArtifactValue.nodeArtifactValue(SliceState.ACTIVE));
            var rows = new ArrayList<SliceState>();

            harness.wiring.sliceRows().forEachRow(harness.store, (_, _, sliceState) -> rows.add(sliceState));

            assertThat(state.shown).as("no observed route under LEGACY").isEmpty();
            assertThat(traffic.shown).isEmpty();
            assertThat(rows).as("LEGACY source reads the NodeArtifactKey row only").containsExactly(SliceState.ACTIVE);
        }
    }

    /// The FSM2 row source reads the observed rows (a stale one is absent) and never the NodeArtifactKey rows.
    @Test
    void fsm2_sliceRowsReadObservedRows_notNodeArtifactRows() {
        try (var harness = new Fsm2Harness(DeploymentEngine.FSM2, false)) {
            harness.put(SliceObservedKey.sliceObservedKey(PEER, ORDERS), row(SliceObservedState.LOADING, 0L));
            harness.put(NodeArtifactKey.nodeArtifactKey(Fsm2Harness.SELF, ORDERS), NodeArtifactValue.nodeArtifactValue(SliceState.ACTIVE));
            var rows = new ArrayList<SliceState>();

            harness.wiring.sliceRows().forEachRow(harness.store, (_, _, sliceState) -> rows.add(sliceState));

            assertThat(rows).containsExactly(SliceState.LOADING);

            harness.bootTokens.admit(PEER, 99L);
            harness.put(SliceObservedKey.sliceObservedKey(PEER, ORDERS), row(SliceObservedState.ACTIVE, 2L, 5L));
            rows.clear();
            harness.wiring.sliceRows().forEachRow(harness.store, (_, _, sliceState) -> rows.add(sliceState));

            assertThat(rows).as("the stale row is absent").isEmpty();
        }
    }

    /// Removals are bound too: a removed observed row is gone for BOTH readers, and a removed departure record shows the node's rows again.
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void fsm2_removalsReachTheReaders_observedRowForBoth_departureRecordForTraffic() {
        try (var harness = new Fsm2Harness(DeploymentEngine.FSM2, false)) {
            var state = new Recorder();
            var traffic = new Recorder();

            wireReaders(harness, state, traffic);
            var key = SliceObservedKey.sliceObservedKey(PEER, ORDERS);

            harness.put(key, row(SliceObservedState.ACTIVE, 0L));
            harness.commitLeader();
            harness.leaderRemove(key);

            assertThat(state.gone).as("state reader: removed row is gone").containsExactly(key);
            assertThat(traffic.gone).as("traffic reader: removed row is gone").containsExactly(key);

            harness.put(key, row(SliceObservedState.ACTIVE, 2L, 0L));
            harness.commitLeader();
            harness.leaderPut(NodeDepartureKey.nodeDepartureKey(PEER), departure(DepartureState.DEPARTED));
            harness.leaderRemove(NodeDepartureKey.nodeDepartureKey(PEER));

            assertThat(traffic.shown).as("a removed departure record shows the rows again").hasSize(3);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void wireReaders(Fsm2Harness harness, ObservedSliceListener state, ObservedSliceListener traffic) {
        var builder = KVNotificationRouter.<AetherKey, AetherValue> builder(AetherKey.class);

        harness.wiring.registerReaders(builder, List.of(state), List.of(traffic));
        builder.build()
               .asRouteEntries()
               .forEach(entry -> entry.entries()
                                      .forEach(route -> harness.router.addRoute((Class) route.first(), (Consumer) route.last())));
    }

    private static SliceObservedValue row(SliceObservedState state, long token) {
        return row(state, 1L, token);
    }

    /// `version` is the version-fenced chain: the first write is 1, each later write is the committed version + 1.
    private static SliceObservedValue row(SliceObservedState state, long version, long token) {
        return new SliceObservedValue(state, version, new Generation(1L, 1L), new ProcessIncarnation(1L, token), Option.none(), false, 0, List.of(), 0L);
    }

    private static NodeDepartureValue departure(DepartureState state) {
        return new NodeDepartureValue(state, 1L, new ProcessIncarnation(1L, 0L), Option.none(), 0L);
    }

    private static final class Recorder implements ObservedSliceListener {
        final List<SliceObservedKey> shown = new ArrayList<>();
        final List<SliceObservedKey> gone = new ArrayList<>();

        @Override
        public void onObservedSlice(SliceObservedKey key, SliceObservedValue row) {
            shown.add(key);
        }

        @Override
        public void onObservedSliceGone(SliceObservedKey key) {
            gone.add(key);
        }
    }
}
