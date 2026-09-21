// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.util.ArrayList;
import java.util.List;
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
import org.pragmatica.aether.worker.governor.GovernorAuthorityMessage;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.CommunityState;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// Concurrent network nominations and same-identity process restart over preserved core journals.
/// Restart continues an unchanged owner generation; only an actual owner handoff must advance it.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
class HierarchicalGovernorConcurrencyRestartTest {
    private static final TimeSpan BUDGET = TimeSpan.timeSpan(120).seconds();
    private static final String COMMUNITY = "concurrent-governors";
    private static final long REQUEST_BASE = 9_000_000;
    private final EmberCluster cluster = EmberCluster.emberCluster(3, 36300, 36400, 36500, "governor-restart");

    @AfterEach void stop() {
        cluster.allNodes().forEach(node -> node.setInboundFaultFilter((_, _) -> true));
        LifecycleAwait.bestEffort("stop governor restart", cluster, cluster.stop());
    }

    @Test void simultaneousCandidatesHaveOneOwnerAndRestartDoesNotResetAuthority() {
        start();
        var workers = addWorkers();
        assignCommunity(workers);
        var replies = new ConcurrentHashMap<NodeId, GovernorAuthorityMessage.Response>();
        workers.forEach(worker -> worker.setInboundFaultFilter((_, message) -> {
            if (message instanceof GovernorAuthorityMessage.Response response
                && response.communityId().equals(COMMUNITY) && response.requestId() >= REQUEST_BASE) replies.put(worker.self(), response);
            return true;
        }));
        var attempts = new AtomicInteger();
        await().pollInterval(TimeSpan.timeSpan(1).seconds().duration()).atMost(BUDGET.duration()).until(() -> {
            if (replies.size() == workers.size() && replies.values().stream().allMatch(response -> response.authority().isPresent())) return true;
            var attempt = attempts.incrementAndGet();
            assertThat(attempt).as("bounded concurrent nomination retries").isLessThanOrEqualTo(60);
            for (int index = 0; index < workers.size(); index++) {
                var worker = workers.get(index);
                var request = new GovernorAuthorityMessage.Request(worker.self(), COMMUNITY, REQUEST_BASE + attempt * 10L + index, 0, "");
                assertThat(HierarchyAuthorityAcceptanceTest.runtime(worker).network().sendOutcome(leader().self(), request)
                    .await(BUDGET).unwrap().isSent()).isTrue();
            }
            return false;
        });
        var before = authority();
        assertThat(replies.values().stream().map(response -> response.authority().unwrap().governorId()).distinct().toList())
            .containsExactly(before.governorId());
        assertThat(replies.values().stream().map(response -> response.authority().unwrap().communityTerm()).distinct().toList())
            .containsExactly(before.communityTerm());
        assertThat(before.communityTerm()).isPositive();
        var oldIds = workers.stream().map(AetherNode::self).toList();
        cluster.allNodes().forEach(node -> node.setInboundFaultFilter((_, _) -> true));
        LifecycleAwait.settled("stop governor and cores preserving journals", cluster, cluster.stop());
        start();
        assertThat(authority().communityTerm()).isGreaterThanOrEqualTo(before.communityTerm());
        var restarted = addWorkers();
        assertThat(restarted.stream().map(AetherNode::self).toList()).containsExactlyElementsOf(oldIds);
        await().atMost(BUDGET.duration()).until(() -> authority().members().containsAll(oldIds));
        var accepted = authority();
        assertThat(accepted.communityTerm()).isGreaterThanOrEqualTo(before.communityTerm());
        var delayed = new GovernorAuthorityMessage.Request(restarted.getFirst().self(), COMMUNITY,
            REQUEST_BASE + 1, 0, "");
        var delivered = new AtomicInteger();
        var core = leader();
        core.setInboundFaultFilter((_, message) -> {
            if (message.equals(delayed)) delivered.incrementAndGet();
            return true;
        });
        assertThat(HierarchyAuthorityAcceptanceTest.runtime(restarted.getFirst()).network().sendOutcome(core.self(), delayed)
            .await(BUDGET).unwrap().isSent()).isTrue();
        await().atMost(BUDGET.duration()).until(() -> delivered.get() > 0);
        assertThat(authority().communityTerm()).isGreaterThanOrEqualTo(accepted.communityTerm());
        assertThat(authority().governorId()).isEqualTo(accepted.governorId());
        core.setInboundFaultFilter((_, _) -> true);
    }

    private void start() {
        LifecycleAwait.settled("start governor restart", cluster, cluster.start());
        await().atMost(BUDGET.duration()).until(() -> cluster.currentLeader().isPresent());
    }
    private List<AetherNode> addWorkers() {
        var result = new ArrayList<AetherNode>();
        for (int index = 0; index < 2; index++) {
            var id = LifecycleAwait.nodeSettled("admit governor candidate", cluster, cluster.addWorkerNode());
            var worker = cluster.getNode(id.id()).unwrap();
            await().atMost(BUDGET.duration()).until(() -> worker.kvStore().getTyped(new AetherKey.ActivationDirectiveKey(id),
                AetherValue.ActivationDirectiveValue.class).isPresent());
            result.add(worker);
        }
        return result;
    }
    private void assignCommunity(List<AetherNode> workers) {
        var node = leader();
        var key = new AetherKey.CommunityKey(COMMUNITY);
        var changes = new ArrayList<KVCommand.Mutation<AetherKey, AetherValue>>();
        changes.add(new KVCommand.Mutation<>(key, Option.none(), Option.some(new AetherValue.CommunityValue("default", "WORKER", 2,
            CommunityState.FORMING, System.currentTimeMillis(), Option.none()))));
        for (var worker : workers) {
            var directive = new AetherKey.ActivationDirectiveKey(worker.self());
            var before = node.kvStore().getTyped(directive, AetherValue.ActivationDirectiveValue.class).unwrap();
            changes.add(new KVCommand.Mutation<>(directive, Option.some(before), Option.some(new AetherValue.ActivationDirectiveValue("WORKER", COMMUNITY, ""))));
        }
        var id = UUID.randomUUID().toString();
        var transaction = new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key, id,
            node.kvStore().getTyped(LeaderKey.INSTANCE, LeaderValue.class).unwrap(), List.of(), changes);
        assertThat(node.<Object>apply(List.of(transaction)).await(BUDGET).unwrap()).anyMatch(value -> value instanceof KVCommand.TransactionResult result
            && result.transactionId().equals(id) && result.accepted());
        await().atMost(BUDGET.duration()).until(() -> workers.stream().allMatch(worker -> worker.kvStore()
            .getTyped(new AetherKey.ActivationDirectiveKey(worker.self()), AetherValue.ActivationDirectiveValue.class)
            .filter(value -> value.communityId().equals(COMMUNITY)).isPresent()));
    }
    private AetherNode leader() { return cluster.currentLeader().flatMap(cluster::getNode).unwrap(); }
    private AetherValue.GovernorAnnouncementValue authority() {
        return leader().kvStore().getTyped(AetherKey.GovernorAnnouncementKey.forCommunity(COMMUNITY), AetherValue.GovernorAnnouncementValue.class).unwrap();
    }
}
