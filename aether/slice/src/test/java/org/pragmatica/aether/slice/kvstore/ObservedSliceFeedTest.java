// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeDepartureKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureCause;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeDepartureValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.kvstore.ObservedSlicesTest.row;

/// Spec 1.6.1 (NT-95), R-1.6.6 (NT-117), C8: what the readers outside the FSMs are shown of the observed rows.
class ObservedSliceFeedTest {
    private static final NodeId NODE = NodeId.nodeId("core-1").unwrap();
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:orders:1.0.0").unwrap();
    private static final NodeId PEER = NodeId.nodeId("core-2").unwrap();
    private static final SliceObservedKey KEY = SliceObservedKey.sliceObservedKey(NODE, ARTIFACT);

    private final Map<NodeId, Long> tokens = new HashMap<>();
    private final Recorder recorder = new Recorder();

    private ObservedSliceFeed state() {
        return ObservedSliceFeed.stateFeed(node -> tokens.getOrDefault(node, 0L), List.of(recorder));
    }

    private ObservedSliceFeed traffic() {
        return ObservedSliceFeed.trafficFeed(node -> tokens.getOrDefault(node, 0L), List.of(recorder));
    }

    @Test
    void onObservedPut_showsACurrentRow_withEveryWrite() {
        var feed = state();

        feed.onObservedPut(put(row(SliceObservedState.LOADING, 7L)));
        feed.onObservedPut(put(row(SliceObservedState.ACTIVE, 7L)));

        assertThat(recorder.events).containsExactly("show LOADING", "show ACTIVE");
    }

    @Test
    void onObservedPut_showsARowWhoseTokenIsUnknown_C8() {
        tokens.put(NODE, 9L);
        var feed = state();

        feed.onObservedPut(put(row(SliceObservedState.ACTIVE, 0L)));
        tokens.clear();
        feed.onObservedPut(put(row(SliceObservedState.ACTIVE, 7L)));

        assertThat(recorder.events).as("row token 0, then registry token 0: both current").containsExactly("show ACTIVE", "show ACTIVE");
    }

    @Test
    void onObservedPut_hidesARowStaleOnPositiveEvidence() {
        tokens.put(NODE, 9L);
        var feed = state();

        feed.onObservedPut(put(row(SliceObservedState.ACTIVE, 7L)));

        assertThat(recorder.events).isEmpty();
    }

    @Test
    void onObservedPut_aShownRowTurningStaleIsGone() {
        var feed = state();

        feed.onObservedPut(put(row(SliceObservedState.ACTIVE, 7L)));
        tokens.put(NODE, 9L);
        feed.onObservedPut(put(row(SliceObservedState.ACTIVE, 7L)));

        assertThat(recorder.events).containsExactly("show ACTIVE", "gone");
    }

    @Test
    void onObservedPut_theTombstoneIsGone() {
        var feed = state();

        feed.onObservedPut(put(row(SliceObservedState.ACTIVE, 7L)));
        feed.onObservedPut(put(row(SliceObservedState.UNLOADED, 7L)));

        assertThat(recorder.events).containsExactly("show ACTIVE", "gone");
    }

    @Test
    void onObservedRemove_isGoneOnlyForAShownRow() {
        var feed = state();

        feed.onObservedRemove(remove());
        feed.onObservedPut(put(row(SliceObservedState.UNLOADED, 7L)));
        feed.onObservedRemove(remove());
        feed.onObservedPut(put(row(SliceObservedState.ACTIVE, 7L)));
        feed.onObservedRemove(remove());
        feed.onObservedRemove(remove());

        assertThat(recorder.events).containsExactly("show ACTIVE", "gone");
    }

    @Test
    void trafficFeed_hidesADepartedNodesRows_andShowsThemWhenTheRecordIsSuperseded_NT117() {
        var feed = traffic();

        feed.onObservedPut(put(row(SliceObservedState.ACTIVE, 7L)));
        feed.onDeparturePut(departure(DepartureState.DEPARTED));
        feed.onObservedPut(put(row(SliceObservedState.ACTIVE, 7L)));
        feed.onDeparturePut(departure(DepartureState.ADMITTED));

        assertThat(recorder.events).containsExactly("show ACTIVE", "gone", "show ACTIVE");
    }

    @Test
    void trafficFeed_showsADepartedNodesRowsAgainWhenTheRecordIsRemoved() {
        var feed = traffic();

        feed.onObservedPut(put(row(SliceObservedState.ACTIVE, 7L)));
        feed.onDeparturePut(departure(DepartureState.DEPARTED));
        feed.onDepartureRemove(new ValueRemove<>(new KVCommand.Remove<>(NodeDepartureKey.nodeDepartureKey(NODE)), Option.none()));

        assertThat(recorder.events).containsExactly("show ACTIVE", "gone", "show ACTIVE");
    }

    @Test
    void stateFeed_ignoresDepartureRecords() {
        var feed = state();

        feed.onObservedPut(put(row(SliceObservedState.ACTIVE, 7L)));
        feed.onDeparturePut(departure(DepartureState.DEPARTED));

        assertThat(feed.excludesDeparted()).isFalse();
        assertThat(traffic().excludesDeparted()).isTrue();
        assertThat(recorder.events).containsExactly("show ACTIVE");
    }

    /// A removed row is forgotten: a later departure record for its node does not bring it back.
    @Test
    void trafficFeed_aRemovedRowIsNotResurrectedByALaterDepartureRecord() {
        var feed = traffic();

        feed.onObservedPut(put(row(SliceObservedState.ACTIVE, 7L)));
        feed.onObservedRemove(remove());
        feed.onDeparturePut(departure(NODE, DepartureState.DEPARTED));
        feed.onDeparturePut(departure(NODE, DepartureState.ADMITTED));

        assertThat(recorder.events).containsExactly("show ACTIVE", "gone");
    }

    /// A departure record re-classifies only its own node's rows: another node's row keeps the classification it was delivered with
    /// (a token learned later does not flip it; see the lane's staleness-at-delivery decision).
    @Test
    void trafficFeed_aDepartureRecordLeavesAnotherNodesRowsAlone() {
        var feed = traffic();

        feed.onObservedPut(put(row(SliceObservedState.ACTIVE, 7L)));
        tokens.put(NODE, 9L);
        feed.onDeparturePut(departure(PEER, DepartureState.DEPARTED));

        assertThat(recorder.events).containsExactly("show ACTIVE");
    }

    private static ValuePut<SliceObservedKey, SliceObservedValue> put(SliceObservedValue row) {
        return new ValuePut<>(new KVCommand.Put<>(KEY, row), Option.none());
    }

    private static ValueRemove<SliceObservedKey, SliceObservedValue> remove() {
        return new ValueRemove<>(new KVCommand.Remove<>(KEY), Option.none());
    }

    private static ValuePut<NodeDepartureKey, NodeDepartureValue> departure(DepartureState state) {
        return departure(NODE, state);
    }

    private static ValuePut<NodeDepartureKey, NodeDepartureValue> departure(NodeId node, DepartureState state) {
        var value = new NodeDepartureValue(state,
                                           1L,
                                           new ProcessIncarnation(1L, 7L),
                                           Option.some(DepartureCause.NODE_REMOVED),
                                           0L);

        return new ValuePut<>(new KVCommand.Put<>(NodeDepartureKey.nodeDepartureKey(node), value), Option.none());
    }

    private static final class Recorder implements ObservedSliceListener {
        private final List<String> events = new ArrayList<>();

        @Override
        public void onObservedSlice(SliceObservedKey key, SliceObservedValue row) {
            events.add("show " + row.state());
        }

        @Override
        public void onObservedSliceGone(SliceObservedKey key) {
            events.add("gone");
        }
    }
}
