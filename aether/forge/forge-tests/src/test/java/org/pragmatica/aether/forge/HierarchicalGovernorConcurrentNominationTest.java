// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

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
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;


/// Every worker of one community nominates itself through `GovernorAuthorityMessage.Request` over real
/// transport, with bounded retries. Each round starts every worker's send before awaiting any send outcome,
/// so the nominations are in flight together; whether the leader's handlers overlap is not observed. Every
/// reply that carries an authority, across all rounds, must name the same single governor and the same
/// positive community term as the committed announcement; the one-owner half is checked on every poll.
///
/// This is the nomination half of the former `HierarchicalGovernorConcurrencyRestartTest`. Its other half
/// restarted the cores over preserved journals, a mode #1545 removed with durable control storage.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
class HierarchicalGovernorConcurrentNominationTest {
    private static final TimeSpan BUDGET = TimeSpan.timeSpan(120).seconds();
    private static final String COMMUNITY = "concurrent-governors";
    private static final String NODE_PREFIX = "governor-nomination";
    private static final long REQUEST_BASE = 9_000_000;

    private final EmberCluster cluster = EmberCluster.emberCluster(3, 3880, 3980, 4080, NODE_PREFIX);

    @AfterEach
    void stop() {
        cluster.allNodes().forEach(node -> node.setInboundFaultFilter((_, _) -> true));
        LifecycleAwait.bestEffort("stop governor nomination", cluster, cluster.stop());
    }

    @Test
    void concurrentNomination_allCandidatesAnswered_oneGovernorAndOnePositiveTerm() {
        LifecycleAwait.settled("start governor nomination", cluster, cluster.start());
        await().atMost(BUDGET.duration()).until(() -> cluster.currentLeader()
                                                             .isPresent());
        var workers = addWorkers();
        var replies = new ConcurrentHashMap<NodeId, GovernorAuthorityMessage.Response>();
        var history = new ConcurrentLinkedQueue<GovernorAuthorityMessage.Response>();

        workers.forEach(worker -> worker.setInboundFaultFilter((_, message) -> {
            if (message instanceof GovernorAuthorityMessage.Response response
                && response.communityId()
                           .equals(COMMUNITY)
                && response.requestId() >= REQUEST_BASE) {
                replies.put(worker.self(), response);
                history.add(response);
            }

            return true;
        }));
        var attempts = new AtomicInteger();

        await().pollInterval(TimeSpan.timeSpan(1).seconds().duration())
             .atMost(BUDGET.duration())
             .until(() -> {
                        assertSingleOwner(history);
                        if (replies.size() == workers.size() && replies.values()
                                                                       .stream()
                                                                       .allMatch(response -> response.authority()
                                                                                                     .isPresent())) return true;

                        var attempt = attempts.incrementAndGet();

                        assertThat(attempt).as("bounded concurrent nomination retries")
                                  .isLessThanOrEqualTo(60);
                        var sends = new ArrayList<Promise<WriteOutcome>>();

                        for (int index = 0; index < workers.size(); index++) {
                        var worker = workers.get(index);
                        var request = new GovernorAuthorityMessage.Request(worker.self(),
                                                                           COMMUNITY,
                                                                           REQUEST_BASE + attempt * 10L + index,
                                                                           0,
                                                                           "",
                                                                           false);

                        sends.add(HierarchyAuthorityAcceptanceTest.runtime(worker)
                                                                  .network()
                                                                  .send(leader().self(),
                                                                               request));
                    }

                        sends.forEach(send -> assertThat(send.await(BUDGET).unwrap().isSent()).isTrue());

                        return false;
                    });
        var committed = authority();
        var granted = history.stream().flatMap(response -> response.authority()
                                                                   .stream()).toList();

        assertThat(granted.stream().map(value -> value.governorId()).distinct().toList()).containsExactly(committed.governorId());
        assertThat(granted.stream().map(value -> value.communityTerm()).distinct().toList()).containsExactly(committed.communityTerm());
        assertThat(committed.communityTerm()).isPositive();
    }

    /// Checked on every poll, not only after the loop: a split grant is a violation the moment it is answered, even if
    /// the churn it causes keeps the loop from ever completing.
    private static void assertSingleOwner(ConcurrentLinkedQueue<GovernorAuthorityMessage.Response> history) {
        var granted = history.stream().flatMap(response -> response.authority()
                                                                   .stream()).toList();

        assertThat(granted.stream().map(value -> value.governorId()).distinct().toList()).as("governors granted so far")
                  .hasSizeLessThanOrEqualTo(1);
        assertThat(granted.stream().map(value -> value.communityTerm()).distinct().toList()).as("community terms granted so far")
                  .hasSizeLessThanOrEqualTo(1);
    }

    /// SETUP: the workers start in the test community. H11 (#1840) makes a committed community final and a live worker
    /// has no move (#3327), so the assignment is the FIRST one: the community and both directives are committed, each
    /// expected absent, BEFORE the workers are admitted (production commitWorkerCommunity returns when a directive
    /// exists). The harness mints the next ids by incrementing the prefix counter, so the ids are predicted and checked.
    private List<AetherNode> addWorkers() {
        var node = leader();
        var predicted = List.of(NodeId.nodeId(NODE_PREFIX + "-4").unwrap(), NodeId.nodeId(NODE_PREFIX + "-5").unwrap());
        var key = new AetherKey.CommunityKey(COMMUNITY);
        var changes = new ArrayList<KVCommand.Mutation<AetherKey, AetherValue>>();

        changes.add(new KVCommand.Mutation<>(key,
                                             Option.none(),
                                             Option.some(new AetherValue.CommunityValue("default",
                                                                                        "WORKER",
                                                                                        2,
                                                                                        CommunityState.FORMING,
                                                                                        System.currentTimeMillis(),
                                                                                        Option.none()))));
        for (var id : predicted) {
            changes.add(new KVCommand.Mutation<>(new AetherKey.ActivationDirectiveKey(id),
                                                 Option.none(),
                                                 Option.some(new AetherValue.ActivationDirectiveValue("WORKER",
                                                                                                      COMMUNITY,
                                                                                                      ""))));
        }

        var transactionId = UUID.randomUUID().toString();
        var transaction = new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key,
                                                                                  transactionId,
                                                                                  node.kvStore()
                                                                                      .getTyped(LeaderKey.INSTANCE,
                                                                                                LeaderValue.class)
                                                                                      .unwrap(),
                                                                                  List.of(),
                                                                                  changes);

        assertThat(node.<Object> apply(List.of(transaction)).await(BUDGET).unwrap()).anyMatch(value -> value instanceof KVCommand.TransactionResult result
                                                                                                       && result.transactionId()
                                                                                                                .equals(transactionId)
                                                                                                       && result.accepted());
        var result = new ArrayList<AetherNode>();

        for (var expected : predicted) {
            var id = LifecycleAwait.nodeSettled("admit governor candidate", cluster, cluster.addWorkerNode());
            var worker = cluster.getNode(id.id()).unwrap();

            assertThat(id).isEqualTo(expected);
            await().atMost(BUDGET.duration())
                 .until(() -> worker.kvStore()
                                    .getTyped(new AetherKey.ActivationDirectiveKey(id),
                                              AetherValue.ActivationDirectiveValue.class)
                                    .filter(value -> value.communityId()
                                                          .equals(COMMUNITY))
                                    .isPresent());
            result.add(worker);
        }

        return result;
    }

    private AetherNode leader() {
        return cluster.currentLeader()
                      .flatMap(cluster::getNode)
                      .unwrap();
    }

    private AetherValue.GovernorAnnouncementValue authority() {
        return leader().kvStore()
                     .getTyped(AetherKey.GovernorAnnouncementKey.forCommunity(COMMUNITY),
                               AetherValue.GovernorAnnouncementValue.class)
                     .unwrap();
    }
}
