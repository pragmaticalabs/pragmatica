// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.worker.health.CommunityHealthMessage;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentContext;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// A follower is denied all community reports from first formation, then becomes leader.
/// Unknown workers are never placement-ready; recovery waits its separate acquisition grace.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
class HierarchicalLeaderObservationGraceTest {
    private static final TimeSpan BUDGET = TimeSpan.timeSpan(120).seconds();
    private final EmberCluster cluster = EmberCluster.emberCluster(3, 35400, 35500, 35600, "observation-grace");

    @AfterEach void stop() {
        cluster.allNodes().forEach(node -> node.setInboundFaultFilter((_, _) -> true));
        LifecycleAwait.bestEffort("stop observation grace", cluster, cluster.stop());
    }

    @Test void newLeaderWithoutHistoryWaitsBeforeRecoveryAndNeverInventsWorkerDeath() {
        LifecycleAwait.settled("start observation grace", cluster, cluster.start());
        await().atMost(BUDGET.duration()).until(() -> cluster.currentLeader().isPresent());
        var previousLeader = leader();
        var followers = cluster.allNodes().stream().filter(node -> !node.self().equals(previousLeader.self())).toList();
        var blocked = new AtomicInteger();
        followers.forEach(node -> node.setInboundFaultFilter((_, message) -> {
            if (message instanceof CommunityHealthMessage.Report) {
                blocked.incrementAndGet();
                return false;
            }
            return true;
        }));
        var workers = new ArrayList<NodeId>();
        for (int index = 0; index < 3; index++) {
            var worker = LifecycleAwait.nodeSettled("admit isolated-report worker", cluster, cluster.addWorkerNode());
            workers.add(worker);
            await().atMost(BUDGET.duration()).until(() -> leader().kvStore().getTyped(
                new AetherKey.ActivationDirectiveKey(worker), AetherValue.ActivationDirectiveValue.class).isPresent());
        }
        var community = leader().kvStore().getTyped(new AetherKey.ActivationDirectiveKey(workers.getFirst()),
            AetherValue.ActivationDirectiveValue.class).unwrap().communityId();
        await().atMost(BUDGET.duration()).until(() -> announcement(community).filter(value -> value.memberCount() == 3).isPresent());
        await().atMost(BUDGET.duration()).until(() -> blocked.get() > 0);
        var previous = announcement(community).unwrap();
        LifecycleAwait.nodeSettled("replace leader with report-blind follower", cluster, cluster.killNode(previousLeader.self().id(), false));
        await().atMost(BUDGET.duration()).until(() -> cluster.currentLeader().filter(id -> !id.equals(previousLeader.self().id())).isPresent());
        var successor = leader();
        var liveness = deploymentContext(successor).communityLiveness();
        assertThat(workers).allMatch(liveness::isAbsent);
        // Default community absence is20s; a short bounded observation pins no immediate recovery.
        await().during(3, TimeUnit.SECONDS).atMost(6, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(announcement(community).unwrap().communityTerm()).isEqualTo(previous.communityTerm());
            assertThat(workers).allMatch(worker -> cluster.getNode(worker.id()).isPresent());
        });
        // Direct candidate pongs still work, but cannot grant until missing-report grace expires.
        await().atMost(BUDGET.duration()).until(() -> announcement(community)
            .filter(value -> value.communityTerm() > previous.communityTerm()).isPresent());
        assertThat(workers).allMatch(worker -> successor.kvStore().getTyped(new AetherKey.ActivationDirectiveKey(worker),
            AetherValue.ActivationDirectiveValue.class).isPresent());
        assertThat(workers).allMatch(worker -> cluster.getNode(worker.id()).isPresent());
        followers.forEach(node -> node.setInboundFaultFilter((_, _) -> true));
        await().atMost(BUDGET.duration()).until(() -> workers.stream().noneMatch(liveness::isAbsent));
    }

    private AetherNode leader() { return cluster.currentLeader().flatMap(cluster::getNode).unwrap(); }
    private org.pragmatica.lang.Option<AetherValue.GovernorAnnouncementValue> announcement(String community) {
        return leader().kvStore().getTyped(AetherKey.GovernorAnnouncementKey.forCommunity(community), AetherValue.GovernorAnnouncementValue.class);
    }
    private static ClusterDeploymentContext deploymentContext(AetherNode node) {
        return Result.lift(() -> {
            var accessor = node.getClass().getDeclaredMethod("clusterDeploymentManager");
            accessor.setAccessible(true);
            var manager = accessor.invoke(node);
            var context = manager.getClass().getDeclaredMethod("context");
            context.setAccessible(true);
            return (ClusterDeploymentContext) context.invoke(manager);
        }).unwrap();
    }
}
