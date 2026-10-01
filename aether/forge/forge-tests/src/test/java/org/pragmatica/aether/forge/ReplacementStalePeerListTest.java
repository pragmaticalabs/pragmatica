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
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// #1803: two cores die, and the replacements are minted with the peer list that existed when they were
/// MINTED — the surviving cores only, not the current leader. Both defects need this shape and the harness
/// default (every new node gets the full current list) hides both:
///
///  - (a) a replacement that is not told of the leader never dials it and the leader never dials it, so the
///    leader's membership stays below target;
///  - (b) the first replacement to join sorts first by id, falls through the KV-sync grace and deposes the
///    healthy leader.
///
/// The leader is NOT among the replacements' mint-time peers, one replacement sorts below the leader and one
/// above (the single-dialer order decides who initiates, so both directions are exercised), and the second is
/// minted without the first in its list, as the slower substitutes in the incident were. Auto-heal is blocked so
/// it cannot mint the replacements with a full list and mask the staleness.
///
/// Five cores, two killed: three survivors keep the quorum.
///
/// Heavy until it has a measured pass rate on the per-PR runner: it kills two of five cores and waits out the
/// failure detector (about a minute) before the replacements join.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
class ReplacementStalePeerListTest {
    private static final TimeSpan BUDGET = TimeSpan.timeSpan(180).seconds();
    private static final String LOW = "stale-peers-0-low";
    private static final String HIGH = "stale-peers-9-high";

    private final EmberCluster cluster = EmberCluster.emberCluster(5, 24600, 24700, 24800, "stale-peers");

    @AfterEach
    void stop() {
        LifecycleAwait.bestEffort("stop stale-peers cluster", cluster, cluster.stop());
    }

    @Test
    void replacementsMintedWithoutTheLeader_reachFullMembershipUnderTheSameLeader() {
        cluster.withComputeProviderDecorator(BlockedProvisioning::new);
        LifecycleAwait.settled("start stale-peers cluster", cluster, cluster.start());
        await().atMost(BUDGET.duration()).until(() -> committedLeader().isPresent());
        var leader = committedLeader().unwrap();
        var survivors = survivorsKeeping(leader);
        var victims = cluster.status()
                             .nodes()
                             .stream()
                             .map(EmberCluster.NodeStatus::id)
                             .filter(id -> !survivors.contains(id))
                             .toList();

        victims.forEach(victim -> LifecycleAwait.nodeSettled("kill " + victim, cluster, cluster.killNode(victim, false)));
        await().atMost(BUDGET.duration()).until(() -> victims.stream().noneMatch(counted(leader)::contains));
        var mintTimePeers = survivors.stream().filter(id -> !id.equals(leader)).collect(Collectors.toSet());

        LifecycleAwait.nodeSettled("add " + LOW, cluster, cluster.addCoreNode(LOW, mintTimePeers));
        LifecycleAwait.nodeSettled("add " + HIGH, cluster, cluster.addCoreNode(HIGH, mintTimePeers));

        await().atMost(BUDGET.duration())
               .untilAsserted(() -> assertThat(counted(leader)).as("leader %s must count both replacements and the survivors", leader)
                                                               .containsExactlyInAnyOrderElementsOf(expectedMembers(survivors)));
        assertThat(committedLeader()).as("no replacement may depose the live leader").isEqualTo(Option.some(leader));
        List.of(LOW, HIGH)
            .forEach(replacement -> assertThat(countedBy(replacement)).as("replacement %s must count the leader it was never told of", replacement)
                                                                      .contains(leader));
    }

    private static Set<String> expectedMembers(Set<String> survivors) {
        var members = new HashSet<>(survivors);

        members.add(LOW);
        members.add(HIGH);

        return members;
    }

    /// The leader plus the first two other cores; everything else is killed.
    private Set<String> survivorsKeeping(String leader) {
        var others = cluster.status()
                            .nodes()
                            .stream()
                            .map(EmberCluster.NodeStatus::id)
                            .filter(id -> !id.equals(leader))
                            .limit(2)
                            .toList();
        var survivors = new HashSet<>(others);

        survivors.add(leader);

        return survivors;
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

    /// The leader every live node's committed `LeaderKey` names, once one does.
    private Option<String> committedLeader() {
        return cluster.currentLeader()
                      .filter(id -> cluster.getNode(id).filter(ReplacementStalePeerListTest::committedAsLeader).isPresent());
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
