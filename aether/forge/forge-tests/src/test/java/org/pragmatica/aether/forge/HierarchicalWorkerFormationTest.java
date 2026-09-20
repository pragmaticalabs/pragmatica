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

/// Production admission -> metadata projection -> committed governor -> hierarchical positive
/// evidence, followed by governor replacement. Worker loss is not core voter reconfiguration.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HierarchicalWorkerFormationTest {
    private static final TimeSpan BUDGET = TimeSpan.timeSpan(120).seconds();
    private EmberCluster cluster;

    @BeforeAll void start() {
        cluster = EmberCluster.emberCluster(3, 28400, 28500, 28600, "hierarchy");
        LifecycleAwait.settled("start hierarchy cluster", cluster, cluster.start());
        await().atMost(BUDGET.millis(), TimeUnit.MILLISECONDS).until(() -> cluster.currentLeader().isPresent());
    }

    @AfterAll void stop() {
        Option.option(cluster).onPresent(value -> LifecycleAwait.bestEffort("stop hierarchy cluster", value, value.stop()));
    }

    @Test void manualWorkersFormCommunity_andReplaceLostGovernorWithoutEnteringCoreElectorate() {
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
        LifecycleAwait.nodeSettled("kill governor", cluster, cluster.killNode(previous.governorId().id(), false));
        var survivors = workers.stream().filter(node -> !node.equals(previous.governorId())).toList();
        await().atMost(BUDGET.millis(), TimeUnit.MILLISECONDS).until(() -> announcement(community)
            .filter(value -> value.communityTerm() > previous.communityTerm() && survivors.contains(value.governorId())).isPresent());
        await().atMost(BUDGET.millis(), TimeUnit.MILLISECONDS).until(() -> leader().membershipFsm().dhtRoutableMembers().containsAll(survivors));
        assertThat(leader().membershipFsm().coreCountedMembers()).doesNotContainAnyElementsOf(workers).hasSize(3);
        assertThat(survivors.stream().map(this::directive).allMatch(Option::isPresent)).isTrue();
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
