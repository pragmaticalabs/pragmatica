// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPing;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPong;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.SyncResponse;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;

/// Authenticated real transport, real cold synchronization and management routes. Fault filters
/// only discard traffic; they do not bypass sender binding or normal message eligibility.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
class HierarchyAuthorityAcceptanceTest {
    private static final TimeSpan BUDGET = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan REQUEST = TimeSpan.timeSpan(10).seconds();
    private final EmberCluster cluster = EmberCluster.emberCluster(3, 35100, 35200, 35300, "authority");

    @AfterEach void stop() {
        cluster.allNodes().forEach(node -> node.setInboundFaultFilter((_, _) -> true));
        LifecycleAwait.bestEffort("stop authority acceptance", cluster, cluster.stop());
    }

    @Test void workerMajorityCannotSynchronizeColdVoter_orChangeRolesOrIssueDrain() {
        // Ember retains stopped nodes' port slots for identity-preserving restart.
        cluster.withAdditionalNodeSlots(2).unwrap();
        LifecycleAwait.settled("start two of three voters", cluster, cluster.start(Set.of("authority-3")));
        await().atMost(BUDGET.duration()).until(() -> cluster.currentLeader().isPresent());
        var workers = new ArrayList<AetherNode>();
        var workerStarts = new ArrayList<org.pragmatica.lang.Promise<NodeId>>();
        for (int index = 0; index < 4; index++) {
            // Full startup includes DHT encryption-marker verification, which may need the held
            // replica. Preserve its pending promise and prove completion after restoring that core.
            workerStarts.add(cluster.addWorkerNode());
            var id = new NodeId("authority-" + (4 + index));
            await().atMost(BUDGET.duration()).until(() -> cluster.getNode(id.id()).isPresent());
            var worker = cluster.getNode(id.id()).unwrap();
            workers.add(worker);
            await().atMost(BUDGET.duration()).until(() -> directive(id).isPresent());
            assertThat(directive(id).unwrap().role()).isEqualTo("WORKER");
        }
        assertThat(workers).hasSize(4);
        assertThat(leader().coreNodeIds()).hasSize(3);
        assertPromotionRefused(workers.getFirst(), "CORE");

        var cold = cluster.heldBackNode("authority-3").unwrap();
        var captured = new AtomicReference<SyncResponse<?>>();
        var withheld = new AtomicInteger();
        cold.setInboundFaultFilter((_, message) -> {
            if (message instanceof SyncResponse<?> response && workers.stream().noneMatch(worker -> worker.self().equals(response.sender()))) {
                captured.set(response);
                withheld.incrementAndGet();
                return false;
            }
            return true;
        });
        var started = cluster.startHeldBackNodes();
        await().atMost(BUDGET.duration()).until(() -> captured.get() != null);
        var coreState = captured.get();
        var targetInfo = cluster.getNodeInfos().stream().filter(info -> info.id().equals(cold.self())).findFirst().orElseThrow();
        for (var worker : workers) {
            runtime(worker).network().connect(targetInfo);
            await().atMost(BUDGET.duration()).until(() -> worker.connectedPeerIds().contains(cold.self()));
            // Genuine core snapshot, but the authenticated response sender is a WORKER.
            var falseVote = new SyncResponse<>(worker.self(), coreState.state(), coreState.responder());
            assertThat(runtime(worker).network().sendOutcome(cold.self(), falseVote).await(REQUEST).unwrap().isSent()).isTrue();
        }
        await().during(2, TimeUnit.SECONDS).atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(runtime(cold).isActive()).as("four workers cannot replace three-voter synchronization evidence").isFalse();
            assertThat(withheld.get()).isPositive();
        });
        cold.setInboundFaultFilter((_, _) -> true);
        LifecycleAwait.settled("restore real voter sync responses", cluster, BUDGET, started);
        await().atMost(BUDGET.duration()).until(() -> runtime(cold).isActive());
        for (var workerStart : workerStarts) {
            LifecycleAwait.nodeSettled("complete worker startup after held replica returns", cluster, workerStart);
        }

        assertObservationDoesNotAuthorizeDrain(workers.getFirst());
        var follower = cluster.allNodes().stream().filter(node -> leader().coreNodeIds().contains(node.self()))
            .filter(node -> !node.self().equals(leader().self())).findFirst().orElseThrow();
        assertObservationDoesNotAuthorizeDrain(follower);
        assertForwardedMetricsDoNotRefreshOrDoubleCount(workers.getLast(), follower);

        LifecycleAwait.nodeSettled("release one worker slot", cluster, cluster.killNode(workers.getLast().self().id(), false));
        var extra = LifecycleAwait.nodeSettled("admit CORE above desired count", cluster, cluster.addNode());
        var candidate = cluster.getNode(extra.id()).unwrap();
        await().atMost(BUDGET.duration()).until(() -> leader().membershipFsm().memberDescriptor(extra).isPresent());
        assertThat(leader().membershipFsm().memberDescriptor(extra).unwrap().role()).isEqualToIgnoringCase("CORE");
        assertPromotionRefused(candidate, "WORKER");
        assertThat(leader().coreNodeIds()).hasSize(3);
    }

    private void assertObservationDoesNotAuthorizeDrain(AetherNode sender) {
        var receiver = leader();
        var pongs = new AtomicInteger();
        var seen = new AtomicInteger();
        sender.setInboundFaultFilter((peer, message) -> {
            if (peer.equals(receiver.self()) && message instanceof ClusterSyncPong) pongs.incrementAndGet();
            return true;
        });
        receiver.setInboundFaultFilter((peer, message) -> {
            if (peer.equals(sender.self()) && message instanceof ClusterSyncPing ping) {
                if (!ping.drainNodes().contains(receiver.self())) return false;
                seen.incrementAndGet();
            }
            return true;
        });
        for (long term : new long[] {0, Long.MAX_VALUE - 1}) {
            int previousPongs = pongs.get();
            int previousSeen = seen.get();
            var ping = new ClusterSyncPing(sender.self(), Map.of(), term, term, 1, Set.of(),
                Set.of(receiver.self()), Map.of(receiver.self(), "DRAINING"), Set.of(), true, true);
            assertThat(runtime(sender).network().sendOutcome(receiver.self(), ping).await(REQUEST).unwrap().isSent()).isTrue();
            await().atMost(REQUEST.duration()).until(() -> seen.get() > previousSeen && pongs.get() > previousPongs);
            assertThat(receiver.inFlightRequestTracker().isAcceptingNewWork()).isTrue();
            assertThat(receiver.metricsCollector().observedRabiaTerm()).isLessThan(Long.MAX_VALUE - 1);
            assertThat(runtime(receiver).isActive()).isTrue();
        }
        sender.setInboundFaultFilter((_, _) -> true);
        receiver.setInboundFaultFilter((_, _) -> true);
    }

    private void assertForwardedMetricsDoNotRefreshOrDoubleCount(AetherNode producer, AetherNode relay) {
        var receiver = leader();
        var original = producer.metricsCollector().allObservations().get(producer.self());
        var sample = new org.pragmatica.cluster.metrics.MetricObservation(original.incarnation(), Long.MAX_VALUE - 2,
            System.currentTimeMillis() - org.pragmatica.cluster.metrics.MetricObservation.MAX_AGE_MS + 8_000,
            Map.of("hierarchy.acceptance.requests", 7.0));
        var before = receiver.metricsCollector().historicalMetrics().getOrDefault(producer.self(), List.of()).size();
        sendObservation(producer, receiver, producer.self(), sample);
        await().atMost(REQUEST.duration()).untilAsserted(() ->
            assertThat(receiver.metricsCollector().allObservations().get(producer.self())).isEqualTo(sample));
        for (int index = 0; index < 3; index++) {
            sendObservation(relay, receiver, producer.self(), sample);
            sendObservation(producer, receiver, producer.self(), sample);
        }
        assertThat(receiver.metricsCollector().metricsFor(producer.self())).containsEntry("hierarchy.acceptance.requests", 7.0);
        assertThat(receiver.metricsCollector().historicalMetrics().getOrDefault(producer.self(), List.of()).size()).isLessThanOrEqualTo(before + 1);
        var community = directive(producer.self()).unwrap().communityId();
        var typed = new org.pragmatica.aether.worker.metrics.CommunityMetricsSnapshot(community, producer.self(), 1,
            List.of(), sample.observedAtMs(), sample.incarnation(), sample.sequence());
        var overlapping = new org.pragmatica.aether.worker.metrics.CommunityMetricsSnapshot(community, producer.self(), 2,
            List.of(), sample.observedAtMs(), sample.incarnation(), sample.sequence() + 1);
        var batch = new org.pragmatica.aether.worker.metrics.SourceMetricsBatch(relay.self(), List.of(typed, typed, overlapping));
        assertThat(runtime(relay).network().sendOutcome(receiver.self(), batch).await(REQUEST).unwrap().isSent()).isTrue();
        await().atMost(REQUEST.duration()).untilAsserted(() ->
            assertThat(receiver.controlLoop().communitySnapshots().get(producer.self().id())).isEqualTo(typed));
        assertThat(receiver.controlLoop().communitySnapshots().values().stream()
            .filter(value -> value.governorId().equals(producer.self())).mapToInt(value -> value.memberCount()).sum()).isEqualTo(1);
        // Repeated receipts continue through expiry. They must not reset the producer timestamp.
        await().atMost(REQUEST.duration()).until(() -> {
            sendObservation(relay, receiver, producer.self(), sample);
            assertThat(runtime(relay).network().sendOutcome(receiver.self(), batch).await(REQUEST).unwrap().isSent()).isTrue();
            return receiver.metricsCollector().metricsFor(producer.self()).isEmpty()
                && !receiver.controlLoop().communitySnapshots().containsKey(producer.self().id());
        });
        assertThat(receiver.metricsCollector().allObservations()).doesNotContainKey(producer.self());
    }

    private void sendObservation(AetherNode sender, AetherNode receiver, NodeId producer,
        org.pragmatica.cluster.metrics.MetricObservation observation) {
        var ping = new ClusterSyncPing(sender.self(), Map.of(producer, observation), 0, 0, 0,
            Set.of(), Set.of(), Map.of(), Set.of(), false, false);
        assertThat(runtime(sender).network().sendOutcome(receiver.self(), ping).await(REQUEST).unwrap().isSent()).isTrue();
    }

    private void assertPromotionRefused(AetherNode target, String requested) {
        var node = leader();
        var before = directive(target.self());
        var port = cluster.status().nodes().stream().filter(info -> info.id().equals(node.self().id())).findFirst().orElseThrow().mgmtPort();
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/nodes/promote/" + target.self().id()))
            .header("Content-Type", "application/json").timeout(REQUEST.duration())
            .POST(HttpRequest.BodyPublishers.ofString("{\"targetRole\":\"" + requested + "\"}")).build();
        var response = jdkHttpOperations().sendString(request).await(REQUEST).unwrap();
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("immutable");
        assertThat(directive(target.self())).isEqualTo(before);
    }

    private AetherNode leader() { return cluster.currentLeader().flatMap(cluster::getNode).unwrap(); }
    private org.pragmatica.lang.Option<AetherValue.ActivationDirectiveValue> directive(NodeId id) {
        return leader().kvStore().getTyped(new AetherKey.ActivationDirectiveKey(id), AetherValue.ActivationDirectiveValue.class);
    }
    static RabiaNode<?> runtime(AetherNode node) {
        return Result.lift(() -> {
            var accessor = node.getClass().getDeclaredMethod("clusterNode");
            accessor.setAccessible(true);
            return (RabiaNode<?>) accessor.invoke(node);
        }).unwrap();
    }
}
