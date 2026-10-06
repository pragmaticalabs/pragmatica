// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.controller.RollbackEvent;
import org.pragmatica.aether.invoke.SliceFailureEvent;
import org.pragmatica.aether.node.NodeCodecs;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.stream.FrameworkStreamConsumer;
import org.pragmatica.aether.slice.PublishOutcomeUnknown;
import org.pragmatica.aether.slice.stream.FrameworkStreamPublisher;
import org.pragmatica.aether.slice.stream.FrameworkStreamPublishers;
import org.pragmatica.aether.slice.stream.SystemStreams;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.StreamPartitionManager.Exhaustion;
import org.pragmatica.aether.stream.SystemStreamFactories;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.consensus.topology.TransportObservation;
import org.pragmatica.consensus.topology.TransportObservation.ObservationSource;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;


/// B5b — cluster-events migrated onto the replicated partition transport. The aggregator now
/// publishes/consumes through a REAL single-partition `system:cluster-events:1.0.0` stream managed
/// by a {@link StreamPartitionManager}, encoded with the node {@link SliceCodec} (which carries the
/// generated `ApiCodecsNode.CODECS`, B5a), and wired via {@link SystemStreamFactories}. This drives
/// the full create -> publish -> codec -> store -> fetch path, plus the owner-gated emit (folds B3)
/// and the production count/byte/age retention.
class ClusterEventAggregatorTest {

    private static final NodeId SELF = new NodeId("self-node");
    private static final Artifact ROLLBACK_ARTIFACT = Artifact.artifact("org.example:svc:1.0.0").unwrap();

    /// Node runtime codec, built exactly as production builds it. Includes the generated
    /// ClusterEvent codecs, so it can encode/decode the sealed hierarchy over the byte[] transport.
    private static final SliceCodec CODEC = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());

    private static final BooleanSupplier OWNER = () -> true;
    private static final BooleanSupplier NOT_OWNER = () -> false;
    private static final BooleanSupplier LEADER = () -> true;
    private static final BooleanSupplier NOT_LEADER = () -> false;

    private record Harness(ClusterEventAggregator aggregator,
                           HlcClock hlc,
                           StreamPartitionManager manager,
                           AtomicReference<FrameworkStreamPublisher<ClusterEvent>> publisher) {
        static Harness create(RetentionPolicy retention, BooleanSupplier ownerCheck) {
            return create(retention, ownerCheck, () -> false);
        }

        static Harness create(RetentionPolicy retention, BooleanSupplier ownerCheck, BooleanSupplier replayingCheck) {
            return create(retention, ownerCheck, replayingCheck, LEADER);
        }

        static Harness create(RetentionPolicy retention,
                              BooleanSupplier ownerCheck,
                              BooleanSupplier replayingCheck,
                              BooleanSupplier leaderCheck) {
            return create(retention, ownerCheck, replayingCheck, leaderCheck, HlcClock.hlcClock(SELF));
        }

        static Harness create(RetentionPolicy retention,
                              BooleanSupplier ownerCheck,
                              BooleanSupplier replayingCheck,
                              BooleanSupplier leaderCheck,
                              HlcClock hlc) {
            // Generous memory budget so calculateStreamBytes (64 + 24*maxCount + maxBytes) fits.
            var manager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);
            var config = StreamConfig.streamConfig(SystemStreams.CLUSTER_EVENTS.asString(),
                                                   1,
                                                   retention,
                                                   "earliest",
                                                   64L * 1024,
                                                   ConsistencyMode.EVENTUAL,
                                                   1);
            var publisher = SystemStreamFactories.<ClusterEvent>systemStreamPublisher(SystemStreams.CLUSTER_EVENTS,
                                                                                      manager,
                                                                                      CODEC,
                                                                                      config).unwrap();
            var consumer = SystemStreamFactories.<ClusterEvent>systemStreamConsumer(SystemStreams.CLUSTER_EVENTS,
                                                                                    manager,
                                                                                    CODEC,
                                                                                    CODEC,
                                                                                    config).unwrap();
            var pubRef = new AtomicReference<FrameworkStreamPublisher<ClusterEvent>>(publisher);
            var conRef = new AtomicReference<FrameworkStreamConsumer<ClusterEvent>>(consumer);
            var aggregator = ClusterEventAggregator.clusterEventAggregator(pubRef::get,
                                                                           conRef::get,
                                                                           ownerCheck,
                                                                           SELF,
                                                                           hlc,
                                                                           () -> 1,
                                                                           replayingCheck,
                                                                           leaderCheck);
            return new Harness(aggregator, hlc, manager, pubRef);
        }

        static Harness create() {
            return create(defaultRetention(), OWNER);
        }

        static RetentionPolicy defaultRetention() {
            return RetentionPolicy.retentionPolicy(10_000, 64L * 1024 * 1024, Long.MAX_VALUE, RetentionMode.ANY);
        }

        List<ClusterEvent> events() {
            return aggregator.events().await().or(List.of());
        }
    }

    private static TransportObservation.PeerJoined peerJoined(String id, List<NodeId> view) {
        return TransportObservation.peerJoined(new NodeId(id), view, ObservationSource.QUIC);
    }

    // --- round-trip through the partition transport ---------------------------------------------

    @Test
    void emittedEvent_surfacesInEvents() {
        var h = Harness.create();
        h.aggregator().onPeerJoined(peerJoined("peer-1", List.of(SELF, new NodeId("peer-1"))));

        var events = h.events();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst()).isInstanceOf(ClusterEvent.NodeJoined.class);
        assertThat(events.getFirst().details()).containsEntry("nodeId", "peer-1");
    }

    @Test
    void replayingNode_suppressesEmit() {
        // 7b: while this node is re-applying a snapshot/resync (KVStore.isReplaying() == true), replayed
        // subscribers must NOT re-publish historical cluster-events. Owner-check is true here, so only the
        // replay gate can suppress — proving the gate is independent of ownership.
        var h = Harness.create(Harness.defaultRetention(), OWNER, () -> true);
        h.aggregator().onPeerJoined(peerJoined("peer-1", List.of(SELF, new NodeId("peer-1"))));
        h.aggregator().onConfirmedDeparture(new NodeId("dead-1"));

        assertThat(h.events()).isEmpty();
    }

    @Test
    void membershipDecision_mapsToDepartureEvents() {
        var h = Harness.create();
        // #210: NodeRemoved is now a no-op here — NODE_FAILED moved to the FSM DEAD edge
        // (onConfirmedDeparture). Only the graceful NODE_LEFT decisions emit from onMembershipDecision.
        h.aggregator().onMembershipDecision(MembershipDecision.nodeRemoved(new NodeId("dead-1"), List.of(SELF)));
        h.aggregator().onMembershipDecision(MembershipDecision.nodeDecommissioned(new NodeId("gone-2"), List.of(SELF)));
        h.aggregator().onMembershipDecision(MembershipDecision.nodeDraining(new NodeId("drain-3"), List.of(SELF)));
        // Non-departure variants are ignored.
        h.aggregator().onMembershipDecision(MembershipDecision.nodeJoined(new NodeId("join-4"), List.of(SELF)));

        var events = h.events();
        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isInstanceOf(ClusterEvent.NodeLeft.class);
        assertThat(events.get(0).details()).containsEntry("nodeId", "gone-2");
        assertThat(events.get(1)).isInstanceOf(ClusterEvent.NodeLeft.class);
        assertThat(events.get(1).details()).containsEntry("nodeId", "drain-3");
    }

    /// #210: NODE_FAILED is sourced from the ungated FSM DEAD edge (onConfirmedDeparture) — the same
    /// confirmed-death signal that drives auto-heal — NOT the quorum-gated MembershipDecision.NodeRemoved,
    /// which the projector drops during post-kill churn so the event never reached /api/events on cloud.
    @Test
    void confirmedDeparture_emitsNodeFailed() {
        var h = Harness.create();
        h.aggregator().onConfirmedDeparture(new NodeId("crashed-1"));

        var events = h.events();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst()).isInstanceOf(ClusterEvent.NodeFailed.class);
        assertThat(events.getFirst().severity()).isEqualTo(ClusterEvent.Severity.CRITICAL);
        assertThat(events.getFirst().details()).containsEntry("nodeId", "crashed-1");
    }

    @Test
    void eventsSince_filtersByTimestamp() throws InterruptedException {
        var h = Harness.create();
        h.aggregator().onPeerJoined(peerJoined("early", List.of(SELF)));
        Thread.sleep(5);
        var cutoff = Instant.now();
        Thread.sleep(5);
        h.aggregator().onPeerJoined(peerJoined("late", List.of(SELF)));

        var since = h.aggregator().eventsSince(cutoff).await().or(List.of());
        assertThat(since).hasSize(1);
        assertThat(since.getFirst().details()).containsEntry("nodeId", "late");
    }

    /// Codec-through-transport: a populated `details` map must survive encode -> store -> decode via
    /// the partition stream (not an in-heap object ring). Proves the byte[] codec path is live.
    @Test
    void populatedDetailsMap_survivesPartitionTransportRoundTrip() {
        var h = Harness.create();
        h.aggregator().onConfigChanged(OperationalEvent.ConfigChanged.configChanged("retention", "node", "update", "operator-x"));

        var events = h.events();
        assertThat(events).hasSize(1);
        var decoded = events.getFirst();
        assertThat(decoded).isInstanceOf(ClusterEvent.ConfigChanged.class);
        assertThat(decoded.details()).containsEntry("key", "retention")
                                     .containsEntry("scope", "node")
                                     .containsEntry("action", "update")
                                     .containsEntry("requestedBy", "operator-x");
    }

    /// #1777 R1b (owner rule): a DHT replication change entering and leaving the overdue condition reaches the
    /// cluster-events stream as the typed pair, with the change, its factors and the reason, through the codec transport.
    @Test
    void dhtReplicationUnsettledAndSettled_reachTheEventStream_withTheirDetails() {
        var h = Harness.create();
        h.aggregator().onDhtReplicationUnsettled(OperationalEvent.DhtReplicationUnsettled.dhtReplicationUnsettled(9,
                                                                                                                   3,
                                                                                                                   1,
                                                                                                                   "APPLYING",
                                                                                                                   1000,
                                                                                                                   "unsettled for longer than 5 minutes"));
        h.aggregator().onDhtReplicationSettled(OperationalEvent.DhtReplicationSettled.dhtReplicationSettled(9, 3, 1, 1000, "settled"));

        var events = h.events();

        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isInstanceOf(ClusterEvent.DhtReplicationUnsettled.class);
        assertThat(events.get(0).type()).isEqualTo("DHT_REPLICATION_UNSETTLED");
        assertThat(events.get(0).severity()).isEqualTo(ClusterEvent.Severity.WARNING);
        assertThat(events.get(0).details()).containsEntry("changeVersion", "9")
                                           .containsEntry("replicationFactor", "3")
                                           .containsEntry("confirmationFactor", "1")
                                           .containsEntry("stage", "APPLYING")
                                           .containsEntry("since", "1000");
        assertThat(events.get(1)).isInstanceOf(ClusterEvent.DhtReplicationSettled.class);
        assertThat(events.get(1).type()).isEqualTo("DHT_REPLICATION_SETTLED");
        assertThat(events.get(1).details()).containsEntry("reason", "settled");
    }

    /// #1777 (owner rule): a stale DHT writer's own announcement and its resolution reach the stream as the typed pair.
    @Test
    void dhtWriterStaleAndResolved_reachTheEventStream_withTheirDetails() {
        var h = Harness.create();
        h.aggregator().onDhtWriterStale(OperationalEvent.DhtWriterStale.dhtWriterStale("writer-1", 5, 1000));
        h.aggregator().onDhtWriterStaleResolved(OperationalEvent.DhtWriterStaleResolved.dhtWriterStaleResolved("writer-1", 5, 1000));

        var events = h.events();

        assertThat(events).hasSize(2);
        assertThat(events.get(0).type()).isEqualTo("DHT_WRITER_STALE");
        assertThat(events.get(0).severity()).isEqualTo(ClusterEvent.Severity.WARNING);
        assertThat(events.get(0).details()).containsEntry("nodeId", "writer-1")
                                           .containsEntry("fence", "5")
                                           .containsEntry("since", "1000");
        assertThat(events.get(1).type()).isEqualTo("DHT_WRITER_STALE_RESOLVED");
    }

    /// #1730 owner ruling: the stream failover refusal and its resolution reach the cluster-events stream as typed
    /// events carrying the stream, partition, owner, ISR, live set and reason, through the codec transport.
    @Test
    void streamFailoverRefusedAndResolved_reachTheEventStream_withTheirDetails() {
        var h = Harness.create();
        h.aggregator().onStreamFailoverRefused(OperationalEvent.StreamFailoverRefused.streamFailoverRefused("orders",
                                                                                                              2,
                                                                                                              "node-a",
                                                                                                              java.util.List.of("node-a", "node-b"),
                                                                                                              java.util.List.of("node-c"),
                                                                                                              "no live ISR",
                                                                                                              "refused-id"));
        h.aggregator().onStreamFailoverResolved(OperationalEvent.StreamFailoverResolved.streamFailoverResolved("orders",
                                                                                                                2,
                                                                                                                "node-b",
                                                                                                                java.util.List.of("node-b"),
                                                                                                                java.util.List.of("node-b", "node-c"),
                                                                                                                "elected",
                                                                                                                "resolved-id"));

        var events = h.events();

        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isInstanceOf(ClusterEvent.StreamFailoverRefused.class);
        assertThat(events.get(0).type()).isEqualTo("STREAM_FAILOVER_REFUSED");
        assertThat(events.get(0).severity()).isEqualTo(ClusterEvent.Severity.CRITICAL);
        assertThat(events.get(0).details()).containsEntry("stream", "orders")
                                           .containsEntry("partition", "2")
                                           .containsEntry("owner", "node-a")
                                           .containsEntry("isr", "node-a,node-b")
                                           .containsEntry("live", "node-c")
                                           .containsEntry("reason", "no live ISR")
                                           .containsEntry("eventId", "refused-id");
        assertThat(events.get(1)).isInstanceOf(ClusterEvent.StreamFailoverResolved.class);
        assertThat(events.get(1).details()).containsEntry("owner", "node-b");
    }

    /// #1883: the in-sync-set events reach the cluster-events stream as typed events, WARNING for the breach and INFO for
    /// the restoration, carrying the stream, partition, owner, ISR, fenced set and factor.
    @Test
    void streamIsrBelowMinimumAndRestored_reachTheEventStream_withTheirDetails() {
        var h = Harness.create();
        h.aggregator().onStreamIsrBelowMinimum(OperationalEvent.StreamIsrBelowMinimum.streamIsrBelowMinimum("orders",
                                                                                                            2,
                                                                                                            "node-a",
                                                                                                            java.util.List.of("node-a"),
                                                                                                            java.util.List.of("node-b"),
                                                                                                            2,
                                                                                                            "below-id"));
        h.aggregator().onStreamIsrRestored(OperationalEvent.StreamIsrRestored.streamIsrRestored("orders",
                                                                                                2,
                                                                                                "node-a",
                                                                                                java.util.List.of("node-a", "node-b"),
                                                                                                java.util.List.of(),
                                                                                                2,
                                                                                                "restored-id"));

        var events = h.events();

        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isInstanceOf(ClusterEvent.StreamIsrBelowMinimum.class);
        assertThat(events.get(0).type()).isEqualTo("STREAM_ISR_BELOW_MINIMUM");
        assertThat(events.get(0).severity()).isEqualTo(ClusterEvent.Severity.WARNING);
        assertThat(events.get(0).details()).containsEntry("stream", "orders")
                                           .containsEntry("partition", "2")
                                           .containsEntry("owner", "node-a")
                                           .containsEntry("isr", "node-a")
                                           .containsEntry("fenced", "node-b")
                                           .containsEntry("confirmationFactor", "2")
                                           .containsEntry("eventId", "below-id");
        assertThat(events.get(1)).isInstanceOf(ClusterEvent.StreamIsrRestored.class);
        assertThat(events.get(1).type()).isEqualTo("STREAM_ISR_RESTORED");
        assertThat(events.get(1).severity()).isEqualTo(ClusterEvent.Severity.INFO);
    }

    /// #1730 owner ruling, the cluster-wide path: EVERY node derives the failover event from the same committed
    /// ownership Put, and only the cluster-events partition owner publishes. The LEADER (which committed the refusal)
    /// is NOT that owner here, and the event still reaches the stream exactly once — on the owner.
    @Test
    void streamFailoverRefused_derivedOnEveryNode_publishedOnceByTheEventsOwner_notTheLeader() {
        var leader = Harness.create(Harness.defaultRetention(), NOT_OWNER);
        var eventsOwner = Harness.create(Harness.defaultRetention(), OWNER);
        var third = Harness.create(Harness.defaultRetention(), NOT_OWNER);
        var key = org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey.streamPartitionOwnershipKey("orders", 0);
        var before = org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue.streamPartitionOwnershipValue(new NodeId("node-a"),
                                                                                                                             org.pragmatica.aether.slice.generation.Epoch.ZERO,
                                                                                                                             1L,
                                                                                                                             org.pragmatica.hlc.HlcTimestamp.ZERO,
                                                                                                                             List.of(new NodeId("node-a"), new NodeId("node-b")),
                                                                                                                             2L);
        var committed = new org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut<>(new org.pragmatica.cluster.state.kvstore.KVCommand.Put<>(key,
                                                                                                                                                         before.withFailoverRefused(true)),
                                                                                                    org.pragmatica.lang.Option.some(before));

        for (var node : List.of(leader, eventsOwner, third)) {
            org.pragmatica.aether.node.StreamFailoverAnnouncer.streamFailoverAnnouncer(() -> List.of(new NodeId("node-c")),
                                                                                       event -> node.aggregator()
                                                                                                    .onStreamFailoverRefused((OperationalEvent.StreamFailoverRefused) event))
                                                         .onOwnershipPut(committed);
        }

        assertThat(leader.events()).as("the leader is not the events owner: it publishes nothing").isEmpty();
        assertThat(third.events()).isEmpty();
        assertThat(eventsOwner.events()).as("exactly one copy, on the events owner")
                                        .singleElement()
                                        .isInstanceOf(ClusterEvent.StreamFailoverRefused.class);
    }

    /// v1877's probe (round 3), adapted to the fix: it was red at `5b32414b5`, when only the committing leader announced
    /// and the owner gate dropped it there (0 published). Now the committed ownership Put reaches BOTH nodes — as every
    /// node's KV router delivers it — and the one that owns the events partition publishes it: exactly 1.
    @Test
    void v1877_failoverRefusal_committedByALeaderThatIsNotTheEventsOwner_isPublishedExactlyOnce() {
        var leaderNotOwner = Harness.create(Harness.defaultRetention(), NOT_OWNER);
        var ownerNotLeader = Harness.create(Harness.defaultRetention(), OWNER);

        for (var node : List.of(leaderNotOwner, ownerNotLeader)) {
            announcerInto(node).onOwnershipPut(committedRefusal());
        }

        assertThat(leaderNotOwner.events().size() + ownerNotLeader.events().size())
            .as("STREAM_FAILOVER_REFUSED published across the leader (not owner) and the owner (not leader)")
            .isEqualTo(1);
    }

    /// Control: a single node that is both leader and events owner publishes it.
    @Test
    void v1877_control_leaderIsTheEventsOwner_publishesIt() {
        var leaderOwner = Harness.create(Harness.defaultRetention(), OWNER);

        announcerInto(leaderOwner).onOwnershipPut(committedRefusal());

        assertThat(leaderOwner.events()).hasSize(1);
    }

    private static org.pragmatica.aether.node.StreamFailoverAnnouncer announcerInto(Harness node) {
        return org.pragmatica.aether.node.StreamFailoverAnnouncer.streamFailoverAnnouncer(() -> List.of(new NodeId("node-c")),
                                                                                          event -> node.aggregator()
                                                                                                       .onStreamFailoverRefused((OperationalEvent.StreamFailoverRefused) event));
    }

    private static org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut<org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey, org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue> committedRefusal() {
        var a = new NodeId("node-a");
        var before = org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue.streamPartitionOwnershipValue(a,
                                                                                                                             org.pragmatica.aether.slice.generation.Epoch.epoch(1L, 1L, 1L),
                                                                                                                             1L,
                                                                                                                             org.pragmatica.hlc.HlcTimestamp.ZERO,
                                                                                                                             List.of(a),
                                                                                                                             1L);
        var key = org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey.streamPartitionOwnershipKey("orders", 0);

        return new org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut<>(new org.pragmatica.cluster.state.kvstore.KVCommand.Put<>(key,
                                                                                                                                                before.withFailoverRefused(true)),
                                                                                           org.pragmatica.lang.Option.some(before));
    }

    /// The ISR events are derived on EVERY node from one committed Put and published once, by the events
    /// owner only (the leader and a third node publish nothing). Both legs: the breach and the restoration.
    @Test
    void streamIsrBelowAndRestored_derivedOnEveryNode_publishedOnceByTheEventsOwner() {
        var leader = Harness.create(Harness.defaultRetention(), NOT_OWNER);
        var eventsOwner = Harness.create(Harness.defaultRetention(), OWNER);
        var third = Harness.create(Harness.defaultRetention(), NOT_OWNER);
        var a = new NodeId("node-a");
        var b = new NodeId("node-b");
        var key = StreamPartitionOwnershipKey.streamPartitionOwnershipKey("orders", 0);
        var healthy = StreamPartitionOwnershipValue.streamPartitionOwnershipValue(a, Epoch.ZERO, 1L, HlcTimestamp.ZERO, List.of(a, b), 2L);
        var below = healthy.withIsrAndFenced(List.of(a), List.of(b));
        var restored = below.withIsrAndFenced(List.of(a, b), List.of());
        var breach = new ValuePut<>(new KVCommand.Put<>(key, below), Option.some(healthy));
        var resolution = new ValuePut<>(new KVCommand.Put<>(key, restored), Option.some(below));

        for (var node : List.of(leader, eventsOwner, third)) {
            var announcer = org.pragmatica.aether.node.StreamIsrAnnouncer.streamIsrAnnouncer(_ -> 2, _ -> 2, _ -> Option.none(), (_, _) -> Option.none(), event -> {
                switch (event) {
                    case OperationalEvent.StreamIsrBelowMinimum e -> node.aggregator().onStreamIsrBelowMinimum(e);
                    case OperationalEvent.StreamIsrRestored e -> node.aggregator().onStreamIsrRestored(e);
                    default -> throw new AssertionError("unexpected " + event);
                }
            });

            announcer.onOwnershipPut(breach);
            announcer.onOwnershipPut(resolution);
        }

        assertThat(leader.events()).as("not the events owner: publishes nothing").isEmpty();
        assertThat(third.events()).isEmpty();
        assertThat(eventsOwner.events()).as("exactly one breach and one restoration, on the events owner")
                                        .extracting(ClusterEvent::type)
                                        .containsExactly("STREAM_ISR_BELOW_MINIMUM", "STREAM_ISR_RESTORED");
    }

    @Test
    void streamConfigChangeNotApplied_reachesTheEventStream_asAWarning_withItsDetails() {
        var h = Harness.create();

        h.aggregator().onStreamConfigChangeNotApplied(OperationalEvent.StreamConfigChangeNotApplied.streamConfigChangeNotApplied("orders",
                                                                                                                                 1,
                                                                                                                                 3,
                                                                                                                                 "durability only increases online",
                                                                                                                                 "cfg-id"));

        assertThat(h.events()).singleElement().satisfies(event -> {
            assertThat(event).isInstanceOf(ClusterEvent.StreamConfigChangeNotApplied.class);
            assertThat(event.type()).isEqualTo("STREAM_CONFIG_CHANGE_NOT_APPLIED");
            assertThat(event.severity()).isEqualTo(ClusterEvent.Severity.WARNING);
            assertThat(event.details()).containsEntry("stream", "orders")
                                       .containsEntry("requestedConfirmationFactor", "1")
                                       .containsEntry("effectiveConfirmationFactor", "3")
                                       .containsEntry("reason", "durability only increases online")
                                       .containsEntry("eventId", "cfg-id");
        });
    }

    /// STREAM_CONFIG_CHANGE_NOT_APPLIED is derived on every node from the committed config Put, so it is
    /// published by the events owner only: a non-owner (the leader, a third node) publishes nothing.
    @Test
    void streamConfigChangeNotApplied_derivedOnEveryNode_publishedOnlyByTheEventsOwner() {
        var eventsOwner = Harness.create(Harness.defaultRetention(), OWNER);
        var notOwner = Harness.create(Harness.defaultRetention(), NOT_OWNER);

        for (var node : List.of(eventsOwner, notOwner)) {
            node.aggregator().onStreamConfigChangeNotApplied(OperationalEvent.StreamConfigChangeNotApplied.streamConfigChangeNotApplied("orders",
                                                                                                                                      1,
                                                                                                                                      3,
                                                                                                                                      "durability only increases online",
                                                                                                                                      "cfg-id"));
        }

        assertThat(notOwner.events()).as("not the events owner: publishes nothing").isEmpty();
        assertThat(eventsOwner.events()).singleElement().isInstanceOf(ClusterEvent.StreamConfigChangeNotApplied.class);
    }

    /// #1883 F3: during a membership change two nodes can both pass the events-owner gate and each publish the event
    /// derived from one committed Put. The event carries a deterministic `eventId` that the aggregator keeps, so the read
    /// collapses the two copies; an event with another id (another transition) stays.
    @Test
    void twoCopiesOfOneDerivedEvent_shareTheirPresetEventId_andReadAsOne() {
        var h = Harness.create();
        var isr = java.util.List.of("node-a");
        var fenced = java.util.List.of("node-b");

        h.aggregator().onStreamIsrBelowMinimum(OperationalEvent.StreamIsrBelowMinimum.streamIsrBelowMinimum("orders", 2, "node-a", isr, fenced, 2, "same-put"));
        h.aggregator().onStreamIsrBelowMinimum(OperationalEvent.StreamIsrBelowMinimum.streamIsrBelowMinimum("orders", 2, "node-a", isr, fenced, 2, "same-put"));
        h.aggregator().onStreamIsrBelowMinimum(OperationalEvent.StreamIsrBelowMinimum.streamIsrBelowMinimum("orders", 2, "node-a", isr, fenced, 2, "next-put"));
        h.aggregator().onStreamFailoverRefused(OperationalEvent.StreamFailoverRefused.streamFailoverRefused("orders", 2, "node-a", isr, fenced, "r", "refusal"));
        h.aggregator().onStreamFailoverRefused(OperationalEvent.StreamFailoverRefused.streamFailoverRefused("orders", 2, "node-a", isr, fenced, "r", "refusal"));

        assertThat(h.events()).extracting(e -> e.details().get("eventId"))
                              .as("same id read once, different id kept, failover events the same")
                              .containsExactlyInAnyOrder("same-put", "next-put", "refusal");
    }

    // --- owner-gated emit (operational events: config/deploy/scale/blueprint stay owner-gated) -----

    @Test
    void owner_emits() {
        var h = Harness.create(Harness.defaultRetention(), OWNER);
        h.aggregator().onConfigChanged(OperationalEvent.ConfigChanged.configChanged("retention", "node", "update", "op"));
        assertThat(h.events()).hasSize(1);
    }

    @Test
    void nonOwner_suppressesEmit() {
        var h = Harness.create(Harness.defaultRetention(), NOT_OWNER);
        h.aggregator().onConfigChanged(OperationalEvent.ConfigChanged.configChanged("retention", "node", "update", "op"));
        // Non-owner publishes nothing for OWNER-gated operational events — the partition stays empty.
        // (Cluster-canonical events — membership/leader/quorum/generation/lifecycle — are LEADER-gated;
        //  covered by the leader-gate tests below.)
        assertThat(h.events()).isEmpty();
    }

    // --- leader-gated departure emit (#94: NODE_FAILED delivery for replacement deaths) ----------

    /// Membership FAILURES route through {@link ClusterEventAggregator#onConfirmedDeparture}. Since
    /// #926 that path is UN-gated ({@code emitLocal}); this test additionally pins that the OWNER gate
    /// does not suppress it either. The just-failed node is frequently the cluster-events partition
    /// owner, so owner-gating would suppress its own `NODE_FAILED`.
    @Test
    void leader_emitsDeparture_evenWhenNotOwner() {
        var h = Harness.create(Harness.defaultRetention(), NOT_OWNER, () -> false, LEADER);
        h.aggregator().onConfirmedDeparture(new NodeId("dead"));
        var events = h.events();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst()).isInstanceOf(ClusterEvent.NodeFailed.class);
        assertThat(events.getFirst().details()).containsEntry("nodeId", "dead");
    }

    /// #926 — THE ticket, and the direct reversal of the contract this test file previously pinned.
    ///
    /// The replaced test (`nonLeader_suppressesDepartureEmit`) asserted that a non-leader observer must
    /// NOT emit `NODE_FAILED`, on the reasoning that the leader gate "is what collapses the fan-out to a
    /// single emit". That reasoning was correct about the fan-out and wrong about the cost: the FSM DEAD
    /// edge fires on EVERY node, so when NO node is leader the gate holds everywhere at once and the
    /// event is emitted NOWHERE. Measured on a five-node cluster over ten days: SWIM confirmed 8 faulty
    /// members, 1,297,717 leader-election lines were logged, and `NodeFailed` appeared 0 times.
    ///
    /// This test models the failing condition honestly. Every observer's `leaderCheck` is the CONSTANT
    /// `NOT_LEADER`, so "the cluster has no leader" is not merely true at the instant of one assertion —
    /// it holds by construction for every call in the window under test, and no election can complete
    /// behind the test's back. Owner-checks are deliberately mixed so that neither gate can be the one
    /// letting the event through.
    @Test
    void noLeaderAnywhere_stillEmitsNodeFailed_onEveryObserver() {
        var observers = List.of(Harness.create(Harness.defaultRetention(), OWNER, () -> false, NOT_LEADER),
                                Harness.create(Harness.defaultRetention(), NOT_OWNER, () -> false, NOT_LEADER),
                                Harness.create(Harness.defaultRetention(), NOT_OWNER, () -> false, NOT_LEADER));

        observers.forEach(h -> h.aggregator().onConfirmedDeparture(new NodeId("dead")));

        for (var h : observers) {
            var events = h.events();
            assertThat(events).hasSize(1);
            assertThat(events.getFirst()).isInstanceOf(ClusterEvent.NodeFailed.class);
            assertThat(events.getFirst().details()).containsEntry("nodeId", "dead");
            // `observedBy` is what makes the bounded duplication collapsible by a consumer.
            assertThat(events.getFirst().details()).containsEntry("observedBy", SELF.id());
        }
    }

    /// #926 — the replay gate is the ONE suppression that must survive un-gating. Without this, moving
    /// `NODE_FAILED` to `emitLocal` would re-publish historical departures on every snapshot/resync.
    /// `emitLocal` keeps `replayingCheck`; this pins that it still does, with no leader involved.
    @Test
    void noLeader_stillSuppressesDepartureDuringReplay() {
        var h = Harness.create(Harness.defaultRetention(), OWNER, () -> true, NOT_LEADER);
        h.aggregator().onConfirmedDeparture(new NodeId("dead"));
        assertThat(h.events()).isEmpty();
    }

    /// #926 — `LEADER_LOST` was unreachable by construction. It is emitted from the branch where
    /// `leaderId()` is EMPTY, and it went through `emitAsLeader`, so the event announcing "there is no
    /// leader" required the emitter to BE the leader. No node could ever satisfy both at once.
    @Test
    void noLeader_stillEmitsLeaderLost() {
        var h = Harness.create(Harness.defaultRetention(), NOT_OWNER, () -> false, NOT_LEADER);
        h.aggregator().onLeaderChange(LeaderNotification.leaderChange(Option.none(), false));

        var events = h.events();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst()).isInstanceOf(ClusterEvent.LeaderLost.class);
        assertThat(events.getFirst().details()).containsEntry("observedBy", SELF.id());
    }

    /// `LEADER_ELECTED` stays leader-gated — the new leader is the authoritative emitter of its own
    /// election and the gate holds for it. Pinned so the #926 change is not read as "un-gate everything".
    @Test
    void nonLeader_stillSuppressesLeaderElected() {
        var h = Harness.create(Harness.defaultRetention(), OWNER, () -> false, NOT_LEADER);
        h.aggregator().onLeaderChange(LeaderNotification.leaderChange(Option.some(new NodeId("other")), false));
        assertThat(h.events()).isEmpty();
    }

    /// #926 — `QUORUM_LOST` is the most severe event this class emits and was the least emittable: a
    /// cluster that has gone PASSIVE cannot commit through consensus and so cannot sustain a leader
    /// lease, meaning `leaderCheck` is false on every node exactly when quorum is lost.
    @Test
    void noLeader_stillEmitsQuorumLost() {
        var h = Harness.create(Harness.defaultRetention(), NOT_OWNER, () -> false, NOT_LEADER);
        h.aggregator().onQuorumStateChange(ClusterStateNotification.passive());

        var events = h.events();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst()).isInstanceOf(ClusterEvent.QuorumLost.class);
        assertThat(events.getFirst().severity()).isEqualTo(ClusterEvent.Severity.CRITICAL);
    }

    /// #926 — the recovery half. Quorum forms BEFORE a leader is elected, so the old gate dropped this
    /// notice at the one moment it was guaranteed false. Un-gating the loss while leaving the recovery
    /// gated would be worse than fixing neither: an operator would watch the cluster enter "quorum lost"
    /// and never see it leave. A failure signal is only usable if its recovery signal is as reachable.
    @Test
    void noLeader_stillEmitsQuorumEstablished() {
        var h = Harness.create(Harness.defaultRetention(), NOT_OWNER, () -> false, NOT_LEADER);
        h.aggregator().onQuorumStateChange(ClusterStateNotification.active());

        var events = h.events();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst()).isInstanceOf(ClusterEvent.QuorumEstablished.class);
    }

    /// NODE_JOINED is now LEADER-gated too (the join analog of the departure fix). The transport
    /// `PeerJoined` handshake is the source (the membership delta does not fire for a not-yet-counted
    /// JOINING replacement), but a replacement's join must not be lost just because the cluster-events
    /// partition owner did not observe its handshake — the leader (which dials every core member) emits.
    @Test
    void leader_emitsNodeJoined_evenWhenNotOwner() {
        var h = Harness.create(Harness.defaultRetention(), NOT_OWNER, () -> false, LEADER);
        h.aggregator().onPeerJoined(peerJoined("replacement", List.of(SELF, new NodeId("replacement"))));
        var events = h.events();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst()).isInstanceOf(ClusterEvent.NodeJoined.class);
        assertThat(events.getFirst().details()).containsEntry("nodeId", "replacement");
    }

    @Test
    void nonLeader_suppressesNodeJoinedEmit() {
        var h = Harness.create(Harness.defaultRetention(), OWNER, () -> false, NOT_LEADER);
        h.aggregator().onPeerJoined(peerJoined("replacement", List.of(SELF, new NodeId("replacement"))));
        assertThat(h.events()).isEmpty();
    }

    /// #1573 B2: the automatic-rollback events are produced on the leader only. Under the owner gate they
    /// were lost whenever another node owned the cluster-events partition (v1608: 2 of 6 clusters).
    @Test
    void leader_emitsAutoRollbackAndSliceFailure_evenWhenNotOwner() {
        var h = Harness.create(Harness.defaultRetention(), NOT_OWNER, () -> false, LEADER);

        h.aggregator().onSliceFailure(allInstancesFailed());
        h.aggregator().onAutoRollback(autoRollbackExecuted());

        assertThat(h.events()).hasSize(2)
                              .anyMatch(ClusterEvent.SliceFailure.class::isInstance)
                              .anyMatch(ClusterEvent.AutoRollback.class::isInstance);
    }

    @Test
    void nonLeader_suppressesAutoRollbackAndSliceFailure_evenWhenOwner() {
        var h = Harness.create(Harness.defaultRetention(), OWNER, () -> false, NOT_LEADER);

        h.aggregator().onSliceFailure(allInstancesFailed());
        h.aggregator().onAutoRollback(autoRollbackExecuted());

        assertThat(h.events()).isEmpty();
    }

    private static SliceFailureEvent.AllInstancesFailed allInstancesFailed() {
        return SliceFailureEvent.AllInstancesFailed.allInstancesFailed("req-1",
                                                                       ROLLBACK_ARTIFACT,
                                                                       MethodName.methodName("ping").unwrap(),
                                                                       Option.none(),
                                                                       List.of(SELF),
                                                                       Map.of(SELF, 3L),
                                                                       30_000L);
    }

    private static RollbackEvent.AutoRollbackExecuted autoRollbackExecuted() {
        return new RollbackEvent.AutoRollbackExecuted("req-1",
                                                      ROLLBACK_ARTIFACT,
                                                      Version.version("0.9.0").unwrap(),
                                                      1,
                                                      Map.of(SELF, 3L),
                                                      30_000L);
    }

    /// Lifecycle (and leader/quorum/generation) events are cluster-canonical and LEADER-gated: a
    /// non-leader (even when partition owner) must not advertise them.
    @Test
    void nonLeader_suppressesCanonicalEvent() {
        var h = Harness.create(Harness.defaultRetention(), OWNER, () -> false, NOT_LEADER);
        h.aggregator().onNodeLifecycleChanged(OperationalEvent.NodeLifecycleChanged.nodeLifecycleChanged("n1", "READY", "op"));
        assertThat(h.events()).isEmpty();
    }

    /// And the same lifecycle event DOES emit on the leader even when it is not the partition owner.
    @Test
    void leader_emitsCanonicalEvent_evenWhenNotOwner() {
        var h = Harness.create(Harness.defaultRetention(), NOT_OWNER, () -> false, LEADER);
        h.aggregator().onNodeLifecycleChanged(OperationalEvent.NodeLifecycleChanged.nodeLifecycleChanged("n1", "READY", "op"));
        assertThat(h.events()).hasSize(1);
        assertThat(h.events().getFirst()).isInstanceOf(ClusterEvent.NodeLifecycleChanged.class);
    }

    // --- budget exhaustion (per-node, NOT owner-gated; reconciliation #13/#15) ------------------

    private static Exhaustion createFloorExhaustion(String streamName) {
        return new Exhaustion(streamName, 4, Exhaustion.Phase.CREATE_FLOOR, 2_097_152L, 1_153_433L,
                              134_217_728L, ConsistencyMode.EVENTUAL);
    }

    private static Exhaustion growthExhaustion(String streamName) {
        return new Exhaustion(streamName, 4, Exhaustion.Phase.GROWTH, 262_144L, 0L,
                              134_217_728L, ConsistencyMode.EVENTUAL);
    }

    /// Budget exhaustion is a per-node fact: even a NON-OWNER node must report its own exhaustion via
    /// the un-gated `emitLocal` path (mirrors SelfDrainInitiated). The owner gate that suppresses
    /// consensus-derived events does NOT apply here.
    @Test
    void onStreamMemoryExceeded_emitsLocal_notOwnerGated() {
        var h = Harness.create(Harness.defaultRetention(), NOT_OWNER);
        h.aggregator().onStreamMemoryExceeded(createFloorExhaustion("orders"));

        var events = h.events();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst()).isInstanceOf(ClusterEvent.StreamMemoryExceeded.class);
        assertThat(events.getFirst().severity()).isEqualTo(ClusterEvent.Severity.WARNING);
        assertThat(events.getFirst().details()).containsEntry("streamName", "orders")
                                               .containsEntry("phase", "create-floor")
                                               .containsEntry("nodeId", SELF.id());
    }

    // --- self-drain (per-node, NOT owner-gated; #565) -------------------------------------------

    private static ClusterEvent selfDrainInitiated(Harness h) {
        return new ClusterEvent.SelfDrainInitiated(h.hlc().now(),
                                                   ClusterEvent.Severity.WARNING,
                                                   "Self-drain initiated on " + SELF.id() + " (reason=QUORUM_LOSS)",
                                                   Map.of("nodeId", SELF.id(), "reason", "QUORUM_LOSS"));
    }

    /// #565 — THE regression. A self-draining node is the ONLY authoritative source for "I am
    /// self-draining", and it is by definition NOT the owner of the cluster-events partition: it is
    /// fencing itself out of a cluster whose pre-partition placement typically put partition 0 on one of
    /// the nodes it just lost. So the one event that explains why a node left must travel the un-gated
    /// path. Four doc sites already named `SelfDrainInitiated` as the exemplar for exactly this
    /// (`ClusterEvent` x2, `emitLocal`, and the budget test above) — the wiring was the one place that
    /// did not follow it, and the suppression logged at DEBUG, invisible at default INFO.
    @Test
    void emitLocal_selfDrainInitiated_notOwner_isPublished() {
        var h = Harness.create(Harness.defaultRetention(), NOT_OWNER);
        h.aggregator().emitLocal(selfDrainInitiated(h));

        var events = h.events();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst()).isInstanceOf(ClusterEvent.SelfDrainInitiated.class);
        assertThat(events.getFirst().severity()).isEqualTo(ClusterEvent.Severity.WARNING);
        assertThat(events.getFirst().details()).containsEntry("nodeId", SELF.id())
                                               .containsEntry("reason", "QUORUM_LOSS");
    }

    /// The trap this fix closes, pinned so it cannot be reintroduced by "simplifying" the call site back
    /// to `emit`. The owner gate is a DIFFERENT gate from the leader gate the wiring comment rules out,
    /// and it silently drops the event for precisely the node that needs to report it.
    @Test
    void emit_selfDrainInitiated_notOwner_isSuppressed_whichIsWhyWiringUsesEmitLocal() {
        var h = Harness.create(Harness.defaultRetention(), NOT_OWNER);
        h.aggregator().emit(selfDrainInitiated(h));

        assertThat(h.events()).isEmpty();
    }

    /// An OWNER self-draining must not double-publish: `emitLocal` bypasses the owner gate rather than
    /// adding a second path, so the owner case emits exactly once.
    @Test
    void emitLocal_selfDrainInitiated_owner_isPublishedExactlyOnce() {
        var h = Harness.create(Harness.defaultRetention(), OWNER);
        h.aggregator().emitLocal(selfDrainInitiated(h));

        assertThat(h.events()).hasSize(1);
    }

    /// Rate-limit: within the 60s window per (streamName, phase) the first growth-phase exhaustion
    /// emits and subsequent ones are suppressed (a saturated growing stream must not flood the log).
    @Test
    void onStreamMemoryExceeded_rateLimitsPerStreamPhase_withinWindow() {
        var h = Harness.create(Harness.defaultRetention(), OWNER);
        h.aggregator().onStreamMemoryExceeded(growthExhaustion("hot"));
        h.aggregator().onStreamMemoryExceeded(growthExhaustion("hot"));
        h.aggregator().onStreamMemoryExceeded(growthExhaustion("hot"));

        var events = h.events();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().details()).containsEntry("phase", "growth");
    }

    /// The throttle key includes the phase, so a create-floor exhaustion is NOT suppressed by a prior
    /// growth-phase exhaustion of the same stream (distinct operator-relevant signals).
    @Test
    void onStreamMemoryExceeded_distinctPhases_notMutuallyThrottled() {
        var h = Harness.create(Harness.defaultRetention(), OWNER);
        h.aggregator().onStreamMemoryExceeded(growthExhaustion("mixed"));
        h.aggregator().onStreamMemoryExceeded(createFloorExhaustion("mixed"));

        assertThat(h.events()).hasSize(2);
    }

    // --- #1640 redelivery -----------------------------------------------------------------------

    /// The owner gate is checked when the event is produced, never on redelivery: after a failover the producing
    /// node is typically no longer the owner, and re-checking would drop the event on its only producer. Here the
    /// first publish fails (publisher not yet bound), the node then stops being the owner, and the retry still
    /// lands the event.
    @Test
    void emit_firstPublishFails_ownershipMovesAway_retryStillLands() throws InterruptedException {
        var owner = new java.util.concurrent.atomic.AtomicBoolean(true);
        var h = Harness.create(Harness.defaultRetention(), owner::get);
        var publisher = h.publisher().getAndSet(null);

        h.aggregator().emit(selfDrainInitiated(h));
        assertThat(h.aggregator().redeliveryWaiting()).as("control: the failed publish is held").isEqualTo(1);
        assertThat(h.aggregator().redeliveryFailuresByCause()).as("the unbound publisher is counted by its typed name")
                                                              .containsEntry("PUBLISHER_NOT_BOUND", 1L);

        owner.set(false);
        h.publisher().set(publisher);
        Thread.sleep(ClusterEventRedelivery.INITIAL_BACKOFF_MS + 100);
        h.aggregator().redeliverDue();

        assertThat(h.events()).hasSize(1);
        assertThat(h.aggregator().redeliveryWaiting()).isZero();
    }

    /// An event can be in the log twice (an unknown outcome that landed, then its redelivery). Both copies carry
    /// the same `details.eventId`, and a read returns one.
    @Test
    void events_sameEventLandedTwice_isReadOnce() {
        var h = Harness.create();
        var event = new ClusterEvent.AlertInjected(h.hlc().now(),
                                                   ClusterEvent.Severity.INFO,
                                                   "landed twice",
                                                   Map.of(ClusterEventIdentity.EVENT_ID, "incarnation:1"));

        h.aggregator().emitLocal(event);
        h.aggregator().emitLocal(event);

        assertThat(h.events()).hasSize(1);
        assertThat(h.aggregator().lastReadDuplicates()).as("control: both copies are in the log").isEqualTo(1);
    }

    /// #1653: the id is stamped ONCE, when the aggregator accepts the event, before the first attempt. Here the first
    /// attempt LANDS and still fails (the unknown-outcome case), and the retry lands a second copy: both copies carry
    /// the same id, and a read returns one.
    @Test
    void emit_firstAttemptLandsButFails_retryCopyHasTheSameId_andIsReadOnce() {
        var h = Harness.create();
        var real = h.publisher().get();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var landsThenFails = FrameworkStreamPublishers.<ClusterEvent>testPublisher(SystemStreams.CLUSTER_EVENTS,
                                                                                  event -> landThenFailFirst(real, event, calls))
                                                      .unwrap();

        h.publisher().set(landsThenFails);
        h.aggregator().emitLocal(new ClusterEvent.AlertInjected(h.hlc().now(), ClusterEvent.Severity.INFO, "once", Map.of()));
        assertThat(h.aggregator().redeliveryWaiting()).as("control: the first attempt failed and is held").isEqualTo(1);

        h.aggregator().onStreamPartitionOwnershipPut(ownershipPut(SystemStreams.CLUSTER_EVENTS.asString(), 0));

        assertThat(calls.get()).as("control: two attempts").isEqualTo(2);
        assertThat(h.events()).hasSize(1);
        assertThat(h.aggregator().lastReadDuplicates()).as("control: both copies landed").isEqualTo(1);
    }

    /// #1653: the feed's read starts at the offset it is given, not at the start of the retained log, and reports
    /// where the next read starts. A whole-log read would return all five events for every offset.
    @Test
    void eventsFrom_readsFromTheGivenOffset_andReportsTheNextOne() {
        var h = Harness.create();

        for (int i = 0; i < 5; i++) {
            h.aggregator().emitLocal(new ClusterEvent.AlertInjected(h.hlc().now(), ClusterEvent.Severity.INFO, "e" + i, Map.of()));
        }

        var all = h.aggregator().eventsFrom(0).await().unwrap();
        var tail = h.aggregator().eventsFrom(3).await().unwrap();
        var none = h.aggregator().eventsFrom(5).await().unwrap();

        assertThat(all.events()).as("control: every event from offset 0").extracting(landed -> landed.event().summary())
                                .containsExactly("e0", "e1", "e2", "e3", "e4");
        assertThat(all.nextOffset()).isEqualTo(5);
        assertThat(tail.events()).extracting(landed -> landed.event().summary()).containsExactly("e3", "e4");
        assertThat(tail.nextOffset()).isEqualTo(5);
        assertThat(none.events()).isEmpty();
        assertThat(none.nextOffset()).isEqualTo(5);
    }

    /// #1653 (v1640 V6): once retention has trimmed the head of the log, a read clamped up to the tail still reports
    /// the offset after the last event it read, not `from` plus the number of events.
    @Test
    void eventsFrom_belowTheRetainedTail_nextOffsetFollowsTheLastEventRead() {
        var h = Harness.create(RetentionPolicy.retentionPolicy(5, 64L * 1024 * 1024, Long.MAX_VALUE, RetentionMode.ANY), OWNER);

        for (int i = 0; i < 12; i++) {
            h.aggregator().emitLocal(new ClusterEvent.AlertInjected(h.hlc().now(), ClusterEvent.Severity.INFO, "e" + i, Map.of()));
        }

        var page = h.aggregator().eventsFrom(0).await().unwrap();

        assertThat(page.tailOffset()).as("control: retention trimmed the head").isPositive();
        assertThat(page.events().getLast().offset()).as("control").isEqualTo(11);
        assertThat(page.nextOffset()).isEqualTo(12);
    }

    /// #1653 (v1640 V2): the production feed wiring reads the node's aggregator from its cursor, not from offset 0.
    @Test
    void feed_wiredToTheAggregator_readsFromTheCursor() {
        var h = Harness.create();
        var broadcasts = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var feed = EventWebSocketPublisher.eventWebSocketPublisher(new EventWebSocketPublisherTest.CapturingHandler(broadcasts),
                                                                   h::aggregator,
                                                                   events -> String.valueOf(events.size()));

        for (int i = 0; i < 30; i++) {
            h.aggregator().emitLocal(new ClusterEvent.AlertInjected(h.hlc().now(), ClusterEvent.Severity.INFO, "e" + i, Map.of()));
        }
        feed.publish();
        h.aggregator().emitLocal(new ClusterEvent.AlertInjected(h.hlc().now(), ClusterEvent.Severity.INFO, "e30", Map.of()));
        feed.publish();

        assertThat(broadcasts).as("control: 30, then the one new event").containsExactly("30", "1");
        assertThat(h.aggregator().lastEventsFrom()).isEqualTo(30 - EventWebSocketPublisher.OVERLAP);
    }

    /// #1653: an ownership change of the cluster-events partition is counted on the page, which is what makes the feed
    /// re-read a new owner's log.
    @Test
    void onStreamPartitionOwnershipPut_clusterEventsPartition_isCountedOnThePage() {
        var h = Harness.create();
        var before = h.aggregator().eventsFrom(0).await().unwrap().ownershipChanges();

        h.aggregator().onStreamPartitionOwnershipPut(ownershipPut(SystemStreams.CLUSTER_EVENTS.asString(), 0));

        assertThat(h.aggregator().eventsFrom(0).await().unwrap().ownershipChanges()).isEqualTo(before + 1);
    }

    /// #1653 (v1640 N3): stamping copies `details`, which throws on a null value. That throw stays inside the
    /// publish's isolation: the caller is not interrupted and the event is still published, without an id.
    @Test
    void emitLocal_detailsWithANullValue_doesNotThrow_andIsPublishedWithoutAnId() {
        var h = Harness.create();
        var details = new java.util.HashMap<String, String>();

        details.put("reason", null);
        h.aggregator().emitLocal(new ClusterEvent.AlertInjected(h.hlc().now(), ClusterEvent.Severity.INFO, "null detail", details));

        assertThat(h.aggregator().redeliveryCounters()).as("handed to redelivery").containsEntry("accepted", 1L);
    }

    /// #1653, the handover limit (CTO ruling on item 8): the owner gate is each node's own view of ownership, so
    /// across a handover the old and the new owner can both raise one fact. The guarantee pinned here is
    /// AT-LEAST-ONCE: the old owner's raise, whose publish failed during the handover, is still delivered, and so
    /// is the new owner's. How many copies a read shows is deliberately not asserted.
    @Test
    void emit_ownerHandover_bothOwnersRaiseOneFact_eachRaiseIsDeliveredAtLeastOnce() throws InterruptedException {
        var manager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);
        var config = StreamConfig.streamConfig(SystemStreams.CLUSTER_EVENTS.asString(),
                                               1,
                                               Harness.defaultRetention(),
                                               "earliest",
                                               64L * 1024,
                                               ConsistencyMode.EVENTUAL,
                                               1);
        var publisher = SystemStreamFactories.<ClusterEvent>systemStreamPublisher(SystemStreams.CLUSTER_EVENTS, manager, CODEC, config)
                                             .unwrap();
        var consumer = SystemStreamFactories.<ClusterEvent>systemStreamConsumer(SystemStreams.CLUSTER_EVENTS, manager, CODEC, CODEC, config)
                                            .unwrap();
        var oldOwnerPublisher = new AtomicReference<FrameworkStreamPublisher<ClusterEvent>>(null);
        var oldOwner = handoverSide("old-owner", oldOwnerPublisher, consumer);
        var newOwner = handoverSide("new-owner", new AtomicReference<>(publisher), consumer);

        oldOwner.aggregator().emit(deploymentStarted(oldOwner.hlc()));
        newOwner.aggregator().emit(deploymentStarted(newOwner.hlc()));
        assertThat(oldOwner.aggregator().redeliveryWaiting()).as("control: the old owner's publish failed and is held").isEqualTo(1);

        oldOwnerPublisher.set(publisher);
        Thread.sleep(ClusterEventRedelivery.INITIAL_BACKOFF_MS + 100);
        oldOwner.aggregator().redeliverDue();

        var raisedBy = newOwner.aggregator()
                               .events()
                               .await()
                               .unwrap()
                               .stream()
                               .filter(event -> event instanceof ClusterEvent.DeploymentStarted)
                               .map(event -> event.at().nodeId().id())
                               .toList();

        assertThat(raisedBy).as("the old owner's raise is delivered").contains("old-owner");
        assertThat(raisedBy).as("the new owner's raise is delivered").contains("new-owner");
    }

    private record HandoverSide(ClusterEventAggregator aggregator, HlcClock hlc) {}

    private static HandoverSide handoverSide(String node,
                                             AtomicReference<FrameworkStreamPublisher<ClusterEvent>> publisher,
                                             FrameworkStreamConsumer<ClusterEvent> consumer) {
        var hlc = HlcClock.hlcClock(new NodeId(node));
        var aggregator = ClusterEventAggregator.clusterEventAggregator(publisher::get,
                                                                       () -> consumer,
                                                                       OWNER,
                                                                       new NodeId(node),
                                                                       hlc,
                                                                       () -> 3,
                                                                       () -> false,
                                                                       LEADER);
        return new HandoverSide(aggregator, hlc);
    }

    private static ClusterEvent deploymentStarted(HlcClock hlc) {
        return new ClusterEvent.DeploymentStarted(hlc.now(),
                                                  ClusterEvent.Severity.INFO,
                                                  "Deploying x to n1",
                                                  Map.of("artifact", "x", "nodeId", "n1"));
    }

    private static void landThenFailFirst(FrameworkStreamPublisher<ClusterEvent> real,
                                          ClusterEvent event,
                                          java.util.concurrent.atomic.AtomicInteger calls) {
        real.publish(event)
            .await();
        if (calls.incrementAndGet() == 1) {
            throw new IllegalStateException("landed, then the outcome was lost");
        }
    }

    /// #1653 round 2: `at` is not an identity. Two DISTINCT events that share `at` (one node, one millisecond,
    /// for example after a restart with a clock step) are both kept, because each is stamped with its own id.
    @Test
    void events_twoDistinctEventsWithTheSameAt_areBothKept() {
        var h = Harness.create();
        var at = h.hlc().now();

        h.aggregator().emitLocal(new ClusterEvent.AlertInjected(at, ClusterEvent.Severity.INFO, "first", Map.of()));
        h.aggregator().emitLocal(new ClusterEvent.AlertInjected(at, ClusterEvent.Severity.INFO, "second", Map.of()));

        assertThat(h.events()).extracting(ClusterEvent::summary).containsExactlyInAnyOrder("first", "second");
        assertThat(h.events()).allMatch(event -> event.details().containsKey(ClusterEventIdentity.EVENT_ID));
    }

    /// #1653 round 2: a redelivered event lands after events produced later; a read is still a timeline.
    @Test
    void events_landedOutOfAtOrder_areReadInAtOrder() {
        var h = Harness.create();
        var earlier = h.hlc().now();
        var later = h.hlc().now();

        h.aggregator().emitLocal(new ClusterEvent.AlertInjected(later, ClusterEvent.Severity.INFO, "later", Map.of()));
        h.aggregator().emitLocal(new ClusterEvent.AlertInjected(earlier, ClusterEvent.Severity.INFO, "earlier", Map.of()));

        assertThat(h.events()).extracting(ClusterEvent::summary).containsExactly("earlier", "later");
    }

    /// #1653 round 2: an ownership put for cluster-events partition 0 re-sends every held event at once, without
    /// waiting for its backoff; a put for any other stream or partition does not.
    @Test
    void onStreamPartitionOwnershipPut_clusterEventsPartition0_drainsAtOnce_otherPutsDoNot() {
        var h = Harness.create();
        var publisher = h.publisher().getAndSet(null);

        h.aggregator().emitLocal(selfDrainInitiated(h));
        h.publisher().set(publisher);

        h.aggregator().onStreamPartitionOwnershipPut(ownershipPut("orders", 0));
        h.aggregator().onStreamPartitionOwnershipPut(ownershipPut(SystemStreams.CLUSTER_EVENTS.asString(), 1));
        assertThat(h.events()).as("control: other puts do not drain").isEmpty();

        h.aggregator().onStreamPartitionOwnershipPut(ownershipPut(SystemStreams.CLUSTER_EVENTS.asString(), 0));

        assertThat(h.events()).as("drained at once, well inside the 1 s backoff").hasSize(1);
    }

    /// #1653 round 2: `redeliverDue` is what the AetherNode 1 s tick calls; once an event's backoff has passed, it
    /// re-sends it.
    @Test
    void redeliverDue_afterTheBackoff_resends() throws InterruptedException {
        var h = Harness.create();
        var publisher = h.publisher().getAndSet(null);

        h.aggregator().emitLocal(selfDrainInitiated(h));
        h.publisher().set(publisher);
        h.aggregator().redeliverDue();
        assertThat(h.events()).as("control: not yet due").isEmpty();

        Thread.sleep(ClusterEventRedelivery.INITIAL_BACKOFF_MS + 100);
        h.aggregator().redeliverDue();

        assertThat(h.events()).hasSize(1);
    }

    private static ValuePut<StreamPartitionOwnershipKey, StreamPartitionOwnershipValue> ownershipPut(String stream, int partition) {
        var epoch = Epoch.epoch(0L, 7, 3);

        return new ValuePut<>(new KVCommand.Put<>(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(stream, partition),
                                                  StreamPartitionOwnershipValue.streamPartitionOwnershipValue(SELF,
                                                                                                              epoch,
                                                                                                              epoch.localCounter(),
                                                                                                              HlcTimestamp.ZERO)),
                              Option.none());
    }

    // --- operator warnings (#1574) ---------------------------------------------------------------

    private static OperatorWarning fsyncFailed(String subject) {
        return OperatorWarning.operatorWarning(OperatorWarningCode.REPLICA_FSYNC_FAILED,
                                               subject,
                                               "durability sync failed for " + subject);
    }

    /// A warning is a per-node fact, so a NON-OWNER still publishes it. The event carries the code
    /// catalogue's severity, the logged message as its summary, and the filterable details.
    @Test
    void onOperatorWarning_notOwner_emitsEventCarryingCodeAndSubject() {
        var h = Harness.create(Harness.defaultRetention(), NOT_OWNER);

        h.aggregator().onOperatorWarning(OperatorWarning.operatorWarning(OperatorWarningCode.CORE_ABSENCE_FENCE,
                                                                         "core",
                                                                         "CORE ABSENCE fence firing"));

        var events = h.events();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst()).isInstanceOf(ClusterEvent.OperatorWarning.class);
        assertThat(events.getFirst().type()).isEqualTo("OPERATOR_WARNING");
        assertThat(events.getFirst().severity()).isEqualTo(ClusterEvent.Severity.CRITICAL);
        assertThat(events.getFirst().summary()).isEqualTo("CORE ABSENCE fence firing");
        assertThat(events.getFirst().details()).containsAllEntriesOf(Map.of("code", "core-absence-fence",
                                                                            "subsystem", "worker-isolation",
                                                                            "subject", "core",
                                                                            "nodeId", SELF.id(),
                                                                            "suppressedSince", "0"))
                                                .as("#1653: stamped like every other event")
                                                .containsKey(ClusterEventIdentity.EVENT_ID)
                                                .hasSize(6);
    }

    /// The flood test. A thousand raises of one `(code, subject)` inside a window publish ONE event, a
    /// different subject is not starved by the flood, and the first event after the window closes
    /// reports how many were held back.
    @Test
    void onOperatorWarning_flood_emitsOncePerWindow_andReportsTheSuppressedCount() {
        var physicalMillis = new AtomicLong(1_000_000L);
        var h = Harness.create(Harness.defaultRetention(),
                               OWNER,
                               () -> false,
                               LEADER,
                               HlcClock.hlcClock(SELF, physicalMillis::get, Long.MAX_VALUE));

        for (int i = 0; i < 1_000; i++) {
            h.aggregator().onOperatorWarning(fsyncFailed("orders[3]"));
        }
        h.aggregator().onOperatorWarning(fsyncFailed("orders[4]"));

        assertThat(h.events()).hasSize(2);

        physicalMillis.addAndGet(60_000L);
        h.aggregator().onOperatorWarning(fsyncFailed("orders[3]"));

        var events = h.events();
        assertThat(events).hasSize(3);
        assertThat(events.stream().map(event -> event.details().get("subject")).toList())
            .containsExactly("orders[3]", "orders[4]", "orders[3]");
        assertThat(events.getLast().details()).containsEntry("suppressedSince", "999");
        assertThat(events.getFirst().details()).containsEntry("suppressedSince", "0");
    }

    private static OperatorWarning diverged(String subject) {
        return OperatorWarning.operatorWarning(OperatorWarningCode.STREAM_CONSUMER_STATE_DIVERGED, subject, "diverged " + subject);
    }

    private static OperatorWarning repaired(String subject) {
        return OperatorWarning.operatorWarning(OperatorWarningCode.STREAM_CONSUMER_STATE_REPAIRED, subject, "repaired " + subject);
    }

    private List<String> codes(Harness h) {
        return h.events().stream().map(event -> event.details().get("code")).toList();
    }

    /// #752: a WarningLevel.INFO code maps to ClusterEvent.Severity.INFO, not WARNING.
    @Test
    void onOperatorWarning_infoLevelCode_publishesAtInfoSeverity() {
        var h = Harness.create();

        h.aggregator().onOperatorWarning(diverged("g:orders[0]"));
        h.aggregator().onOperatorWarning(repaired("g:orders[0]"));

        assertThat(h.events().stream().map(ClusterEvent::severity).toList())
            .containsExactly(ClusterEvent.Severity.WARNING, ClusterEvent.Severity.INFO);
    }

    /// #752: a recovery with no published warning for its subject is refused, and a warning for a different subject
    /// does not stand in for it.
    @Test
    void onOperatorWarning_recoveryWithoutItsWarning_isNotPublished() {
        var h = Harness.create();

        h.aggregator().onOperatorWarning(repaired("g:orders[0]"));
        h.aggregator().onOperatorWarning(diverged("g:orders[1]"));
        h.aggregator().onOperatorWarning(repaired("g:orders[0]"));

        assertThat(codes(h)).containsExactly("stream-consumer-state-diverged");
    }

    /// #752: a recovery ends its warning's throttle window. D, R, then the condition recurs 10 s later: the recurrence is
    /// shown, and so is its recovery. The earlier version of this test expected [D, R] here: it held the recurrence back
    /// behind the first window, so a stuck recurrence left the feed's last word at "repaired" while the partition was
    /// consumed by nobody (a false all-clear), and it encoded that as the specification.
    @Test
    void onOperatorWarning_recurrenceAfterAShownRecovery_isShown_andSoIsItsRecovery() {
        var physicalMillis = new AtomicLong(1_000_000L);
        var h = Harness.create(Harness.defaultRetention(), OWNER, () -> false, LEADER,
                               HlcClock.hlcClock(SELF, physicalMillis::get, Long.MAX_VALUE));

        h.aggregator().onOperatorWarning(diverged("g:orders[0]"));
        physicalMillis.addAndGet(30_000L);
        h.aggregator().onOperatorWarning(repaired("g:orders[0]"));
        physicalMillis.addAndGet(10_000L);
        h.aggregator().onOperatorWarning(diverged("g:orders[0]"));
        physicalMillis.addAndGet(60_000L);
        h.aggregator().onOperatorWarning(repaired("g:orders[0]"));

        assertThat(codes(h)).containsExactly("stream-consumer-state-diverged",
                                             "stream-consumer-state-repaired",
                                             "stream-consumer-state-diverged",
                                             "stream-consumer-state-repaired");
    }

    /// #752: a repeat of an open warning is still throttled, and its one recovery is still published.
    @Test
    void onOperatorWarning_repeatOfAnOpenWarning_isThrottled_andClosedByOneRecovery() {
        var physicalMillis = new AtomicLong(1_000_000L);
        var h = Harness.create(Harness.defaultRetention(), OWNER, () -> false, LEADER,
                               HlcClock.hlcClock(SELF, physicalMillis::get, Long.MAX_VALUE));

        h.aggregator().onOperatorWarning(diverged("g:orders[0]"));
        physicalMillis.addAndGet(10_000L);
        h.aggregator().onOperatorWarning(diverged("g:orders[0]"));
        h.aggregator().onOperatorWarning(repaired("g:orders[0]"));

        assertThat(codes(h)).containsExactly("stream-consumer-state-diverged", "stream-consumer-state-repaired");
    }

    /// #752: the recovery is released only after its warning is in the log. Here the warning's first publish fails and
    /// the recovery arrives while it is held: the recovery waits, and follows the warning once the retry lands.
    @Test
    void onOperatorWarning_recoveryWhileItsWarningIsHeldForRedelivery_followsTheWarning() throws InterruptedException {
        var h = Harness.create();
        var publisher = h.publisher().getAndSet(null);

        h.aggregator().onOperatorWarning(diverged("g:orders[0]"));
        h.aggregator().onOperatorWarning(repaired("g:orders[0]"));
        h.publisher().set(publisher);
        assertThat(codes(h)).as("control: nothing is in the log yet").isEmpty();
        Thread.sleep(ClusterEventRedelivery.INITIAL_BACKOFF_MS + 100);
        h.aggregator().redeliverDue();

        assertThat(codes(h)).containsExactly("stream-consumer-state-diverged", "stream-consumer-state-repaired");
    }

    /// #752: a warning that redelivery gives up on was never shown, so a recovery held for it is dropped, and the log
    /// never carries a recovery without its warning. Without the hold, the recovery was published at once and the
    /// warning then lost to overflow.
    @Test
    void onOperatorWarning_recoveryWhoseWarningIsGivenUp_isNotPublished() throws InterruptedException {
        var h = Harness.create();
        var publisher = h.publisher().getAndSet(null);

        h.aggregator().onOperatorWarning(diverged("g:orders[0]"));
        h.aggregator().onOperatorWarning(repaired("g:orders[0]"));
        for (int i = 0; i < ClusterEventRedelivery.CAPACITY; i++) {
            h.aggregator().onOperatorWarning(fsyncFailed("orders[" + i + "]"));
        }
        assertThat(h.aggregator().redeliveryDropped()).as("control: the diverged event was dropped by overflow")
                                                      .containsEntry("overflow", 1L);
        h.publisher().set(publisher);
        Thread.sleep(ClusterEventRedelivery.INITIAL_BACKOFF_MS + 100);
        h.aggregator().redeliverDue();

        assertThat(codes(h)).as("the held fsync warnings landed (control), and neither diverged nor repaired did")
                            .isNotEmpty()
                            .doesNotContain("stream-consumer-state-diverged", "stream-consumer-state-repaired");
    }

    /// #752: the recovery of a published warning is published even when it comes late. Independent windows swallowed
    /// the second recovery here (its window, opened by the first, was still shut) though its warning was shown.
    @Test
    void onOperatorWarning_recoveryOfAPublishedWarning_isNotThrottledAway() {
        var physicalMillis = new AtomicLong(1_000_000L);
        var h = Harness.create(Harness.defaultRetention(), OWNER, () -> false, LEADER,
                               HlcClock.hlcClock(SELF, physicalMillis::get, Long.MAX_VALUE));

        h.aggregator().onOperatorWarning(diverged("g:orders[0]"));
        physicalMillis.addAndGet(30_000L);
        h.aggregator().onOperatorWarning(repaired("g:orders[0]"));
        physicalMillis.addAndGet(35_000L);
        h.aggregator().onOperatorWarning(diverged("g:orders[0]"));
        physicalMillis.addAndGet(5_000L);
        h.aggregator().onOperatorWarning(repaired("g:orders[0]"));

        assertThat(codes(h)).containsExactly("stream-consumer-state-diverged",
                                             "stream-consumer-state-repaired",
                                             "stream-consumer-state-diverged",
                                             "stream-consumer-state-repaired");
    }

    /// #752: one published warning allows one recovery.
    @Test
    void onOperatorWarning_secondRecoveryForOneWarning_isNotPublished() {
        var h = Harness.create();

        h.aggregator().onOperatorWarning(diverged("g:orders[0]"));
        h.aggregator().onOperatorWarning(repaired("g:orders[0]"));
        h.aggregator().onOperatorWarning(repaired("g:orders[0]"));

        assertThat(codes(h)).containsExactly("stream-consumer-state-diverged", "stream-consumer-state-repaired");
    }

    /// #752: a warning whose publish was dropped (replay) was never shown, so a recovery for it is refused.
    @Test
    void onOperatorWarning_recoveryOfAnUnpublishedWarning_isNotPublished() {
        var replaying = new java.util.concurrent.atomic.AtomicBoolean(true);
        var h = Harness.create(Harness.defaultRetention(), OWNER, replaying::get, LEADER, HlcClock.hlcClock(SELF, () -> 1_000_000L, Long.MAX_VALUE));

        h.aggregator().onOperatorWarning(diverged("g:orders[0]"));
        replaying.set(false);
        h.aggregator().onOperatorWarning(repaired("g:orders[0]"));

        assertThat(h.events()).isEmpty();
    }

    /// One millisecond short of the window is still inside it.
    @Test
    void onOperatorWarning_justInsideTheWindow_isStillSuppressed() {
        var physicalMillis = new AtomicLong(1_000_000L);
        var h = Harness.create(Harness.defaultRetention(),
                               OWNER,
                               () -> false,
                               LEADER,
                               HlcClock.hlcClock(SELF, physicalMillis::get, Long.MAX_VALUE));

        h.aggregator().onOperatorWarning(fsyncFailed("orders[3]"));
        physicalMillis.addAndGet(59_999L);
        h.aggregator().onOperatorWarning(fsyncFailed("orders[3]"));

        assertThat(h.events()).hasSize(1);
    }

    /// #1617 R3: throttle keys are evicted once idle for twice the window, so an open key space (peers,
    /// `stream[partition]` subjects) cannot grow without bound. 100k keys, then an idle interval past the
    /// eviction age: the map is empty again.
    @Test
    void evictIdleThrottleWindows_afterTwiceTheWindowIdle_emptiesTheOperatorWarningThrottle() {
        var physicalMillis = new AtomicLong(1_000_000L);
        var h = Harness.create(Harness.defaultRetention(),
                               OWNER,
                               () -> false,
                               LEADER,
                               HlcClock.hlcClock(SELF, physicalMillis::get, Long.MAX_VALUE));

        for (int i = 0; i < 100_000; i++) {
            h.aggregator().onOperatorWarning(fsyncFailed("orders[" + i + "]"));
        }

        assertThat(h.aggregator().operatorWarningThrottleKeys()).isEqualTo(100_000);

        // The HLC's logical counter carries into its physical component under 100k reads in one millisecond,
        // so the keys' last-seen times spread over a few ms. The margins below are far wider than that.
        physicalMillis.addAndGet(60_000L);
        h.aggregator().evictIdleThrottleWindows();

        assertThat(h.aggregator().operatorWarningThrottleKeys()).as("not yet idle for twice the window")
                                                                .isEqualTo(100_000);

        physicalMillis.addAndGet(70_000L);
        h.aggregator().evictIdleThrottleWindows();

        assertThat(h.aggregator().operatorWarningThrottleKeys()).as("idle for twice the window").isZero();
    }

    /// #1617 R3: a key that is still being raised is not evicted, however old its window is.
    @Test
    void evictIdleThrottleWindows_keyRaisedRecently_isKept() {
        var physicalMillis = new AtomicLong(1_000_000L);
        var h = Harness.create(Harness.defaultRetention(),
                               OWNER,
                               () -> false,
                               LEADER,
                               HlcClock.hlcClock(SELF, physicalMillis::get, Long.MAX_VALUE));

        h.aggregator().onOperatorWarning(fsyncFailed("orders[3]"));
        physicalMillis.addAndGet(119_000L);
        h.aggregator().onOperatorWarning(fsyncFailed("orders[3]"));
        physicalMillis.addAndGet(1_000L);
        h.aggregator().evictIdleThrottleWindows();

        assertThat(h.aggregator().operatorWarningThrottleKeys()).isEqualTo(1);
    }

    /// #1617 R4: a window is consumed only by an event that is published. The first occurrence is not
    /// published (replay in progress); a second occurrence inside the same window is then admitted, and
    /// reports the one that was lost.
    @Test
    void onOperatorWarning_firstPublishFails_retryInTheSameWindowIsAdmitted() {
        var replaying = new java.util.concurrent.atomic.AtomicBoolean(true);
        var physicalMillis = new AtomicLong(1_000_000L);
        var h = Harness.create(Harness.defaultRetention(),
                               OWNER,
                               replaying::get,
                               LEADER,
                               HlcClock.hlcClock(SELF, physicalMillis::get, Long.MAX_VALUE));

        h.aggregator().onOperatorWarning(fsyncFailed("orders[3]"));
        assertThat(h.events()).as("control: the first occurrence was not published").isEmpty();

        replaying.set(false);
        physicalMillis.addAndGet(ClusterEventAggregator.OPERATOR_WARNING_RETRY_MS);
        h.aggregator().onOperatorWarning(fsyncFailed("orders[3]"));

        var events = h.events();
        assertThat(events).as("the retry inside the same window is admitted").hasSize(1);
        assertThat(events.getFirst().details()).containsEntry("suppressedSince", "1");
    }

    /// #1617 R4 (v1562): while publishing keeps failing, a key is attempted at most once per retry window, not once
    /// per occurrence. 1,000 raises spread over one 60 s window, every publish failing, make at most
    /// ceil(60 s / 5 s) = 12 attempts. Releasing the window outright made 1,000.
    /// #1617 with #1653: a publish that fails transiently is HELD and re-sent by redelivery, so it is not a loss and
    /// does not shorten the window. A second occurrence inside the window stays suppressed while the first is being
    /// delivered, and the first lands once the publisher is back: one event for the window, not two.
    @Test
    void onOperatorWarning_transientPublishFailure_isRedelivered_andDoesNotReopenTheWindow() throws InterruptedException {
        var physicalMillis = new AtomicLong(1_000_000L);
        var h = Harness.create(Harness.defaultRetention(),
                               OWNER,
                               () -> false,
                               LEADER,
                               HlcClock.hlcClock(SELF, physicalMillis::get, Long.MAX_VALUE));
        var publisher = h.publisher().getAndSet(null);

        h.aggregator().onOperatorWarning(fsyncFailed("orders[3]"));
        assertThat(h.aggregator().redeliveryWaiting()).as("control: the failed publish is held, not dropped").isEqualTo(1);

        physicalMillis.addAndGet(ClusterEventAggregator.OPERATOR_WARNING_RETRY_MS);
        h.aggregator().onOperatorWarning(fsyncFailed("orders[3]"));
        assertThat(h.aggregator().redeliveryCounters()).as("the second occurrence was suppressed, not admitted")
                                                       .containsEntry("accepted", 1L);
        h.publisher().set(publisher);
        Thread.sleep(ClusterEventRedelivery.INITIAL_BACKOFF_MS + 100);
        h.aggregator().redeliverDue();

        assertThat(h.events()).as("the held event lands; the second occurrence stayed suppressed").hasSize(1);
    }

    /// #1617 with #1653: an event redelivery finally DROPS is a loss, so its key reopens the short retry window. Here
    /// the drop is an overflow: with the publisher unbound, one more distinct key than the redelivery capacity pushes
    /// the first key's event out.
    @Test
    void onOperatorWarning_eventRedeliveryGivesUpOn_reopensTheShortWindow() {
        var physicalMillis = new AtomicLong(1_000_000L);
        var h = Harness.create(Harness.defaultRetention(),
                               OWNER,
                               () -> false,
                               LEADER,
                               HlcClock.hlcClock(SELF, physicalMillis::get, Long.MAX_VALUE));

        h.publisher().set(null);
        for (int i = 0; i <= ClusterEventRedelivery.CAPACITY; i++) {
            h.aggregator().onOperatorWarning(fsyncFailed("orders[" + i + "]"));
        }
        assertThat(h.aggregator().redeliveryDropped()).as("control: the first key's event was dropped by overflow")
                                                      .containsEntry("overflow", 1L);

        physicalMillis.addAndGet(ClusterEventAggregator.OPERATOR_WARNING_RETRY_MS);
        h.aggregator().onOperatorWarning(fsyncFailed("orders[0]"));
        assertThat(h.aggregator().redeliveryCounters()).as("the lost key is admitted again after the short window")
                                                       .containsEntry("accepted", (long) ClusterEventRedelivery.CAPACITY + 2);

        // orders[5] is still held by redelivery (only orders[0] and then orders[1] were pushed out), so its full window
        // stands and the occurrence is suppressed.
        h.aggregator().onOperatorWarning(fsyncFailed("orders[5]"));
        assertThat(h.aggregator().redeliveryCounters()).as("a key whose event is still held is not admitted")
                                                       .containsEntry("accepted", (long) ClusterEventRedelivery.CAPACITY + 2);
    }

    @Test
    void onOperatorWarning_publishAlwaysFails_attemptsAreBoundedByTheRetryWindow() {
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        var physicalMillis = new AtomicLong(1_000_000L);
        var h = Harness.create(Harness.defaultRetention(),
                               OWNER,
                               () -> attempts.incrementAndGet() > 0,
                               LEADER,
                               HlcClock.hlcClock(SELF, physicalMillis::get, Long.MAX_VALUE));

        for (int i = 0; i < 1_000; i++) {
            h.aggregator().onOperatorWarning(fsyncFailed("orders[3]"));
            physicalMillis.addAndGet(60);
        }

        var bound = (int) Math.ceil(60_000.0 / ClusterEventAggregator.OPERATOR_WARNING_RETRY_MS);

        // v1617-r3 nit: the exact bound, not a range, so a retry window other than OPERATOR_WARNING_RETRY_MS fails here too.
        assertThat(attempts.get()).as("publish attempts for 1,000 raises in one window, all failing")
                                  .isEqualTo(bound);
        assertThat(h.events()).as("control: nothing was published").isEmpty();
    }

    /// After failed attempts, the first success restores the full window.
    @Test
    void onOperatorWarning_afterAFailedPublish_successRestoresTheFullWindow() {
        var replaying = new java.util.concurrent.atomic.AtomicBoolean(true);
        var physicalMillis = new AtomicLong(1_000_000L);
        var h = Harness.create(Harness.defaultRetention(),
                               OWNER,
                               replaying::get,
                               LEADER,
                               HlcClock.hlcClock(SELF, physicalMillis::get, Long.MAX_VALUE));

        h.aggregator().onOperatorWarning(fsyncFailed("orders[3]"));
        replaying.set(false);
        physicalMillis.addAndGet(ClusterEventAggregator.OPERATOR_WARNING_RETRY_MS);
        h.aggregator().onOperatorWarning(fsyncFailed("orders[3]"));
        assertThat(h.events()).as("control: the retry after the short window landed").hasSize(1);

        physicalMillis.addAndGet(ClusterEventAggregator.OPERATOR_WARNING_RETRY_MS);
        h.aggregator().onOperatorWarning(fsyncFailed("orders[3]"));

        assertThat(h.events()).as("a success restores the 60 s window: 5 s later is still suppressed").hasSize(1);
    }

    /// #1617 R5: the stream-memory throttle's own window edge. One millisecond short of the window is still
    /// suppressed, and the window's end admits.
    @Test
    void onStreamMemoryExceeded_windowEdge_suppressesJustInside_admitsAtTheEdge() {
        var physicalMillis = new AtomicLong(1_000_000L);
        var h = Harness.create(Harness.defaultRetention(),
                               OWNER,
                               () -> false,
                               LEADER,
                               HlcClock.hlcClock(SELF, physicalMillis::get, Long.MAX_VALUE));

        h.aggregator().onStreamMemoryExceeded(growthExhaustion("edge"));
        physicalMillis.addAndGet(59_999L);
        h.aggregator().onStreamMemoryExceeded(growthExhaustion("edge"));

        assertThat(h.events()).as("59,999 ms: still inside the window").hasSize(1);

        physicalMillis.addAndGet(1L);
        h.aggregator().onStreamMemoryExceeded(growthExhaustion("edge"));

        assertThat(h.events()).as("60,000 ms: the window has ended").hasSize(2);
    }

    // --- slice failure on a node -----------------------------------------------------------------

    /// #1660: the operator surface for a node that stopped hosting a slice after a failed reactivation is
    /// the committed FAILED `NodeArtifactValue` `NodeDeploymentState.handleReactivationFailure` now writes.
    /// One committed put, one WARNING `DeploymentFailed`, naming the node, the artifact and the reason.
    @Test
    void committedFailedNodeArtifactPut_emitsOneWarningDeploymentFailed_namingNodeArtifactAndReason() {
        var h = Harness.create();
        var key = NodeArtifactKey.nodeArtifactKey(new NodeId("node-2"), ROLLBACK_ARTIFACT);
        var value = new NodeArtifactValue(SliceState.FAILED,
                                          Option.some("Reactivation after quorum restore failed: consensus timeout"),
                                          false,
                                          0,
                                          List.of(),
                                          0L);

        h.aggregator().onNodeArtifactPut(new ValuePut<>(new KVCommand.Put<>(key, value), Option.none()));

        var events = h.events();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst()).isInstanceOf(ClusterEvent.DeploymentFailed.class);
        assertThat(events.getFirst().severity()).isEqualTo(ClusterEvent.Severity.WARNING);
        assertThat(events.getFirst().summary()).contains("node-2")
                                               .contains(ROLLBACK_ARTIFACT.asString())
                                               .contains("Reactivation after quorum restore failed");
    }

    // --- production retention -------------------------------------------------------------------

    /// Count bound: with maxCount=3 the partition evicts oldest-on-append; only the newest 3 remain.
    @Test
    void retention_dropsOldestBeyondMaxCount() {
        var retention = RetentionPolicy.retentionPolicy(3, 64L * 1024 * 1024, Long.MAX_VALUE, RetentionMode.ANY);
        var h = Harness.create(retention, OWNER);
        for (int i = 0; i < 6; i++) {
            h.aggregator().onPeerJoined(peerJoined("peer-" + i, List.of(SELF)));
        }
        var events = h.events();
        assertThat(events).hasSize(3);
        assertThat(events.stream().map(e -> e.details().get("nodeId")).toList())
                .containsExactly("peer-3", "peer-4", "peer-5");
    }

    /// The production retention carries all three OOM-guard dimensions (count + byte cap + age) in
    /// ANY mode — a unit assertion that the policy the node wires is genuinely bounded on bytes/age,
    /// not just count.
    @Test
    void retentionPolicy_carriesByteAndAgeCaps() {
        var retention = RetentionPolicy.retentionPolicy(10_000, 64L * 1024 * 1024, 24L * 60 * 60 * 1000, RetentionMode.ANY);
        assertThat(retention.maxCount()).isEqualTo(10_000);
        assertThat(retention.maxBytes()).isEqualTo(64L * 1024 * 1024);
        assertThat(retention.maxAgeMs()).isEqualTo(24L * 60 * 60 * 1000);
        assertThat(retention.mode()).isEqualTo(RetentionMode.ANY);
    }

    // --- pins from the #1923 verification (#752) ---------------------------------------
    private Harness clocked(AtomicLong physicalMillis) {
        return Harness.create(Harness.defaultRetention(), OWNER, () -> false, LEADER,
                              HlcClock.hlcClock(SELF, physicalMillis::get, Long.MAX_VALUE));
    }

    /// PA1: diverged, repaired (shown), diverged again 10 s later and NOT repaired (stuck). The operator's feed must not
    /// end on "repaired" while the subject is diverged. Expected RED at head if the throttle hides the second diverged.
    @Test
    void onOperatorWarning_divergedAgainAfterAShownRepair_isShown() {
        var t = new AtomicLong(1_000_000L);
        var h = clocked(t);

        h.aggregator().onOperatorWarning(diverged("g:orders[0]"));
        t.addAndGet(1_000L);
        h.aggregator().onOperatorWarning(repaired("g:orders[0]"));
        t.addAndGet(10_000L);
        h.aggregator().onOperatorWarning(diverged("g:orders[0]"));

        assertThat(codes(h)).as("feed after D, R, D(stuck)").last().isEqualTo("stream-consumer-state-diverged");
    }

    /// PA2: two subjects interleaved, across a window boundary: each recovery pairs with its own subject only.
    @Test
    void onOperatorWarning_twoSubjects_pairIndependently() {
        var t = new AtomicLong(1_000_000L);
        var h = clocked(t);

        h.aggregator().onOperatorWarning(diverged("g:s[0]"));
        h.aggregator().onOperatorWarning(diverged("g:s[1]"));
        h.aggregator().onOperatorWarning(repaired("g:s[1]"));
        h.aggregator().onOperatorWarning(repaired("g:s[1]"));
        h.aggregator().onOperatorWarning(repaired("g:s[0]"));
        t.addAndGet(70_000L);
        h.aggregator().onOperatorWarning(diverged("g:s[0]"));
        h.aggregator().onOperatorWarning(repaired("g:s[1]"));
        h.aggregator().onOperatorWarning(repaired("g:s[0]"));

        assertThat(h.events().stream().map(e -> e.details().get("code") + "@" + e.details().get("subject")).toList())
            .containsExactly("stream-consumer-state-diverged@g:s[0]",
                             "stream-consumer-state-diverged@g:s[1]",
                             "stream-consumer-state-repaired@g:s[1]",
                             "stream-consumer-state-repaired@g:s[0]",
                             "stream-consumer-state-diverged@g:s[0]",
                             "stream-consumer-state-repaired@g:s[0]");
    }

    /// PA3: diverged re-raised while open and inside its window (throttled), then repaired: exactly one recovery.
    @Test
    void onOperatorWarning_divergedReRaisedWhileOpen_thenOneRepair() {
        var t = new AtomicLong(1_000_000L);
        var h = clocked(t);

        h.aggregator().onOperatorWarning(diverged("g:orders[0]"));
        t.addAndGet(10_000L);
        h.aggregator().onOperatorWarning(diverged("g:orders[0]"));
        t.addAndGet(10_000L);
        h.aggregator().onOperatorWarning(repaired("g:orders[0]"));
        h.aggregator().onOperatorWarning(repaired("g:orders[0]"));

        assertThat(codes(h)).containsExactly("stream-consumer-state-diverged", "stream-consumer-state-repaired");
    }

    /// PA4: the recovery arrives after the warning's throttle window was evicted as idle: still published.
    @Test
    void onOperatorWarning_repairAfterTheThrottleKeyWasEvicted_isPublished() {
        var t = new AtomicLong(1_000_000L);
        var h = clocked(t);

        h.aggregator().onOperatorWarning(diverged("g:orders[0]"));
        t.addAndGet(130_000L);
        h.aggregator().evictIdleThrottleWindows();
        assertThat(h.aggregator().operatorWarningThrottleKeys()).as("control: the window was evicted").isZero();
        h.aggregator().onOperatorWarning(repaired("g:orders[0]"));

        assertThat(codes(h)).containsExactly("stream-consumer-state-diverged", "stream-consumer-state-repaired");
    }

    /// PA5: the pairing touches exactly one pair of codes; every other code keeps the plain 60 s throttle.
    @Test
    void onOperatorWarning_onlyTheDivergencePairIsPaired_otherCodesUnchanged() {
        assertThat(java.util.Arrays.stream(OperatorWarningCode.values()).filter(c -> c.recoveryOf().isPresent()).toList())
            .containsExactly(OperatorWarningCode.STREAM_CONSUMER_STATE_REPAIRED);
        assertThat(java.util.Arrays.stream(OperatorWarningCode.values()).filter(OperatorWarningCode::hasRecovery).toList())
            .containsExactly(OperatorWarningCode.STREAM_CONSUMER_STATE_DIVERGED);
        var t = new AtomicLong(1_000_000L);
        var h = clocked(t);

        for (var code : OperatorWarningCode.values()) {
            if (code.recoveryOf().isPresent()) {
                continue;
            }
            h.aggregator().onOperatorWarning(OperatorWarning.operatorWarning(code, "x", "m"));
            t.addAndGet(1_000L);
            h.aggregator().onOperatorWarning(OperatorWarning.operatorWarning(code, "x", "m"));
        }
        assertThat(h.events()).as("one event per non-recovery code, the repeat throttled")
                              .hasSize(OperatorWarningCode.values().length - 1);
    }

    /// #752: a detach-found divergence is a POINT event with no recovery. Sharing the pass-found code, it opened a
    /// record and consumed the window: a pass-found divergence 10 s later was throttled (never shown) and its repair was
    /// then published against the detach event. As its own code it opens no record and has its own window.
    @Test
    void onOperatorWarning_detachFoundDivergence_opensNoRecord_andHidesNoPassDivergence() {
        var t = new AtomicLong(1_000_000L);
        var h = clocked(t);

        h.aggregator().onOperatorWarning(OperatorWarning.operatorWarning(OperatorWarningCode.STREAM_CONSUMER_DETACH_FOUND_NOTHING,
                                                                         "g:orders[0]",
                                                                         "Detach ... found no subscription"));
        t.addAndGet(10_000L);
        h.aggregator().onOperatorWarning(OperatorWarning.operatorWarning(OperatorWarningCode.STREAM_CONSUMER_STATE_DIVERGED,
                                                                         "g:orders[0]",
                                                                         "held as attached, but the consumer runtime has no subscription"));
        h.aggregator().onOperatorWarning(repaired("g:orders[0]"));

        assertThat(h.events().stream().map(ClusterEvent::summary).toList())
            .as("the repair must follow the pass-found divergence it closes, which must be shown")
            .containsExactly("Detach ... found no subscription",
                             "held as attached, but the consumer runtime has no subscription",
                             "repaired g:orders[0]");
    }

    // --- pins from the #1923 round-2 verification (#752) ---------------------------------------------
    /// A shown diverged, then its repair arrives during a snapshot/resync replay. The repaired of a shown diverged is not
    /// swallowed: replay used to suppress the repair after it had consumed the open mark, so the diverged stayed open
    /// for good.
    @Test
    void onOperatorWarning_repairDuringReplay_ofAShownWarning_isNotSwallowed() {
        var replaying = new java.util.concurrent.atomic.AtomicBoolean(false);
        var h = Harness.create(Harness.defaultRetention(), OWNER, replaying::get, LEADER,
                               HlcClock.hlcClock(SELF, () -> 1_000_000L, Long.MAX_VALUE));

        h.aggregator().onOperatorWarning(diverged("g:orders[0]"));
        assertThat(codes(h)).as("control: the warning is shown").containsExactly("stream-consumer-state-diverged");
        replaying.set(true);
        h.aggregator().onOperatorWarning(repaired("g:orders[0]"));
        replaying.set(false);
        h.aggregator().onOperatorWarning(repaired("g:orders[0]"));

        assertThat(codes(h)).as("a shown diverged is eventually closed").contains("stream-consumer-state-repaired");
    }

    /// A repair raised during replay is released exactly once on the first tick after it, with no second raise needed.
    @Test
    void onOperatorWarning_repairDuringReplay_isReleasedOnTheNextTickAfterIt_once() {
        var replaying = new java.util.concurrent.atomic.AtomicBoolean(false);
        var h = Harness.create(Harness.defaultRetention(), OWNER, replaying::get, LEADER,
                               HlcClock.hlcClock(SELF, () -> 1_000_000L, Long.MAX_VALUE));

        h.aggregator().onOperatorWarning(diverged("g:orders[0]"));
        replaying.set(true);
        h.aggregator().onOperatorWarning(repaired("g:orders[0]"));
        h.aggregator().redeliverDue();
        assertThat(codes(h)).as("control: still replaying, so the repair is held").containsExactly("stream-consumer-state-diverged");
        replaying.set(false);
        h.aggregator().redeliverDue();
        h.aggregator().redeliverDue();
        assertThat(codes(h)).as("released by the tick alone").containsExactly("stream-consumer-state-diverged", "stream-consumer-state-repaired");

        h.aggregator().onOperatorWarning(repaired("g:orders[0]"));

        assertThat(codes(h)).as("once: a later raise finds the warning closed")
                            .containsExactly("stream-consumer-state-diverged", "stream-consumer-state-repaired");
    }

    /// D1 held for redelivery, its repair held (clearing the window), then a recurrence D2 is admitted before D1
    /// lands. The held repair is overwritten. The feed must not END on a false state: it ends on diverged, which is true.
    @Test
    void onOperatorWarning_recurrenceWhileTheRepairIsHeld_feedEndsTruthfully() throws InterruptedException {
        var h = Harness.create();
        var publisher = h.publisher().getAndSet(null);

        h.aggregator().onOperatorWarning(diverged("g:orders[0]"));
        h.aggregator().onOperatorWarning(repaired("g:orders[0]"));
        h.aggregator().onOperatorWarning(diverged("g:orders[0]"));
        h.publisher().set(publisher);
        Thread.sleep(ClusterEventRedelivery.INITIAL_BACKOFF_MS + 100);
        h.aggregator().redeliverDue();
        var afterLanding = codes(h);
        h.aggregator().onOperatorWarning(repaired("g:orders[0]"));

        assertThat(afterLanding).as("never ends on repaired while diverged").last().isEqualTo("stream-consumer-state-diverged");
        assertThat(codes(h)).as("the later repair still closes it").last().isEqualTo("stream-consumer-state-repaired");
    }

    private void expireRedelivery(Harness h, AtomicLong clock) {
        for (long elapsed = 0; elapsed <= ClusterEventRedelivery.RETRY_HORIZON_MS; elapsed += ClusterEventRedelivery.MAX_BACKOFF_MS) {
            clock.addAndGet(ClusterEventRedelivery.MAX_BACKOFF_MS);
            h.aggregator().redeliverDue();
        }
    }

    /// A warning whose publish outcome was UNKNOWN may be in the log; when redelivery gives up on it, its held repair is
    /// released rather than dropped, so the alarm is not left open for good.
    @Test
    void onOperatorWarning_warningGivenUpAfterAnUnknownOutcome_releasesItsHeldRepair() {
        var clock = new AtomicLong(1_000_000L);
        var h = Harness.create(Harness.defaultRetention(), OWNER, () -> false, LEADER,
                               HlcClock.hlcClock(SELF, clock::get, Long.MAX_VALUE));
        var unknown = PublishOutcomeUnknown.FACTORY.apply(Causes.cause("owner died"));

        h.aggregator().interceptPublish(_ -> unknown.promise());

        h.aggregator().onOperatorWarning(diverged("g:orders[0]"));
        h.aggregator().onOperatorWarning(repaired("g:orders[0]"));
        assertThat(h.aggregator().redeliveryWaiting()).as("control: both are held").isEqualTo(1);
        expireRedelivery(h, clock);
        h.aggregator().interceptPublish(event -> h.publisher().get().publish(event));
        clock.addAndGet(ClusterEventRedelivery.MAX_BACKOFF_MS);
        h.aggregator().redeliverDue();

        assertThat(h.aggregator().redeliveryDropped()).as("control: the warning was given up on").containsEntry("expired", 1L);
        assertThat(codes(h)).as("the repair of a possibly-shown warning is published").containsExactly("stream-consumer-state-repaired");
    }

    /// The same give-up after a DEFINITE non-delivery drops the held repair: the warning was never in the log.
    @Test
    void onOperatorWarning_warningGivenUpAfterADefiniteFailure_dropsItsHeldRepair() {
        var clock = new AtomicLong(1_000_000L);
        var h = Harness.create(Harness.defaultRetention(), OWNER, () -> false, LEADER,
                               HlcClock.hlcClock(SELF, clock::get, Long.MAX_VALUE));
        h.aggregator().interceptPublish(_ -> Causes.cause("refused").promise());

        h.aggregator().onOperatorWarning(diverged("g:orders[0]"));
        h.aggregator().onOperatorWarning(repaired("g:orders[0]"));
        expireRedelivery(h, clock);
        h.aggregator().interceptPublish(event -> h.publisher().get().publish(event));
        clock.addAndGet(ClusterEventRedelivery.MAX_BACKOFF_MS);
        h.aggregator().redeliverDue();

        assertThat(h.aggregator().redeliveryDropped()).as("control: the warning was given up on").containsEntry("expired", 1L);
        assertThat(codes(h)).isEmpty();
    }

    /// A repair held during replay is stale once the same subject's condition recurs before the tick: the tick must not
    /// publish it, or the feed would end on "repaired" while the partition is diverged again.
    @Test
    void onOperatorWarning_recurrenceBetweenReplayEndAndTick_discardsTheHeldRepair() {
        var replaying = new java.util.concurrent.atomic.AtomicBoolean(false);
        var t = new AtomicLong(1_000_000L);
        var h = Harness.create(Harness.defaultRetention(), OWNER, replaying::get, LEADER,
                               HlcClock.hlcClock(SELF, t::get, Long.MAX_VALUE));

        h.aggregator().onOperatorWarning(diverged("g:orders[0]"));
        t.addAndGet(5_000L);
        replaying.set(true);
        h.aggregator().onOperatorWarning(repaired("g:orders[0]"));
        replaying.set(false);
        t.addAndGet(500L);
        h.aggregator().onOperatorWarning(diverged("g:orders[0]"));
        t.addAndGet(500L);
        h.aggregator().redeliverDue();

        assertThat(codes(h)).as("feed after D, R(held in replay), D(recurs before the tick), tick")
                            .last().isEqualTo("stream-consumer-state-diverged");
    }

    /// The first attempt fails definitely and a RETRY reports an unknown outcome: the warning may still be in the log, so
    /// give-up releases the held repair. Redelivery must carry the unknown outcome from a retry, not only the first attempt.
    @Test
    void onOperatorWarning_unknownOutcomeOnlyOnARetry_stillReleasesItsHeldRepair() {
        var clock = new AtomicLong(1_000_000L);
        var h = Harness.create(Harness.defaultRetention(), OWNER, () -> false, LEADER,
                               HlcClock.hlcClock(SELF, clock::get, Long.MAX_VALUE));
        var unknown = PublishOutcomeUnknown.FACTORY.apply(Causes.cause("owner died"));
        var attempts = new java.util.concurrent.atomic.AtomicInteger();

        h.aggregator().interceptPublish(_ -> attempts.getAndIncrement() == 0
                                             ? Causes.cause("refused").promise()
                                             : unknown.<org.pragmatica.lang.Unit>promise());
        h.aggregator().onOperatorWarning(diverged("g:orders[0]"));
        h.aggregator().onOperatorWarning(repaired("g:orders[0]"));
        expireRedelivery(h, clock);
        h.aggregator().interceptPublish(event -> h.publisher().get().publish(event));
        clock.addAndGet(ClusterEventRedelivery.MAX_BACKOFF_MS);
        h.aggregator().redeliverDue();

        assertThat(codes(h)).containsExactly("stream-consumer-state-repaired");
    }
}
