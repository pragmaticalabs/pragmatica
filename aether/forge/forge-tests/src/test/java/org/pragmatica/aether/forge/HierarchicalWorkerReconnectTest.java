// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.journal.TransitionJournal.Layer;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkServiceMessage.DisconnectNode;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// Bounded six-process transport churn, complementing message-blackout envelope tests. Each
/// worker must record an actual CONNECTED->EVICTED->CONNECTED sequence; merely losing packets
/// or observing an already-connected socket cannot satisfy this test.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
class HierarchicalWorkerReconnectTest {
    private static final TimeSpan BUDGET = TimeSpan.timeSpan(120).seconds();
    private final EmberCluster cluster = EmberCluster.emberCluster(3, 36600, 36700, 36800, "reconnect");

    @AfterEach void stop() {
        LifecycleAwait.bestEffort("stop reconnect fixture", cluster, cluster.stop());
    }

    @Test void correlatedWorkerDisconnectsReconnectAndRepairProjectionWhileCoreCommits() {
        LifecycleAwait.settled("start reconnect fixture", cluster, cluster.start());
        await().atMost(BUDGET.duration()).until(() -> cluster.currentLeader().isPresent());
        var workers = new ArrayList<AetherNode>();
        for (int index = 0; index < 3; index++) {
            var id = LifecycleAwait.nodeSettled("admit reconnect worker", cluster, cluster.addWorkerNode());
            var node = cluster.getNode(id.id()).unwrap();
            await().atMost(BUDGET.duration()).until(() -> node.kvStore().getTyped(new AetherKey.ActivationDirectiveKey(id),
                AetherValue.ActivationDirectiveValue.class).isPresent());
            workers.add(node);
        }
        var baseline = new ConcurrentHashMap<NodeId, Long>();
        workers.forEach(node -> baseline.put(node.self(), node.transitionJournal().snapshot(Layer.PEER, 4096).stream()
            .mapToLong(entry -> entry.seq()).max().orElse(0)));
        var evicted = new ConcurrentHashMap<NodeId, Long>();
        var commits = new AtomicInteger();
        var started = System.nanoTime();
        var maximumCommit = new java.util.concurrent.atomic.AtomicLong();
        await().pollInterval(TimeSpan.timeSpan(1).seconds().duration()).atMost(BUDGET.duration()).until(() -> {
            workers.stream().filter(node -> !evicted.containsKey(node.self())).forEach(node -> {
                var network = HierarchyAuthorityAcceptanceTest.runtime(node).network();
                List.copyOf(node.connectedPeerIds()).forEach(peer -> network.disconnect(new DisconnectNode(peer)));
                node.transitionJournal().snapshot(Layer.PEER, 4096).stream()
                    .filter(entry -> entry.seq() > baseline.get(node.self()) && entry.from().equals("CONNECTED") && entry.to().equals("EVICTED"))
                    .findFirst().ifPresent(entry -> evicted.put(node.self(), entry.seq()));
            });
            var begin = System.nanoTime();
            commitProbe(commits.incrementAndGet());
            maximumCommit.accumulateAndGet(System.nanoTime() - begin, Math::max);
            assertBounds();
            return evicted.size() == workers.size();
        });
        await().atMost(BUDGET.duration()).until(() -> workers.stream().allMatch(node ->
            node.connectedPeerIds().stream().anyMatch(leader().coreNodeIds()::contains)
            && node.transitionJournal().snapshot(Layer.PEER, 4096).stream()
                .anyMatch(entry -> entry.seq() > evicted.get(node.self()) && entry.to().equals("CONNECTED"))));
        // New committed content requires a working metadata channel after the socket churn.
        for (var worker : workers) {
            var key = new AetherKey.ActivationDirectiveKey(worker.self());
            var before = leader().kvStore().getTyped(key, AetherValue.ActivationDirectiveValue.class).unwrap();
            var after = new AetherValue.ActivationDirectiveValue(before.role(), before.communityId(), "after-reconnect");
            commit(key, before, after);
            await().atMost(BUDGET.duration()).until(() -> worker.kvStore().getTyped(key, AetherValue.ActivationDirectiveValue.class)
                .filter(after::equals).isPresent());
        }
        assertThat(commits.get()).isPositive();
        assertThat(leader().coreNodeIds()).hasSize(3).doesNotContainAnyElementsOf(workers.stream().map(AetherNode::self).toList());
        assertBounds();
        System.out.println("HIERARCHY_RECONNECT nodes=6 jvms=1 workers=3 actualEvictions=" + evicted.size()
            + " coreCommits=" + commits + " elapsedMs=" + TimeSpan.timeSpan(System.nanoTime() - started).nanos().millis()
            + " maximumCommitMs=" + TimeSpan.timeSpan(maximumCommit.get()).nanos().millis()
            + " transport=" + leader().transportMetrics());
    }

    private void commitProbe(int index) {
        var key = AetherKey.LogLevelKey.forLogger("hierarchy.reconnect");
        var node = leader();
        var command = new KVCommand.Put<AetherKey, AetherValue>(key,
            AetherValue.LogLevelValue.logLevelValue(key.loggerName(), index % 2 == 0 ? "INFO" : "DEBUG"));
        LifecycleAwait.settled("core commit during reconnect", cluster, TimeSpan.timeSpan(10).seconds(), node.<Object>apply(List.of(command)));
    }
    private void commit(AetherKey key, AetherValue before, AetherValue after) {
        var node = leader();
        var id = UUID.randomUUID().toString();
        var transaction = new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key, id,
            node.kvStore().getTyped(LeaderKey.INSTANCE, LeaderValue.class).unwrap(), List.of(),
            List.of(new KVCommand.Mutation<>(key, Option.some(before), Option.some(after))));
        assertThat(node.<Object>apply(List.of(transaction)).await(BUDGET).unwrap()).anyMatch(value -> value instanceof KVCommand.TransactionResult result
            && result.transactionId().equals(id) && result.accepted());
    }
    private void assertBounds() {
        cluster.allNodes().forEach(node -> {
            assertThat(node.connectedPeerIds().size()).isLessThanOrEqualTo(5);
            var metrics = node.metadataResourceMetrics();
            assertThat(metrics).containsKeys("serverCachedBytes", "clientBufferBytes", "clientVerifiedBytes");
            assertThat(metrics.get("serverCachedBytes")).isLessThanOrEqualTo(metrics.get("serverCacheLimit"));
            assertThat(metrics.get("clientBufferBytes")).isLessThanOrEqualTo(metrics.get("clientScopeLimit"));
            assertThat(metrics.get("clientVerifiedBytes")).isLessThanOrEqualTo(metrics.get("clientCacheLimit"));
        });
    }
    private AetherNode leader() { return cluster.currentLeader().flatMap(cluster::getNode).unwrap(); }
}
