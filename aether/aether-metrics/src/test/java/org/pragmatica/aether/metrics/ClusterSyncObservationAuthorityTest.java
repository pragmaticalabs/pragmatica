// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPing;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPong;
import org.pragmatica.cluster.metrics.MetricObservation;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.lang.Unit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


class ClusterSyncObservationAuthorityTest {
    private static final NodeId SELF = new NodeId("self");
    private static final NodeId CORE = new NodeId("core");
    private static final NodeId WORKER = new NodeId("worker");

    @Test
    void unknownAndRemovedProducersCannotPopulateViewsButAlwaysReceivePong() {
        var network = new RecordingNetwork();
        var collector = ClusterSyncCollector.clusterSyncCollector(SELF, network);

        collector.onClusterSyncPing(ping(WORKER, 0, Map.of(WORKER, sample(1, 1)), false));
        assertThat(collector.metricsFor(WORKER)).isEmpty();
        collector.setMetricsProducerEligibility(WORKER::equals);
        collector.onClusterSyncPing(ping(WORKER, 0, Map.of(WORKER, sample(1, 2)), false));
        assertThat(collector.metricsFor(WORKER)).isNotEmpty();
        collector.setMetricsProducerEligibility(_ -> false);
        collector.onClusterSyncPing(ping(WORKER, 0, Map.of(WORKER, sample(1, 3)), false));
        assertThat(collector.metricsFor(WORKER)).isEmpty();
        assertThat(collector.allMetrics()).doesNotContainKey(WORKER);
        assertThat(collector.allObservations()).doesNotContainKey(WORKER);
        assertThat(network.messages).hasSize(3);
    }

    @Test
    void workerCanPingButCannotAdvanceAuthorityDrainOrRefreshCorePresence() {
        var network = new RecordingNetwork();
        var collector = ClusterSyncCollector.clusterSyncCollector(SELF, network);

        collector.setMetricsProducerEligibility(_ -> true);
        var drains = new AtomicInteger();
        var corePings = new AtomicInteger();

        collector.setPingAuthority(CORE::equals, CORE::equals);
        collector.setDrainCommandHandler(drains::incrementAndGet);
        collector.setCorePingObserver(corePings::incrementAndGet);
        collector.onClusterSyncPing(ping(WORKER, 99, Map.of(WORKER, sample(1, 1)), true));
        assertThat(network.messages).hasSize(1).allMatch(ClusterSyncPong.class::isInstance);
        assertThat(collector.metricsFor(WORKER)).containsEntry("cpu", 0.5);
        assertThat(collector.observedRabiaTerm()).isZero();
        assertThat(drains).hasValue(0);
        assertThat(corePings).hasValue(0);
    }

    @Test
    void identifiedCorePongRefreshesPresenceWithoutAdvancingAuthority() {
        var collector = ClusterSyncCollector.clusterSyncCollector(SELF, new RecordingNetwork());
        var contacts = new AtomicInteger();

        collector.setPingAuthority(CORE::equals, CORE::equals);
        collector.setCorePingObserver(contacts::incrementAndGet);
        collector.onClusterSyncPong(new ClusterSyncPong(WORKER,
                                                        sample(1, 1),
                                                        99,
                                                        99,
                                                        99,
                                                        "READY",
                                                        java.util.List.of(),
                                                        java.util.List.of(),
                                                        java.util.List.of(),
                                                        org.pragmatica.lang.Option.none()));
        collector.onClusterSyncPong(new ClusterSyncPong(CORE,
                                                        sample(1, 1),
                                                        0,
                                                        0,
                                                        0,
                                                        "READY",
                                                        java.util.List.of(),
                                                        java.util.List.of(),
                                                        java.util.List.of(),
                                                        org.pragmatica.lang.Option.none()));
        assertThat(contacts).hasValue(1);
        assertThat(collector.observedRabiaTerm()).isZero();
    }

    @Test
    void staleAuthorityStillGetsResponseWithoutApplyingControlEffects() {
        var network = new RecordingNetwork();
        var collector = ClusterSyncCollector.clusterSyncCollector(SELF, network);

        collector.setMetricsProducerEligibility(_ -> true);
        var drains = new AtomicInteger();

        collector.setPingAuthority(CORE::equals, CORE::equals);
        collector.setDrainCommandHandler(drains::incrementAndGet);
        collector.onClusterSyncPing(ping(CORE, 5, Map.of(), true));
        collector.onClusterSyncPing(ping(CORE, 4, Map.of(), true));
        assertThat(network.messages).hasSize(2);
        assertThat(drains).hasValue(1);
        assertThat(collector.observedRabiaTerm()).isEqualTo(5);
    }

    @Test
    void relayedDuplicateAndReorderedSamplesKeepOriginTimeAndSingleHistoryEntry() {
        var collector = ClusterSyncCollector.clusterSyncCollector(SELF, new NoopNetwork());

        collector.setMetricsProducerEligibility(_ -> true);
        var original = sample(7, 10);

        collector.onClusterSyncPing(ping(CORE, 1, Map.of(WORKER, original), false));
        collector.onClusterSyncPing(ping(CORE, 1, Map.of(WORKER, original), false));
        collector.onClusterSyncPing(ping(WORKER, 99, Map.of(WORKER, sample(7, 9)), false));
        assertThat(collector.historicalMetrics().get(WORKER)).hasSize(1);
        assertThat(collector.historicalMetrics().get(WORKER).getFirst().timestamp()).isEqualTo(original.observedAtMs());
        assertThat(collector.allObservations().get(WORKER)).isEqualTo(original);
    }

    @Test
    void expiredFutureAndOldIncarnationSamplesCannotReplaceCurrentObservation() {
        var collector = ClusterSyncCollector.clusterSyncCollector(SELF, new NoopNetwork());

        collector.setMetricsProducerEligibility(_ -> true);
        var current = sample(7, 10);

        collector.onClusterSyncPing(ping(CORE, 1, Map.of(WORKER, current), false));
        collector.onClusterSyncPing(ping(CORE, 1, Map.of(WORKER, sample(6, 100)), false));
        collector.onClusterSyncPing(ping(CORE,
                                         1,
                                         Map.of(WORKER,
                                                new MetricObservation(8,
                                                                      1,
                                                                      System.currentTimeMillis() - 60_000,
                                                                      Map.of())),
                                         false));
        collector.onClusterSyncPing(ping(CORE,
                                         1,
                                         Map.of(WORKER,
                                                new MetricObservation(8,
                                                                      2,
                                                                      System.currentTimeMillis() + 60_000,
                                                                      Map.of())),
                                         false));
        assertThat(collector.allObservations().get(WORKER)).isEqualTo(current);
    }

    @Test
    void partialCoreBatchDoesNotEvictOtherProducersOrRepeatAuthorityEffects() {
        var collector = ClusterSyncCollector.clusterSyncCollector(SELF, new NoopNetwork());

        collector.setMetricsProducerEligibility(_ -> true);
        var drains = new AtomicInteger();

        collector.setPingAuthority(CORE::equals, CORE::equals);
        collector.setDrainCommandHandler(drains::incrementAndGet);
        collector.onClusterSyncPing(ping(CORE, 1, Map.of(WORKER, sample(1, 1)), true));
        collector.onClusterSyncPing(ping(CORE, 1, Map.of(CORE, sample(1, 2)), false));
        assertThat(collector.allObservations()).containsKeys(WORKER, CORE);
        assertThat(drains).hasValue(1);
    }

    @Test
    void batchPingsReuseOneLocalSampleInsteadOfMultiplyingCollectionAndHistory() {
        var network = new RecordingNetwork();
        var collector = ClusterSyncCollector.clusterSyncCollector(SELF, network);

        collector.setMetricsProducerEligibility(_ -> true);
        for (var index = 0; index < 50; index++) {
            collector.onClusterSyncPing(ping(CORE, 1, Map.of(), false));
        }

        assertThat(network.messages.stream().map(message -> ((ClusterSyncPong) message).observation()).distinct()).hasSize(1);
        assertThat(collector.historicalMetrics().get(SELF)).hasSize(1);
    }

    @Test
    void operationalHistoryHasExplicitResolutionAndBoundedPointCount() {
        var collector = ClusterSyncCollector.clusterSyncCollector(SELF, new NoopNetwork());

        collector.setMetricsProducerEligibility(_ -> true);
        var now = System.currentTimeMillis();

        for (var index = 0; index < 200; index++) {
            collector.injectHistoricalSnapshot(WORKER,
                                               new ClusterSyncCollector.MetricsSnapshot(now - (200 - index) * 1000L,
                                                                                        Map.of("cpu", 0.5)));
        }

        assertThat(collector.historyResolution().millis()).isEqualTo(60_000);
        assertThat(collector.historicalMetrics().get(WORKER)).hasSize(120);
    }

    @Test
    void newerCurrentObservationDoesNotInflateSameBucketHistory() {
        var collector = ClusterSyncCollector.clusterSyncCollector(SELF, new NoopNetwork());

        collector.setMetricsProducerEligibility(_ -> true);
        var now = System.currentTimeMillis();

        for (var sequence = 1; sequence <= 3; sequence++) {
            collector.onClusterSyncPing(ping(CORE,
                                             1,
                                             Map.of(WORKER,
                                                    new MetricObservation(1,
                                                                          sequence,
                                                                          now,
                                                                          Map.of("cpu", sequence / 10.0))),
                                             false));
        }

        assertThat(collector.allObservations().get(WORKER).sequence()).isEqualTo(3);
        assertThat(collector.historicalMetrics().get(WORKER)).hasSize(1);
    }

    private static MetricObservation sample(long incarnation, long sequence) {
        return new MetricObservation(incarnation, sequence, System.currentTimeMillis() - 1000, Map.of("cpu", 0.5));
    }

    private static ClusterSyncPing ping(NodeId sender,
                                        long term,
                                        Map<NodeId, MetricObservation> observations,
                                        boolean authority) {
        return new ClusterSyncPing(sender,
                                   observations,
                                   term,
                                   term,
                                   0L,
                                   Set.of(),
                                   Set.of(SELF),
                                   Map.of(),
                                   Set.of(),
                                   false,
                                   authority);
    }

    private static class RecordingNetwork extends NoopNetwork {
        final List<ProtocolMessage> messages = new ArrayList<>();

        @Override
        public <M extends ProtocolMessage> Unit send(NodeId peer, M message) {
            messages.add(message);

            return Unit.unit();
        }
    }
}
