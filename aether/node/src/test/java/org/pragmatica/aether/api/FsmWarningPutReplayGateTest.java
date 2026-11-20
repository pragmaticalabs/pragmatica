// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.node.NodeCodecs;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.FsmWarningKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.FsmWarningValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.aether.slice.stream.FrameworkStreamPublishers;
import org.pragmatica.aether.slice.stream.SystemStreams;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.serialization.FrameworkCodecs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// #3203 (CTO ruling 2026-11-20: rule E is a live-apply rule): a family warning is published from the committed `FsmWarningKey` put
/// only when that put is applied live. The aggregator's replay gate is wired to the store's own `KVStore::isReplaying`, as
/// ClusterEventsAssembly wires it, and the put reaches [ClusterEventAggregator#onFsmWarningPut] as AetherNode routes it, so a snapshot
/// the node installs (silently) and then replays publishes nothing: the row is adopted, never re-announced.
class FsmWarningPutReplayGateTest {
    private static final NodeId SELF = new NodeId("core-1");
    private static final SliceCodec CODEC = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
    private static final LeaderValue LEADER_VALUE = LeaderValue.leaderValue(SELF, 1L);
    private static final FsmWarningKey KEY = FsmWarningKey.fsmWarningKey("retry-exhausted-unsettled", "orders");
    private static final FsmWarningValue OPEN_ROW = FsmWarningValue.fsmWarningValue(Map.of(),
                                                                                    true,
                                                                                    "open",
                                                                                    1L,
                                                                                    new NodeId("core-2"),
                                                                                    new ProcessIncarnation(1_760_000_000_001L, 1L),
                                                                                    1L);

    /// A live committed put publishes its opener once.
    @Test
    void liveCommittedPut_publishesOnce() {
        var node = Node.node();

        node.commitLeader();
        node.commitRow();

        assertThat(node.published).as("the live put is published once").hasSize(1);
        assertThat(node.published.getFirst().details()).containsEntry("code", KEY.code());
    }

    /// The same row, installed by snapshot and replayed: the replayed put reaches `onFsmWarningPut` and publishes nothing. Red when
    /// emitCommitted's `replayingCheck` gate is removed (the replayed opener is published).
    @Test
    void replayedCommittedPut_publishesNothing() {
        var source = Node.node();

        source.commitLeader();
        source.commitRow();

        var node = Node.node();

        assertThat(node.store.restoreSnapshot(source.store.makeSnapshot().unwrap()).isSuccess()).as("precondition: the snapshot installs").isTrue();
        assertThat(node.replayedWarningPuts).as("control: the install is silent").isZero();
        node.store.replayNotifications();

        assertThat(node.replayedWarningPuts).as("control: the replay delivers the row's put while the store replays").isEqualTo(1);
        assertThat(node.published).as("a replayed put is adopted, never published").isEmpty();
    }

    /// One node: a real store, its router, and a real aggregator wired as ClusterEventsAssembly and AetherNode wire them.
    private static final class Node {
        final KVStore<AetherKey, AetherValue> store;
        final ClusterEventAggregator aggregator;
        final List<ClusterEvent.OperatorWarning> published = new ArrayList<>();
        int replayedWarningPuts;

        private Node() {
            var router = MessageRouter.mutable();

            store = new KVStore<>(router, CODEC, CODEC);
            aggregator = ClusterEventAggregator.clusterEventAggregator(() -> FrameworkStreamPublishers.<ClusterEvent>testPublisher(SystemStreams.CLUSTER_EVENTS,
                                                                                                                                   this::capture)
                                                                                                         .unwrap(),
                                                                       () -> null,
                                                                       () -> true,
                                                                       SELF,
                                                                       HlcClock.hlcClock(SELF),
                                                                       () -> 1,
                                                                       store::isReplaying,
                                                                       () -> true,
                                                                       () -> true);
            router.addRoute(ValuePut.class, this::dispatch);
        }

        static Node node() {
            return new Node();
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private void dispatch(ValuePut put) {
            aggregator.onLeaderCommitted(put);
            if (put.cause().key() instanceof FsmWarningKey) {
                replayedWarningPuts += store.isReplaying() ? 1 : 0;
                aggregator.onFsmWarningPut(put);
            }
        }

        private void capture(ClusterEvent event) {
            if (event instanceof ClusterEvent.OperatorWarning warning) {
                published.add(warning);
            }
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        void commitLeader() {
            store.process(store.createBatch((List) List.of(new KVCommand.Put<>(LeaderKey.INSTANCE, LEADER_VALUE))));
        }

        void commitRow() {
            store.process(store.createBatch(List.of(new KVCommand.LeaderTransaction<AetherKey, AetherValue>(KEY,
                                                                                                         UUID.randomUUID()
                                                                                                             .toString(),
                                                                                                         LEADER_VALUE,
                                                                                                         List.of(),
                                                                                                         List.of(new KVCommand.Mutation<>(KEY,
                                                                                                                                          none(),
                                                                                                                                          some(OPEN_ROW)))))));
        }
    }
}
