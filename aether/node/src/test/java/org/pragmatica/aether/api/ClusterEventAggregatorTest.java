// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.node.NodeCodecs;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.stream.FrameworkStreamConsumer;
import org.pragmatica.aether.slice.stream.FrameworkStreamPublisher;
import org.pragmatica.aether.slice.stream.SystemStreams;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.StreamPartitionManager.Exhaustion;
import org.pragmatica.aether.stream.SystemStreamFactories;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.consensus.topology.TransportObservation;
import org.pragmatica.consensus.topology.TransportObservation.ObservationSource;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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

    /// Node runtime codec, built exactly as production builds it. Includes the generated
    /// ClusterEvent codecs, so it can encode/decode the sealed hierarchy over the byte[] transport.
    private static final SliceCodec CODEC = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());

    private static final BooleanSupplier OWNER = () -> true;
    private static final BooleanSupplier NOT_OWNER = () -> false;

    private record Harness(ClusterEventAggregator aggregator, HlcClock hlc, StreamPartitionManager manager) {
        static Harness create(RetentionPolicy retention, BooleanSupplier ownerCheck) {
            return create(retention, ownerCheck, () -> false);
        }

        static Harness create(RetentionPolicy retention, BooleanSupplier ownerCheck, BooleanSupplier replayingCheck) {
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
            var hlc = HlcClock.hlcClock(SELF);
            var aggregator = ClusterEventAggregator.clusterEventAggregator(pubRef::get,
                                                                           conRef::get,
                                                                           ownerCheck,
                                                                           SELF,
                                                                           hlc,
                                                                           () -> 1,
                                                                           replayingCheck);
            return new Harness(aggregator, hlc, manager);
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
        h.aggregator().onMembershipDecision(MembershipDecision.nodeRemoved(new NodeId("dead-1"), List.of(SELF)));

        assertThat(h.events()).isEmpty();
    }

    @Test
    void membershipDecision_mapsToDepartureEvents() {
        var h = Harness.create();
        h.aggregator().onMembershipDecision(MembershipDecision.nodeRemoved(new NodeId("dead-1"), List.of(SELF)));
        h.aggregator().onMembershipDecision(MembershipDecision.nodeDecommissioned(new NodeId("gone-2"), List.of(SELF)));
        h.aggregator().onMembershipDecision(MembershipDecision.nodeDraining(new NodeId("drain-3"), List.of(SELF)));
        // Non-departure variants are ignored.
        h.aggregator().onMembershipDecision(MembershipDecision.nodeJoined(new NodeId("join-4"), List.of(SELF)));

        var events = h.events();
        assertThat(events).hasSize(3);
        assertThat(events.get(0)).isInstanceOf(ClusterEvent.NodeFailed.class);
        assertThat(events.get(0).severity()).isEqualTo(ClusterEvent.Severity.CRITICAL);
        assertThat(events.get(1)).isInstanceOf(ClusterEvent.NodeLeft.class);
        assertThat(events.get(2)).isInstanceOf(ClusterEvent.NodeLeft.class);
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

    // --- owner-gated emit (folds B3) ------------------------------------------------------------

    @Test
    void owner_emits() {
        var h = Harness.create(Harness.defaultRetention(), OWNER);
        h.aggregator().onPeerJoined(peerJoined("peer-owner", List.of(SELF)));
        assertThat(h.events()).hasSize(1);
    }

    @Test
    void nonOwner_suppressesEmit() {
        var h = Harness.create(Harness.defaultRetention(), NOT_OWNER);
        h.aggregator().onPeerJoined(peerJoined("peer-x", List.of(SELF)));
        h.aggregator().onMembershipDecision(MembershipDecision.nodeRemoved(new NodeId("dead"), List.of(SELF)));
        // Non-owner publishes nothing — the partition stays empty.
        assertThat(h.events()).isEmpty();
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
}
