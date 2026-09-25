// SPDX-License-Identifier: BUSL-1.1
package org.pragmatica.aether.node.health;

import java.util.HashMap;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import static org.assertj.core.api.Assertions.assertThat;

class HierarchyPeerPolicyTest {
    private static final Set<NodeId> CORES = ids("core-", 7);

    @Test
    void tenThousandWorkersHaveTwoDistributedUplinksNotFullCoreMesh() {
        var counts = new HashMap<NodeId, Integer>();
        for (int index = 0; index < 10_000; index++) {
            var self = new NodeId("worker-" + index);
            var selected = HierarchyPeerPolicy.coreUplinks(self, CORES);
            assertThat(selected).hasSize(2);
            assertThat(selected).isEqualTo(HierarchyPeerPolicy.coreUplinks(self, CORES));
            selected.forEach(core -> counts.merge(core, 1, Integer::sum));
        }
        assertThat(counts).hasSize(7);
        assertThat(counts.values()).allMatch(count -> count > 2_500 && count < 3_300);
    }

    @Test
    void workerAndGovernorUseDifferentDirectAudiencesAndFailOverUplinks() {
        var self = new NodeId("worker-self");
        var governor = new NodeId("governor");
        var members = ids("member-", 100);
        var policy = HierarchyPeerPolicy.hierarchyPeerPolicy(self, true);
        policy.refresh(CORES, Set.of(governor), members, Option.some(governor), Option.none(), CORES);
        assertThat(policy.current().get().directPeers()).hasSize(3).contains(governor);
        assertThat(policy.current().get().swimPeers()).hasSize(108);
        assertThat(policy.forwardsPeerMetrics()).isFalse();
        var surviving = Set.of(new NodeId("core-6"));
        policy.refresh(CORES, Set.of(governor), members, Option.some(governor), Option.none(), surviving);
        assertThat(policy.current().get().directPeers()).containsExactlyInAnyOrder(governor, new NodeId("core-6"));
        policy.refresh(CORES, Set.of(self), members, Option.some(self), Option.none(), CORES);
        assertThat(policy.current().get().directPeers()).containsAll(members);
        assertThat(policy.forwardsPeerMetrics()).isTrue();
    }

    @Test
    void coreObservesGovernorsRatherThanEveryWorker() {
        var self = new NodeId("core-0");
        var governors = ids("governor-", 100);
        var policy = HierarchyPeerPolicy.hierarchyPeerPolicy(self, false);
        policy.refresh(CORES, governors, Set.of(), Option.none(), Option.none(), CORES);
        assertThat(policy.current().get().swimPeers()).hasSize(107).containsAll(governors);
        assertThat(policy.shouldObserve(new NodeId("other-worker"))).isFalse();
        assertThat(policy.shouldPing(self)).isFalse();
    }

    @Test
    void workerInitiatesCoreLinkRegardlessOfIdentityOrder() {
        for (var pair : java.util.List.of(java.util.List.of("a-core", "z-worker"),
                                         java.util.List.of("z-core", "a-worker"))) {
            var core = new NodeId(pair.get(0));
            var worker = new NodeId(pair.get(1));
            var corePolicy = HierarchyPeerPolicy.hierarchyPeerPolicy(core, false);
            var workerPolicy = HierarchyPeerPolicy.hierarchyPeerPolicy(worker, true);
            assertThat(workerPolicy.isConnectionInitiator(core, true, false, false)).isTrue();
            assertThat(corePolicy.isConnectionInitiator(worker, false, true, false)).isFalse();
        }
    }

    @Test
    void sameRoleAndUnknownPeersKeepDeterministicIdentityOrder() {
        var early = new NodeId("a");
        var late = new NodeId("z");
        for (boolean worker : java.util.List.of(false, true)) {
            var earlyPolicy = HierarchyPeerPolicy.hierarchyPeerPolicy(early, worker);
            var latePolicy = HierarchyPeerPolicy.hierarchyPeerPolicy(late, worker);
            assertThat(earlyPolicy.isConnectionInitiator(late, !worker, worker, false)).isTrue();
            assertThat(latePolicy.isConnectionInitiator(early, !worker, worker, false)).isFalse();
            assertThat(earlyPolicy.isConnectionInitiator(late, false, false, false)).isTrue();
            assertThat(latePolicy.isConnectionInitiator(early, false, false, false)).isFalse();
        }
    }

    @Test
    void higherIdentityForeignEndpointInitiatesWithoutExpandingObservationScope() {
        var self = new NodeId("z-caller");
        var endpoint = new NodeId("a-foreign-endpoint");
        var policy = HierarchyPeerPolicy.hierarchyPeerPolicy(self, true);
        policy.refresh(CORES, Set.of(), Set.of(self), Option.none(), Option.none(), CORES);
        assertThat(policy.isConnectionInitiator(endpoint, false, true, false)).isFalse();
        assertThat(policy.isConnectionInitiator(endpoint, false, true, true)).isTrue();
        assertThat(policy.shouldObserve(endpoint)).isFalse();
        assertThat(policy.shouldPing(endpoint)).isFalse();
        assertThat(policy.shouldConnect(endpoint)).isFalse();
    }

    @Test
    void reciprocalForeignDependenciesRequireTransportToSelectOneSharedConnection() {
        var early = new NodeId("a-endpoint");
        var late = new NodeId("z-endpoint");
        var earlyPolicy = HierarchyPeerPolicy.hierarchyPeerPolicy(early, true);
        var latePolicy = HierarchyPeerPolicy.hierarchyPeerPolicy(late, true);
        // Both sides have real outgoing work. Transport must resolve opposite-direction links
        // consistently; neither side can infer the remote dependency catalog here.
        assertThat(earlyPolicy.isConnectionInitiator(late, false, true, true)).isTrue();
        assertThat(latePolicy.isConnectionInitiator(early, false, true, true)).isTrue();
    }

    @Test
    void observedCommunityEndpointStillInitiatesWhenItIsNotAControlConnection() {
        var self = new NodeId("z-caller");
        var endpoint = new NodeId("a-community-endpoint");
        var governor = new NodeId("governor");
        var policy = HierarchyPeerPolicy.hierarchyPeerPolicy(self, true);
        policy.refresh(CORES, Set.of(governor), Set.of(self, endpoint, governor),
                       Option.some(governor), Option.none(), CORES);
        assertThat(policy.shouldObserve(endpoint)).isTrue();
        assertThat(policy.shouldConnect(endpoint)).isFalse();
        assertThat(policy.shouldPing(endpoint)).isFalse();
        assertThat(policy.isConnectionInitiator(endpoint, false, true, false)).isFalse();
        assertThat(policy.isConnectionInitiator(endpoint, false, true, true)).isTrue();
    }

    @Test
    void stagedHigherIdentityCoreInitiatesOnlyConfiguredBootstrapPeersWithoutGainingMembership() {
        var self = new NodeId("z-staged-core");
        var seed = new NodeId("a-core");
        var policy = HierarchyPeerPolicy.hierarchyPeerPolicy(self, false);
        policy.refresh(Set.of(seed), Set.of(), Set.of(), Option.none(), Option.none(), Set.of(seed));
        assertThat(policy.isConnectionInitiator(seed, true, false, false)).isFalse();
        assertThat(policy.initiatesCoreBootstrap(false, true)).isTrue();
        assertThat(policy.initiatesCoreBootstrap(false, false)).isFalse();
        assertThat(policy.initiatesCoreBootstrap(true, true)).isFalse();
        assertThat(policy.current().get().swimPeers()).containsExactlyInAnyOrder(seed, self);
        assertThat(HierarchyPeerPolicy.hierarchyPeerPolicy(self, true)
                                      .initiatesCoreBootstrap(false, true)).isFalse();
    }

    private static Set<NodeId> ids(String prefix, int size) {
        return IntStream.range(0, size).mapToObj(index -> new NodeId(prefix + index)).collect(Collectors.toSet());
    }
}
