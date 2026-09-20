// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.pragmatica.aether.metrics.fsm.ClusterSyncContext;
import org.pragmatica.aether.metrics.fsm.ClusterSyncState;
import org.pragmatica.aether.metrics.observation.PeerObservationStore;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPing;
import org.pragmatica.cluster.metrics.MetricObservation;
import org.pragmatica.aether.worker.metrics.CommunityMetricsSnapshot;
import org.pragmatica.aether.worker.metrics.SourceMetricsBatch;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.statemachine.Fsm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


class ClusterSyncMetricsScopeTest {
    private static final NodeId SELF = new NodeId("self");
    private static final NodeId CORE = new NodeId("core");
    private static final NodeId WORKER = new NodeId("worker");

    @Test
    void completeCoreCoverageUsesBoundedBatchesAndWorkerReceivesOnlySenderSample() {
        verifyCoverage(300);
    }

    @Test
    void tenThousandProducerCoverageRemainsCompleteAndWorkerPayloadRemainsConstant() {
        verifyCoverage(10_000);
    }

    private void verifyCoverage(int producerCount) {
        var network = new RecordingNetwork();
        var collector = ClusterSyncCollector.clusterSyncCollector(SELF, network);
        collector.setMetricsProducerEligibility(_ -> true);
        var source = IntStream.range(0, producerCount)
                              .boxed()
                              .collect(Collectors.toMap(n -> new NodeId("producer-" + n),
                                                        _ -> new MetricObservation(1,
                                                                                   1,
                                                                                   System.currentTimeMillis(),
                                                                                   Map.of("cpu", 0.5))));

        collector.onClusterSyncPing(new ClusterSyncPing(CORE,
                                                        source,
                                                        1,
                                                        1,
                                                        0,
                                                        Set.of(),
                                                        Set.of(),
                                                        Map.of(),
                                                        Set.of(),
                                                        false,
                                                        false));
        network.sent.clear();
        var context = context(network, collector);

        context.setMetricsRecipient(CORE::equals);
        context.setDispatchedNodesSupplier(() -> Set.of(WORKER));
        context.setSourceMetricsSupplier(() -> source.keySet()
                                                     .stream()
                                                     .map(producer -> new CommunityMetricsSnapshot("community",
                                                                                                   producer,
                                                                                                   1,
                                                                                                   List.of(),
                                                                                                   System.currentTimeMillis(),
                                                                                                   1,
                                                                                                   1))
                                                     .toList());
        context.broadcastPing(Epoch.epoch(1, 0), 1);
        var typedBatches = network.sent.stream().filter(item -> item.message() instanceof SourceMetricsBatch).toList();

        assertThat(typedBatches).hasSize(Math.ceilDiv(producerCount, 128))
                  .allSatisfy(item -> assertThat(item.peer()).isEqualTo(CORE));
        assertThat(typedBatches).allSatisfy(item -> assertThat(((SourceMetricsBatch) item.message()).snapshots()).hasSizeLessThanOrEqualTo(128));
        assertThat(typedBatches.stream()
                               .mapToInt(item -> ((SourceMetricsBatch) item.message()).snapshots()
                                                                                      .size())
                               .sum()).isEqualTo(producerCount);
        var corePings = network.pingsTo(CORE);

        assertThat(corePings).hasSize(Math.ceilDiv(producerCount + 1, 128));
        assertThat(corePings).allSatisfy(ping -> assertThat(ping.observations()).hasSizeLessThanOrEqualTo(128));
        var delivered = corePings.stream()
                                 .flatMap(ping -> ping.observations()
                                                      .keySet()
                                                      .stream())
                                 .collect(Collectors.toSet());

        assertThat(delivered).containsAll(source.keySet()).contains(SELF).hasSize(producerCount + 1);
        assertThat(corePings.stream().filter(ClusterSyncPing::carriesAuthority)).hasSize(1);
        assertThat(corePings.stream().filter(ping -> !ping.carriesAuthority())).allSatisfy(ping -> assertThat(ping.dispatchedNodes()).isEmpty());
        assertThat(network.pingsTo(WORKER)).singleElement()
                  .satisfies(ping -> {
                                 assertThat(ping.observations()).containsOnlyKeys(SELF);
                                 assertThat(ping.dispatchedNodes()).isEmpty();
                                 assertThat(ping.completeMetricsRoster()).isFalse();
                             });
    }

    private static ClusterSyncContext context(RecordingNetwork network, ClusterSyncCollector collector) {
        var ref = new AtomicReference<ClusterSyncContext>();

        Fsm.<ClusterSyncState, ClusterFsmEvent> fsm("metrics-scope",
                                                    fsm -> {
                                                        var context = new ClusterSyncContext(fsm,
                                                                                             SELF,
                                                                                             network,
                                                                                             collector,
                                                                                             TimeSpan.timeSpan(1).hours(),
                                                                                             () -> 1L,
                                                                                             3,
                                                                                             () -> Epoch.epoch(1, 0),
                                                                                             PeerObservationStore.peerObservationStore());

                                                        ref.set(context);

                                                        return context.dormant();
                                                    });

        return ref.get();
    }

    private record Sent(NodeId peer, ProtocolMessage message) {}

    private static class RecordingNetwork extends NoopNetwork {
        final List<Sent> sent = new ArrayList<>();

        @Override
        public Set<NodeId> connectedPeers() {
            return Set.of(CORE, WORKER);
        }

        @Override
        public <M extends ProtocolMessage> Unit send(NodeId peer, M message) {
            sent.add(new Sent(peer, message));

            return Unit.unit();
        }

        List<ClusterSyncPing> pingsTo(NodeId peer) {
            return sent.stream()
                       .filter(item -> item.peer()
                                           .equals(peer) && item.message() instanceof ClusterSyncPing)
                       .map(item -> (ClusterSyncPing) item.message())
                       .toList();
        }
    }
}
