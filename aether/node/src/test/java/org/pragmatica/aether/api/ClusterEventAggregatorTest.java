// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.stream.FrameworkStreamConsumer;
import org.pragmatica.aether.slice.stream.FrameworkStreamPublisher;
import org.pragmatica.aether.slice.stream.SystemStreams;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.consensus.topology.TransportObservation;
import org.pragmatica.consensus.topology.TransportObservation.ObservationSource;
import org.pragmatica.hlc.HlcClock;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;


/// Stream-namespaces rebuild (Stage 4) — port of the former KV-ring/sweeper aggregator test to
/// the `system:cluster-events:1.0.0` stream model. The old test exercised
/// `onClusterEventLogPut` + a `RingBuffer` projection + `(epoch, seq)` cursor — all deleted. This
/// rewrite drives the same observable surface (`events()`, `eventsSince(Instant)`, producer
/// handlers, MembershipDecision mapping, bounded retention) against a real
/// `ClusterEventStreamBuffer` wired through the framework SPIs.
class ClusterEventAggregatorTest {

    private static final NodeId SELF = new NodeId("self-node");

    private record Harness(ClusterEventAggregator aggregator, HlcClock hlc) {
        static Harness create(RetentionPolicy retention) {
            var wiring = ClusterEventStreamWiring.clusterEventStreamWiring(SystemStreams.CLUSTER_EVENTS, retention).unwrap();
            var pubRef = new AtomicReference<FrameworkStreamPublisher<ClusterEvent>>(wiring.publisher());
            var conRef = new AtomicReference<FrameworkStreamConsumer<ClusterEvent>>(wiring.consumer());
            var hlc = HlcClock.hlcClock(SELF);
            var aggregator = ClusterEventAggregator.clusterEventAggregator(pubRef::get, conRef::get, SELF, hlc);
            return new Harness(aggregator, hlc);
        }

        static Harness create() {
            return create(RetentionPolicy.retentionPolicy(10_000, Long.MAX_VALUE, Long.MAX_VALUE));
        }

        List<ClusterEvent> events() {
            return aggregator.events().await().or(List.of());
        }
    }

    private static TransportObservation.PeerJoined peerJoined(String id, List<NodeId> view) {
        return TransportObservation.peerJoined(new NodeId(id), view, ObservationSource.QUIC);
    }

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

    @Test
    void retention_dropsOldestBeyondMaxCount() {
        var h = Harness.create(RetentionPolicy.retentionPolicy(3, Long.MAX_VALUE, Long.MAX_VALUE));
        for (int i = 0; i < 6; i++) {
            h.aggregator().onPeerJoined(peerJoined("peer-" + i, List.of(SELF)));
        }
        var events = h.events();
        assertThat(events).hasSize(3);
        // Oldest three (peer-0..2) dropped; newest three retained.
        assertThat(events.stream().map(e -> e.details().get("nodeId")).toList())
                .containsExactly("peer-3", "peer-4", "peer-5");
    }
}
