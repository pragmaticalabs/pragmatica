// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.worker.governor.GovernorAnnouncer;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.concurrent.CancellableTask;
import org.pragmatica.lang.io.TimeSpan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// Repeated committed activation traverses consensus, scoped metadata and production node routing.
/// Reflection observes existing ownership handles; it neither installs a fake runtime nor arms tasks.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
class HierarchicalWorkerRuntimeReplayTest {
    private static final TimeSpan BUDGET = TimeSpan.timeSpan(120).seconds();
    private final EmberCluster cluster = EmberCluster.emberCluster(3, 35700, 35800, 35900, "runtime-replay");

    @AfterEach void stop() {
        LifecycleAwait.bestEffort("stop runtime replay", cluster, cluster.stop());
    }

    @Test void repeatedActivationReassignmentAndShutdownHaveOneRuntimeOwner() {
        LifecycleAwait.settled("start runtime replay", cluster, cluster.start());
        await().atMost(BUDGET.duration()).until(() -> cluster.currentLeader().isPresent());
        var workerId = LifecycleAwait.nodeSettled("admit runtime replay worker", cluster, cluster.addWorkerNode());
        var worker = cluster.getNode(workerId.id()).unwrap();
        var key = new AetherKey.ActivationDirectiveKey(workerId);
        await().atMost(BUDGET.duration()).until(() -> worker.kvStore().getTyped(key, AetherValue.ActivationDirectiveValue.class).isPresent()
            && announcerHolder(worker).get() != null);
        var original = announcerHolder(worker).get();
        var originalTimer = timer(original);
        var tasks = worker.periodicTasks().armedCount();
        var listeners = observationListenerCount(worker);
        assertThat(tasks).isPositive();
        assertThat(originalTimer.isScheduled()).isTrue();

        for (int index = 0; index < 3; index++) {
            var prior = leader().kvStore().getTyped(key, AetherValue.ActivationDirectiveValue.class).unwrap();
            var repeated = new AetherValue.ActivationDirectiveValue(prior.role(), prior.communityId(), "replay-" + index);
            commit(List.of(new KVCommand.Mutation<>(key, Option.some(prior), Option.some(repeated))));
            await().atMost(BUDGET.duration()).until(() -> worker.kvStore().getTyped(key, AetherValue.ActivationDirectiveValue.class)
                .filter(repeated::equals).isPresent() && !worker.kvStore().hasPendingNotifications());
            assertThat(announcerHolder(worker).get()).isSameAs(original);
            assertThat(timer(original)).isSameAs(originalTimer);
            assertThat(worker.periodicTasks().armedCount()).isEqualTo(tasks);
            assertThat(observationListenerCount(worker)).isEqualTo(listeners);
        }

        var prior = leader().kvStore().getTyped(key, AetherValue.ActivationDirectiveValue.class).unwrap();
        var communityKey = new AetherKey.CommunityKey("runtime-reassigned");
        var originalCommunity = leader().kvStore().getTyped(new AetherKey.CommunityKey(prior.communityId()), AetherValue.CommunityValue.class).unwrap();
        var reassigned = new AetherValue.ActivationDirectiveValue(prior.role(), communityKey.communityId(), "");
        commit(List.of(new KVCommand.Mutation<>(communityKey, Option.none(), Option.some(originalCommunity)),
            new KVCommand.Mutation<>(key, Option.some(prior), Option.some(reassigned))));
        await().atMost(BUDGET.duration()).until(() -> announcerHolder(worker).get() != original
            && announcerHolder(worker).get().communityId().equals(communityKey.communityId()));
        var replacement = announcerHolder(worker).get();
        assertThat(originalTimer.isScheduled()).isFalse();
        assertThat(original.isGovernor()).isFalse();
        assertThat(timer(replacement).isScheduled()).isTrue();
        assertThat(worker.periodicTasks().armedCount()).isEqualTo(tasks);
        assertThat(observationListenerCount(worker)).isEqualTo(listeners);

        // A callback already dequeued before replacement may still reach the retired instance.
        original.onMembershipChange(List.of());
        assertThat(announcerHolder(worker).get()).isSameAs(replacement);
        assertThat(originalTimer.isScheduled()).isFalse();
        assertThat(original.isGovernor()).isFalse();
        assertThat(((java.util.concurrent.atomic.AtomicLong) accessor(original, "sequence")).get()).isEqualTo(-1);

        LifecycleAwait.nodeSettled("stop reassigned worker", cluster, cluster.killNode(workerId.id(), false));
        assertThat(worker.periodicTasks().armedCount()).isZero();
        assertThat(timer(replacement).isScheduled()).isFalse();
        original.onMembershipChange(List.of());
        replacement.onMembershipChange(List.of());
        assertThat(original.isGovernor()).isFalse();
        assertThat(replacement.isGovernor()).isFalse();
        assertThat(((java.util.concurrent.atomic.AtomicLong) accessor(replacement, "sequence")).get()).isEqualTo(-1);
        assertThat(worker.periodicTasks().armedCount()).isZero();
        assertThat(timer(replacement).isScheduled()).isFalse();
    }

    private void commit(List<KVCommand.Mutation<AetherKey, AetherValue>> mutations) {
        var node = leader();
        var transactionId = UUID.randomUUID().toString();
        var command = new KVCommand.LeaderTransaction<AetherKey, AetherValue>(mutations.getFirst().key(), transactionId,
            node.kvStore().getTyped(LeaderKey.INSTANCE, LeaderValue.class).unwrap(), List.of(), mutations);
        var results = node.<Object>apply(List.of(command)).await(BUDGET).unwrap();
        assertThat(results).anyMatch(value -> value instanceof KVCommand.TransactionResult result
            && result.transactionId().equals(transactionId) && result.accepted());
    }

    private AetherNode leader() { return cluster.currentLeader().flatMap(cluster::getNode).unwrap(); }
    @SuppressWarnings("unchecked")
    private static AtomicReference<GovernorAnnouncer> announcerHolder(AetherNode node) {
        return (AtomicReference<GovernorAnnouncer>) accessor(node, "governorAnnouncerHolder");
    }
    private static CancellableTask timer(GovernorAnnouncer announcer) {
        return (CancellableTask) accessor(announcer, "timer");
    }
    private static int observationListenerCount(AetherNode node) {
        var detector = accessor(node, "swimHealthDetector");
        return Result.lift(() -> {
            var field = detector.getClass().getDeclaredField("pendingObservationListeners");
            field.setAccessible(true);
            return ((List<?>) field.get(detector)).size();
        }).unwrap();
    }
    private static Object accessor(Object target, String name) {
        return Result.lift(() -> {
            var method = target.getClass().getDeclaredMethod(name);
            method.setAccessible(true);
            return method.invoke(target);
        }).unwrap();
    }
}
