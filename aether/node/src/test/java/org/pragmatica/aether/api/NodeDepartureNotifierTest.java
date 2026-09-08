// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.pragmatica.aether.node.NodeCodecs;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.stream.FrameworkStreamConsumer;
import org.pragmatica.aether.slice.stream.FrameworkStreamPublisher;
import org.pragmatica.aether.slice.stream.SystemStreams;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.SystemStreamFactories;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;


/// #926 round 2 — pins the COMPOSITION on the confirmed-departure edge.
///
/// An adversarial probe deleted `alertManager.onNodeFailed(...)` from the `AetherNode` boot lambda and
/// **all 1217 tests still passed**. `AlertManager`'s behaviour was pinned in isolation and the
/// aggregator's was too, but nothing pinned that one confirmed departure reaches BOTH. A mutation that
/// leaves every gate green is an unpinned behaviour, not an independent one.
///
/// These tests drive the real production factory, a real `StreamPartitionManager`, the real node codec
/// and a real `AlertManager` — no stubs on the path under test — so deleting either call inside
/// [`NodeDepartureNotifier#onConfirmedDeparture`] turns them red.
class NodeDepartureNotifierTest {

    private static final NodeId SELF = new NodeId("observer-node");
    private static final NodeId DEAD = new NodeId("dead-node");
    private static final SliceCodec CODEC = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());

    private record Fixture(NodeDepartureNotifier notifier, ClusterEventAggregator aggregator, AlertManager alerts) {
        @SuppressWarnings("unchecked")
        static Fixture create() {
            var manager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);
            var retention = RetentionPolicy.retentionPolicy(10_000, 64L * 1024 * 1024, Long.MAX_VALUE, RetentionMode.ANY);
            var config = StreamConfig.streamConfig(SystemStreams.CLUSTER_EVENTS.asString(),
                                                   1,
                                                   retention,
                                                   "earliest",
                                                   64L * 1024,
                                                   ConsistencyMode.EVENTUAL,
                                                   1);
            var publisher = SystemStreamFactories.<ClusterEvent> systemStreamPublisher(SystemStreams.CLUSTER_EVENTS,
                                                                                       manager,
                                                                                       CODEC,
                                                                                       config)
                                                 .unwrap();
            var consumer = SystemStreamFactories.<ClusterEvent> systemStreamConsumer(SystemStreams.CLUSTER_EVENTS,
                                                                                     manager,
                                                                                     CODEC,
                                                                                     CODEC,
                                                                                     config)
                                                .unwrap();
            var pubRef = new AtomicReference<FrameworkStreamPublisher<ClusterEvent>>(publisher);
            var conRef = new AtomicReference<FrameworkStreamConsumer<ClusterEvent>>(consumer);
            // Never leader, never owner — the #926 condition. Both surfaces must respond anyway.
            var aggregator = ClusterEventAggregator.clusterEventAggregator(pubRef::get,
                                                                           conRef::get,
                                                                           () -> false,
                                                                           SELF,
                                                                           HlcClock.hlcClock(SELF),
                                                                           () -> 1,
                                                                           () -> false,
                                                                           () -> false);
            var alerts = AlertManager.readOnly((KVStore<AetherKey, AetherValue>) Mockito.mock(KVStore.class));

            return new Fixture(NodeDepartureNotifier.nodeDepartureNotifier(aggregator, alerts, SELF), aggregator, alerts);
        }

        List<ClusterEvent> events() {
            return aggregator.events().await().or(List.of());
        }
    }

    /// Deleting the aggregator call from the notifier turns this red.
    @Test
    void confirmedDeparture_reachesTheEventStream() {
        var f = Fixture.create();
        f.notifier().onConfirmedDeparture(DEAD);

        var events = f.events();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst()).isInstanceOf(ClusterEvent.NodeFailed.class);
        assertThat(events.getFirst().details()).containsEntry("nodeId", DEAD.id());
        assertThat(events.getFirst().details()).containsEntry("observedBy", SELF.id());
    }

    /// Deleting the alert call from the notifier turns this red — this is the exact mutation that
    /// previously left all 1217 tests green.
    @Test
    void confirmedDeparture_reachesTheAlertSurface() {
        var f = Fixture.create();
        f.notifier().onConfirmedDeparture(DEAD);

        var active = f.alerts().getActiveNodeHealthAlerts();
        assertThat(active).hasSize(1);
        assertThat(active.getFirst().nodeId()).isEqualTo(DEAD);
        assertThat(active.getFirst().observedBy()).isEqualTo(SELF);
        assertThat(active.getFirst().severity()).isEqualTo(AlertEvent.Severity.CRITICAL);
    }

    /// Both, from ONE departure, with no leader and no ownership. Deleting EITHER call turns this red,
    /// which is the property the two tests above cannot express individually.
    @Test
    void oneDeparture_reachesBothSurfaces_withNoLeaderAndNoOwnership() {
        var f = Fixture.create();
        f.notifier().onConfirmedDeparture(DEAD);

        assertThat(f.events()).hasSize(1);
        assertThat(f.alerts().getActiveNodeHealthAlerts()).hasSize(1);
    }

    /// An operator drain still belongs in the stream — a `NodeFailed` record of a drained departure is
    /// legitimate history — but must NOT raise a CRITICAL alert. This pins that the two surfaces diverge
    /// exactly where they should, so the drain-quieting fix cannot be mistaken for suppressing the event
    /// as well.
    ///
    /// **This test previously fed `"SwimDeparted"`** and asserted no alert — encoding the round-2
    /// blocking defect as the specification. `SwimDeparted` is SWIM's death broadcast, not a graceful
    /// goodbye; `DrainRequested` is the only cause that genuinely means "announced".
    @Test
    void drainedDeparture_reachesTheStreamButRaisesNoAlert() {
        var f = Fixture.create();
        f.alerts().noteMembershipTransition(DEAD, "DrainRequested");
        f.notifier().onConfirmedDeparture(DEAD);

        assertThat(f.events()).hasSize(1);
        assertThat(f.events().getFirst()).isInstanceOf(ClusterEvent.NodeFailed.class);
        assertThat(f.alerts().getActiveNodeHealthAlerts()).isEmpty();
    }

    /// Regression pin at the composition level: a SWIM-confirmed death must reach BOTH surfaces. While
    /// `SwimDeparted` sat in the graceful set, this path produced an event and no alert — `kill -9` was
    /// silent on the alert surface.
    @Test
    void swimDeath_reachesBothSurfaces_notJustTheStream() {
        var f = Fixture.create();
        f.alerts().noteMembershipTransition(DEAD, "SwimDeparted");
        f.notifier().onConfirmedDeparture(DEAD);

        assertThat(f.events()).hasSize(1);
        assertThat(f.alerts().getActiveNodeHealthAlerts()).hasSize(1);
        assertThat(f.alerts().getActiveNodeHealthAlerts().getFirst().severity()).isEqualTo(AlertEvent.Severity.CRITICAL);
    }
}
