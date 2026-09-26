// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;


/// S28 live path: across real leader failovers, every new leader's minted term (the `rabiaTerm` of its
/// generation epoch, the dominant component of every leader-authored ownership/assignment epoch) is
/// strictly above its predecessor's, and equals the committed `LeaderValue.viewSequence` that named it.
/// A per-process count of local leader gains fails the first assertion on the FIRST failover: each
/// successor is on its first tenure, so it mints term 1, the same as the dead leader.
///
/// Five cores so two successive kills keep a quorum of three.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
class LeaderTermFailoverTest {
    private static final TimeSpan BUDGET = TimeSpan.timeSpan(120).seconds();

    private final EmberCluster cluster = EmberCluster.emberCluster(5, 37400, 37500, 37600, "leader-term");

    @AfterEach
    void stop() {
        LifecycleAwait.bestEffort("stop leader-term cluster", cluster, cluster.stop());
    }

    @Test
    void newLeaderTerm_isStrictlyAboveThePriorLeaders_acrossTwoFailovers() {
        LifecycleAwait.settled("start leader-term cluster", cluster, cluster.start());
        var first = awaitLeaderTerm(none());
        var second = failOver(first);

        failOver(second);
    }

    private LeaderTermObservation failOver(LeaderTermObservation prior) {
        LifecycleAwait.nodeSettled("kill leader " + prior.leaderId(),
                                   cluster,
                                   cluster.killNode(prior.leaderId()));
        var successor = awaitLeaderTerm(some(prior.leaderId()));

        assertThat(successor.term()).as("successor %s term vs prior leader %s term",
                                        successor.leaderId(),
                                        prior.leaderId())
                  .isGreaterThan(prior.term());

        return successor;
    }

    /// Waits for a leader other than `excluded` whose committed `LeaderKey` names it, then reads the term
    /// it mints. The committed sequence is the expected term: the value the fix derives the term from.
    private LeaderTermObservation awaitLeaderTerm(Option<String> excluded) {
        await().atMost(BUDGET.duration()).until(() -> settledLeader(excluded).isPresent());
        var leader = settledLeader(excluded).unwrap();
        var committed = committedLeader(leader).unwrap();
        var term = mintedTerm(leader);

        assertThat(term).as("leader %s term vs its committed viewSequence",
                            leader.self().id())
                  .isEqualTo(committed.viewSequence());

        return LeaderTermObservation.leaderTermObservation(leader.self().id(),
                                                           term);
    }

    private Option<AetherNode> settledLeader(Option<String> excluded) {
        return cluster.currentLeader()
                      .filter(id -> isNotExcluded(excluded, id))
                      .flatMap(cluster::getNode)
                      .filter(LeaderTermFailoverTest::committedAsLeader)
                      .filter(node -> mintedTerm(node) > 0L);
    }

    private static boolean isNotExcluded(Option<String> excluded, String id) {
        return excluded.filter(id::equals)
                       .isEmpty();
    }

    private static long mintedTerm(AetherNode node) {
        return node.currentGenerationEpoch()
                   .rabiaTerm();
    }

    private static boolean committedAsLeader(AetherNode node) {
        return committedLeader(node).filter(value -> value.leader()
                                                          .equals(node.self()))
                              .isPresent();
    }

    private static Option<LeaderValue> committedLeader(AetherNode node) {
        return node.kvStore()
                   .getTyped(LeaderKey.INSTANCE, LeaderValue.class);
    }

    private record LeaderTermObservation(String leaderId, long term) {
        static LeaderTermObservation leaderTermObservation(String leaderId, long term) {
            return new LeaderTermObservation(leaderId, term);
        }
    }
}
