// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// Drop only incumbent governor reports at every core. The old governor retains its direct
/// pongs and community links; recovery must replace reporting authority without declaring death.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HierarchicalGovernorReportLossTest {
    private static final TimeSpan BUDGET = TimeSpan.timeSpan(120).seconds();
    private EmberCluster cluster;

    @BeforeAll void start() {
        cluster = EmberCluster.emberCluster(3, 30400, 30500, 30600, "report-loss");
        LifecycleAwait.settled("start hierarchy cluster", cluster, cluster.start());
        await().atMost(BUDGET.millis(), TimeUnit.MILLISECONDS).until(() -> cluster.currentLeader().isPresent());
    }

    @AfterAll void stop() {
        Option.option(cluster).onPresent(value -> LifecycleAwait.bestEffort("stop hierarchy cluster", value, value.stop()));
    }

    @Test void reportOnlyLossReplacesAuthorityWhileEveryWorkerRemainsAlive() {
        var workers = new ArrayList<NodeId>();
        for (int index = 0; index < 3; index++) {
            var worker = LifecycleAwait.nodeSettled("admit hierarchy worker", cluster, cluster.addWorkerNode());
            workers.add(worker);
            await().atMost(BUDGET.millis(), TimeUnit.MILLISECONDS).until(() -> directive(worker).isPresent());
        }
        var community = directive(workers.getFirst()).unwrap().communityId();
        await().atMost(BUDGET.millis(), TimeUnit.MILLISECONDS).untilAsserted(() ->
            assertThat(announcement(community).filter(value -> value.memberCount() == workers.size()).isPresent())
                .as("committed governor roster: %s", diagnostics(community, workers)).isTrue());
        await().atMost(BUDGET.millis(), TimeUnit.MILLISECONDS).untilAsserted(() ->
            assertThat(leader().kvStore().getTyped(AetherKey.CommunityKey.communityKey(community), AetherValue.CommunityValue.class)
                .filter(value -> value.state() == org.pragmatica.aether.slice.kvstore.CommunityState.ACTIVE).isPresent())
                .as("fresh hierarchical health reaches viability: %s", diagnostics(community, workers)).isTrue());
        await().atMost(BUDGET.millis(), TimeUnit.MILLISECONDS).until(() -> leader().membershipFsm().dhtRoutableMembers().containsAll(workers));
        assertThat(leader().membershipFsm().coreCountedMembers()).doesNotContainAnyElementsOf(workers).hasSize(3);
        var previous = announcement(community).unwrap();
        var dropped = new java.util.concurrent.atomic.AtomicInteger();
        var staleReport = new java.util.concurrent.atomic.AtomicReference<org.pragmatica.aether.worker.health.CommunityHealthMessage.Report>();
        var cores = cluster.allNodes().stream().filter(node -> !workers.contains(node.self())).toList();
        cores.forEach(node -> node.setInboundFaultFilter((sender, message) -> {
            if (sender.equals(previous.governorId())
                && message instanceof org.pragmatica.aether.worker.health.CommunityHealthMessage.Report report) {
                staleReport.compareAndSet(null, report);
                dropped.incrementAndGet();
                return false;
            }
            return true;
        }));
        await().atMost(BUDGET.millis(), TimeUnit.MILLISECONDS).until(() -> dropped.get() > 0);
        await().during(1, TimeUnit.SECONDS).atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(announcement(community).unwrap().communityTerm()).isEqualTo(previous.communityTerm()));
        await().atMost(BUDGET.millis(), TimeUnit.MILLISECONDS).untilAsserted(() ->
            assertThat(announcement(community).filter(value -> value.communityTerm() > previous.communityTerm()
                && !value.governorId().equals(previous.governorId())).isPresent())
                .as("report-only authority recovery: %s", diagnostics(community, workers)).isTrue());
        await().atMost(BUDGET.millis(), TimeUnit.MILLISECONDS).until(() -> leader().membershipFsm().dhtRoutableMembers().containsAll(workers));
        assertThat(workers.stream().map(worker -> cluster.getNode(worker.id())).allMatch(Option::isPresent)).isTrue();
        assertThat(workers.stream().map(this::directive).allMatch(Option::isPresent)).isTrue();
        var oldGovernor = cluster.getNode(previous.governorId().id()).unwrap();
        assertThat(oldGovernor.connectedPeerIds()).anyMatch(peer -> workers.contains(peer) && !peer.equals(previous.governorId()));
        assertThat(leader().membershipFsm().coreCountedMembers()).doesNotContainAnyElementsOf(workers).hasSize(3);
        cores.forEach(node -> node.setInboundFaultFilter((_, _) -> true));
        var accepted = announcement(community).unwrap();
        assertThat(staleReport.get()).as("actual report captured before authority replacement").isNotNull();
        var delivered = new java.util.concurrent.atomic.AtomicInteger();
        var target = leader();
        target.setInboundFaultFilter((sender, message) -> {
            if (sender.equals(previous.governorId()) && message.equals(staleReport.get())) delivered.incrementAndGet();
            return true;
        });
        assertThat(HierarchyAuthorityAcceptanceTest.runtime(oldGovernor).network()
            .sendOutcome(target.self(), staleReport.get()).await(BUDGET).unwrap().isSent()).isTrue();
        await().atMost(BUDGET.duration()).until(() -> delivered.get() > 0);
        await().during(2, TimeUnit.SECONDS).atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            var current = announcement(community).unwrap();
            assertThat(current.governorId()).isEqualTo(accepted.governorId());
            assertThat(current.communityTerm()).isEqualTo(accepted.communityTerm());
            assertThat(leader().membershipFsm().coreCountedMembers()).doesNotContainAnyElementsOf(workers);
        });
        target.setInboundFaultFilter((_, _) -> true);
    }

    private String diagnostics(String community, List<NodeId> workers) {
        return "authority=" + announcement(community) + ", assignments=" + workers.stream().map(node -> node + "=" + directive(node)).toList()
            + ", readiness=" + leader().metricsCollector().reportedStates()
            + ", nodes=" + cluster.allNodes().stream().map(node -> node.self() + " ready=" + node.isReady()
                + " storage=" + org.pragmatica.aether.node.StorageFactory.pendingDhtAdmissions(node.storageSetups())
                + " connected=" + node.connectedPeerIds() + " authority=" + node.kvStore().getTyped(AetherKey.GovernorAnnouncementKey.forCommunity(community), AetherValue.GovernorAnnouncementValue.class)).toList();
    }

    private AetherNode leader() { return cluster.currentLeader().flatMap(cluster::getNode).unwrap(); }
    private Option<AetherValue.ActivationDirectiveValue> directive(NodeId node) {
        return leader().kvStore().getTyped(new AetherKey.ActivationDirectiveKey(node), AetherValue.ActivationDirectiveValue.class);
    }
    private Option<AetherValue.GovernorAnnouncementValue> announcement(String community) {
        return leader().kvStore().getTyped(AetherKey.GovernorAnnouncementKey.forCommunity(community), AetherValue.GovernorAnnouncementValue.class);
    }
}
