// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.pragmatica.aether.api.ClusterEvent;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.consensus.topology.ObserverResumedNotification;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #2306 — `QUORUM_LOST` is a statement about voters, through the REAL wiring of a node (`bindSelfIsVoter`,
/// the `ObserverResumedNotification` route), not a hand-built aggregator.
///
/// Five voters plus one node outside the voter roster (an observer, as an auto-heal replacement is while it joins).
///   - The observer is told it went passive: it publishes the WARNING `observer-consensus-lagging`, never `QUORUM_LOST`;
///     told it observes again, it publishes the INFO recovery `observer-consensus-resumed`.
///   - A lagging observer that is then promoted (ACTIVE) closes the warning with the same recovery.
///   - Two voters are killed: a voter majority remains, so a commit still succeeds (the observer in the cluster does
///     not turn a surviving majority into a loss); a killed voter's own shutdown is still published as CRITICAL
///     `QUORUM_LOST` (a node wired as an observer would publish the warning instead), and no survivor raises one.
@PortBudget
class EmberObserverQuorumLossWiringTest {
    private static final int CLUSTER_SIZE = 5;
    private static final int SLOTS = 2 * CLUSTER_SIZE + 2;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_HTTP_OFFSET = 80;
    private static final EmberTestPorts.Block PORTS = new EmberTestPorts.Block(EmberTestPorts.POOL_FIRST,
                                                                               EmberTestPorts.POOL_LAST,
                                                                               EmberTestPorts.POOL_STEP,
                                                                               SLOTS,
                                                                               MGMT_OFFSET,
                                                                               APP_HTTP_OFFSET);
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(180).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.stop().await(STOP_BOUND);
        }
    }

    @Test
    @Timeout(600)
    void observerPassive_raisesLagWarningNotQuorumLost_andVotersStillRaiseQuorumLost() {
        cluster = EmberTestPorts.startedCluster(PORTS,
                                                basePort -> emberCluster(CLUSTER_SIZE,
                                                                         basePort,
                                                                         basePort + MGMT_OFFSET,
                                                                         basePort + APP_HTTP_OFFSET,
                                                                         "obs"),
                                                START_BOUND);
        awaitCondition("leader", 90_000L, () -> cluster.getLeaderManagementPort().isPresent());
        var voterIds = cluster.allNodes().stream().map(n -> n.self().id()).toList();
        var observerId = cluster.addNode().await(START_BOUND).unwrap().id();
        var observer = cluster.getNode(observerId).unwrap();
        awaitCondition("observer joined", 60_000L, () -> cluster.allNodes().size() == CLUSTER_SIZE + 1);

        observer.route(ClusterStateNotification.passive());
        awaitCondition(() -> "observer-consensus-lagging for " + observerId + "; events: " + codesSeenBy(observer),
                       60_000L,
                       () -> hasWarning(observer, "observer-consensus-lagging", observerId));
        observer.route(ObserverResumedNotification.observerResumed(observer.self()));
        awaitCondition(() -> "observer-consensus-resumed for " + observerId + "; events: " + codesSeenBy(observer),
                       60_000L,
                       () -> hasWarning(observer, "observer-consensus-resumed", observerId));
        assertThat(quorumLossObservers(observer)).as("the observer's passive is not a quorum loss").doesNotContain(observerId);

        observer.route(ClusterStateNotification.passive());
        awaitCondition(() -> "second observer-consensus-lagging for " + observerId + "; events: " + codesSeenBy(observer),
                       60_000L,
                       () -> countWarnings(observer, "observer-consensus-lagging", observerId) >= 2);
        observer.route(ClusterStateNotification.active());
        awaitCondition(() -> "observer-consensus-resumed on promotion (ACTIVE) for " + observerId + "; events: " + codesSeenBy(observer),
                       60_000L,
                       () -> countWarnings(observer, "observer-consensus-resumed", observerId) >= 2);

        var leaderId = cluster.currentLeader().unwrap();
        var killed = voterIds.stream().filter(id -> !id.equals(leaderId)).limit(2).toList();

        killed.forEach(id -> cluster.killNode(id, false).await(STOP_BOUND));
        var survivors = List.copyOf(cluster.allNodes());

        awaitCondition(() -> "CRITICAL QuorumLost observed by a killed voter " + killed + "; saw " + quorumLossObservers(survivors.getFirst()),
                       120_000L,
                       () -> quorumLossObservers(survivors.getFirst()).stream().anyMatch(killed::contains));
        var survivorIds = survivors.stream().map(n -> n.self().id()).toList();

        assertThat(quorumLossObservers(survivors.getFirst())).as("a voter majority survives: no survivor reports a quorum loss")
                                                             .doesNotContainAnyElementsOf(survivorIds);
        var committed = survivors.stream()
                                 .filter(AetherNode::isLeader)
                                 .findFirst()
                                 .orElse(survivors.getFirst())
                                 .<Object>apply(List.of(new KVCommand.Noop<AetherKey>(AetherKey.StreamPartitionOwnershipKey.streamPartitionOwnershipKey("obs",
                                                                                                                                                       0))))
                                 .await(TimeSpan.timeSpan(30).seconds());

        assertThat(committed.isSuccess()).as("3 of 5 voters alive plus the observer: consensus still commits; " + committed).isTrue();
    }

    /// The mirror case (#2306): an observer must never count toward the voter quorum. Five voters plus an observer that the
    /// survivors' membership view already counts (six); the leader and two more voters are killed at once, leaving two
    /// voters of five plus the observer. The two survivors must detect the loss (their topology quorum bit drops, which
    /// pauses the engine) and must NOT commit. The observer is retired by the leader a few seconds after it joins, so the
    /// leader goes first and the kills follow the moment the observer is counted; if the observer is already gone the test
    /// reports that precondition instead of passing vacuously.
    @Test
    @Timeout(600)
    void twoVotersPlusAnObserver_detectTheLossAndPause_andDoNotCommit() {
        cluster = EmberTestPorts.startedCluster(PORTS,
                                                basePort -> emberCluster(CLUSTER_SIZE,
                                                                         basePort,
                                                                         basePort + MGMT_OFFSET,
                                                                         basePort + APP_HTTP_OFFSET,
                                                                         "mir"),
                                                START_BOUND);
        awaitCondition("leader", 90_000L, () -> cluster.getLeaderManagementPort().isPresent());
        var voterIds = cluster.allNodes().stream().map(n -> n.self().id()).toList();
        var leaderId = cluster.currentLeader().unwrap();
        var leader = cluster.getNode(leaderId).unwrap();
        var observerId = cluster.addNode().await(START_BOUND).unwrap().id();

        awaitCondition(() -> "the observer " + observerId + " is counted by the leader's membership view; count=" + reportedCount(leader),
                       60_000L,
                       () -> reportedCount(leader) == CLUSTER_SIZE + 1);
        var killed = new java.util.ArrayList<String>();

        killed.add(leaderId);
        voterIds.stream().filter(id -> !id.equals(leaderId)).limit(2).forEach(killed::add);
        killed.forEach(id -> cluster.killNode(id, false).await(STOP_BOUND));
        var survivors = List.copyOf(cluster.allNodes());
        var survivingVoters = survivors.stream().filter(n -> voterIds.contains(n.self().id())).toList();

        assertThat(survivors.stream().map(n -> n.self().id()).toList())
            .as("precondition: the observer is still alive beside the two surviving voters")
            .contains(observerId);
        assertThat(survivingVoters).as("two voters survive").hasSize(2);
        awaitCondition(() -> "both surviving voters report no quorum; states=" + survivingVoters.stream().map(n -> n.self().id() + "=" + inQuorum(n)).toList(),
                       90_000L,
                       () -> survivingVoters.stream().noneMatch(EmberObserverQuorumLossWiringTest::inQuorum));
        var key = AetherKey.StreamPartitionOwnershipKey.streamPartitionOwnershipKey("mir", 0);
        var committed = survivingVoters.getFirst().<Object>apply(List.of(new KVCommand.Noop<AetherKey>(key))).await(TimeSpan.timeSpan(20).seconds());

        assertThat(committed.isSuccess()).as("2 voters of 5 plus an observer must not commit; " + committed).isFalse();
    }

    private static int reportedCount(AetherNode node) {
        return node.topologyManager() instanceof TopologyObserver observer
               ? observer.reportedActiveNodeCount()
               : -1;
    }

    private static boolean inQuorum(AetherNode node) {
        return node.topologyManager() instanceof TopologyObserver observer && observer.inQuorum().getAsBoolean();
    }

    private static boolean hasWarning(AetherNode node, String code, String subject) {
        return events(node).stream()
                           .anyMatch(e -> e instanceof ClusterEvent.OperatorWarning && code.equals(e.details().get("code"))
                                          && subject.equals(e.details().get("subject")));
    }

    private static long countWarnings(AetherNode node, String code, String subject) {
        return events(node).stream()
                           .filter(e -> e instanceof ClusterEvent.OperatorWarning && code.equals(e.details().get("code"))
                                        && subject.equals(e.details().get("subject")))
                           .count();
    }

    private static List<String> quorumLossObservers(AetherNode node) {
        return events(node).stream()
                           .filter(e -> e instanceof ClusterEvent.QuorumLost)
                           .filter(e -> e.severity() == ClusterEvent.Severity.CRITICAL)
                           .map(e -> e.details().get("observedBy"))
                           .toList();
    }

    private static List<String> codesSeenBy(AetherNode node) {
        return events(node).stream().map(e -> e.getClass().getSimpleName() + "/" + e.details().get("code")).toList();
    }

    private static List<ClusterEvent> events(AetherNode node) {
        return node.eventAggregator().events().await().or(List.of());
    }

    private static void awaitCondition(String what, long budgetMs, BooleanSupplier condition) {
        awaitCondition(() -> what, budgetMs, condition);
    }

    private static void awaitCondition(Supplier<String> what, long budgetMs, BooleanSupplier condition) {
        var deadline = System.currentTimeMillis() + budgetMs;

        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleepQuietly(500L);
        }
        throw new AssertionError("timed out: " + what.get());
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
