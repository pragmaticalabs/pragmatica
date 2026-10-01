// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.ProviderDefaults;
import org.pragmatica.aether.environment.ProvisionRequest;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// #1803: a core dies, and its replacement is minted with a peer list that does not name the current leader — the
/// peers that existed when it was MINTED, and the leader (in the incident a node that had itself joined after those
/// lists were frozen) was not among them. Both defects need this shape and the harness default (every new node gets
/// the full current list) hides both:
///
///  - (a) a replacement that is not told of the leader never dials it and the leader never dials it, so the
///    leader's membership stays below target;
///  - (b) a replacement that sorts first by id falls through the KV-sync grace and deposes the healthy leader.
///
/// The leader is NOT among the replacement's mint-time peers. Two cases, because the single-dialer order decides
/// who initiates: the replacement's id sorts below the leader's, and above it. Five cores, one stopped gracefully (a
/// drain, as in the incident), one replacement: the cluster returns to its configured size of five. The
/// replacement is listed THREE live voters (the survivors other than the leader); one listed fewer than a quorum of
/// the electorate could never finish its state sync and would stay inactive for a reason unrelated to this
/// ticket. Auto-heal is blocked so it cannot mint the replacement with a full list and mask the staleness.
///
/// Heavy until it has a measured pass rate on the per-PR runner: it waits out the cluster's startup cooldown and
/// the departure of the stopped core before the replacement joins.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
class ReplacementStalePeerListTest {
    private static final TimeSpan BUDGET = TimeSpan.timeSpan(180).seconds();
    private static final int CORES = 5;
    /// The leader reconciler does not act on a deficit until the cluster has been up this long (auto-heal startup
    /// cooldown, 15 s); killing inside it measures the cooldown, not the replacement path.
    private static final TimeSpan STARTUP_SETTLE = TimeSpan.timeSpan(20).seconds();
    private static final String BELOW_LEADER = "stale-peers-0-low";
    private static final String ABOVE_LEADER = "stale-peers-9-high";

    private final EmberCluster cluster = EmberCluster.emberCluster(CORES, 24600, 24700, 24800, "stale-peers");

    @AfterEach
    void stop() {
        LifecycleAwait.bestEffort("stop stale-peers cluster", cluster, cluster.stop());
    }

    @Test
    void replacementSortingBelowTheLeader_mintedWithoutIt_reachesFullMembershipUnderTheSameLeader() {
        replacementMintedWithoutTheLeader(BELOW_LEADER);
    }

    @Test
    void replacementSortingAboveTheLeader_mintedWithoutIt_reachesFullMembershipUnderTheSameLeader() {
        replacementMintedWithoutTheLeader(ABOVE_LEADER);
    }

    private void replacementMintedWithoutTheLeader(String replacement) {
        cluster.withComputeProviderDecorator(BlockedProvisioning::new);
        LifecycleAwait.settled("start stale-peers cluster", cluster, cluster.start());
        await().atMost(BUDGET.duration()).until(() -> committedLeader().isPresent());
        var leader = committedLeader().unwrap();
        var electionSequence = committedSequence(leader);

        await().atMost(BUDGET.duration()).until(() -> counted(leader).size() == CORES);
        settlePastStartupCooldown();
        var victim = nonLeaderCores(leader).getFirst();
        var survivors = new HashSet<>(allCores());

        survivors.remove(victim);
        LifecycleAwait.nodeSettled("stop " + victim, cluster, cluster.killNode(victim));
        await().atMost(BUDGET.duration()).until(() -> !counted(leader).contains(victim));
        var mintTimePeers = survivors.stream().filter(id -> !id.equals(leader)).collect(Collectors.toSet());

        // Not awaited here: a replacement that cannot reach the leader never finishes starting, and the assertions
        // below then say WHY (membership short, leader changed) instead of "start did not settle".
        var joined = cluster.addCoreNode(replacement, mintTimePeers);
        var expected = new HashSet<>(survivors);

        expected.add(replacement);
        await().atMost(BUDGET.duration())
               .untilAsserted(() -> assertThat(counted(leader)).as("leader %s must count the replacement and the survivors", leader)
                                                               .containsExactlyInAnyOrderElementsOf(expected));
        assertThat(committedLeader()).as("the replacement must not depose the live leader").isEqualTo(Option.some(leader));
        assertThat(committedSequence(leader)).as("no election took place at all: the committed LeaderKey sequence is unchanged")
                                             .isEqualTo(electionSequence);
        LifecycleAwait.nodeSettled("join " + replacement, cluster, joined);
        await().atMost(BUDGET.duration())
               .untilAsserted(() -> assertThat(memberStates(replacement)).as("replacement %s must hold the leader it was never told of as a live member",
                                                                             replacement)
                                                                         .containsEntry(new NodeId(leader), "Member"));
    }

    private Set<String> allCores() {
        return cluster.status()
                      .nodes()
                      .stream()
                      .map(EmberCluster.NodeStatus::id)
                      .collect(Collectors.toSet());
    }

    private List<String> nonLeaderCores(String leader) {
        return allCores().stream()
                         .filter(id -> !id.equals(leader))
                         .sorted()
                         .toList();
    }

    private static void settlePastStartupCooldown() {
        await().pollDelay(STARTUP_SETTLE.duration()).timeout(STARTUP_SETTLE.duration().plusSeconds(10)).until(() -> true);
    }

    private Set<String> counted(String viewer) {
        return countedBy(viewer);
    }

    private Set<String> countedBy(String viewer) {
        return cluster.getNode(viewer)
                      .map(node -> node.membershipFsm()
                                       .coreCountedMembers()
                                       .stream()
                                       .map(NodeId::id)
                                       .collect(Collectors.toSet()))
                      .or(Set.of());
    }

    /// The viewer's own membership FSM state per member. Not `coreCountedMembers`: that also requires the member's
    /// role label to read `core`, and the harness gives its initial nodes no role label (see
    /// `EmberCluster#addWorkerNode`), so a replacement never counts them whatever it knows of their health.
    private Map<NodeId, String> memberStates(String viewer) {
        return cluster.getNode(viewer)
                      .map(node -> node.membershipFsm().memberStates())
                      .or(Map.of());
    }

    /// The leader every live node's committed `LeaderKey` names, once one does.
    private Option<String> committedLeader() {
        return cluster.currentLeader()
                      .filter(id -> cluster.getNode(id).filter(ReplacementStalePeerListTest::committedAsLeader).isPresent());
    }

    private long committedSequence(String viewer) {
        return cluster.getNode(viewer)
                      .flatMap(node -> node.kvStore().getTyped(LeaderKey.INSTANCE, LeaderValue.class))
                      .map(LeaderValue::viewSequence)
                      .or(-1L);
    }

    private static boolean committedAsLeader(AetherNode node) {
        return node.kvStore()
                   .getTyped(LeaderKey.INSTANCE, LeaderValue.class)
                   .filter(value -> value.leader().equals(node.self()))
                   .isPresent();
    }

    /// Auto-heal must not mint the replacements itself (with a full, current peer list): that would mask the
    /// staleness this test exists to create.
    private static final class BlockedProvisioning implements ComputeProvider {
        private final ComputeProvider delegate;

        private BlockedProvisioning(ComputeProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public ProviderDefaults providerDefaults() {
            return delegate.providerDefaults();
        }

        @Override
        public Promise<InstanceInfo> createFrom(ProvisionRequest request) {
            return EnvironmentError.capacityUnavailable("", new RuntimeException("provisioning blocked by the test (#1803)")).promise();
        }

        @Override
        public Promise<Unit> terminate(InstanceId instanceId) {
            return delegate.terminate(instanceId);
        }

        @Override
        public Promise<List<InstanceInfo>> listInstances() {
            return delegate.listInstances();
        }

        @Override
        public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {
            return delegate.instanceStatus(instanceId);
        }
    }
}
