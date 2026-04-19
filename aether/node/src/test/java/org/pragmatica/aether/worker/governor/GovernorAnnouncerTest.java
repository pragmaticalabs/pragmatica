// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.governor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.swim.SwimMember;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;


/// Verifies governor election → GovernorAnnouncementKey write flow.
/// See `aether/docs/specs/cluster-generation-spec.md` §5 / §6.
class GovernorAnnouncerTest {
    private static final NodeId SELF = NodeId.nodeId("worker-5").unwrap();
    private static final NodeId PEER_A = NodeId.nodeId("worker-2").unwrap();  // lower than SELF
    private static final NodeId PEER_B = NodeId.nodeId("worker-8").unwrap();  // higher than SELF

    private RecordingClusterNode cluster;
    private GovernorAnnouncer announcer;

    @BeforeEach
    void setUp() {
        cluster = new RecordingClusterNode();
        var hlcClock = HlcClock.hlcClock(SELF.id()).unwrap();
        announcer = GovernorAnnouncer.governorAnnouncer(SELF,
                                                         cluster,
                                                         hlcClock,
                                                         () -> "pool-a",
                                                         () -> "host:9000",
                                                         () -> Epoch.ZERO);
        announcer.start();
    }

    @Test
    void onMembershipChange_selfElectedFirstTime_writesGovernorChange() {
        // SELF and PEER_B only (both higher than SELF would dominate; PEER_B > SELF so SELF wins)
        announcer.onMembershipChange(List.of(alive(SELF), alive(PEER_B)));

        assertThat(announcer.isGovernor()).isTrue();
        assertThat(cluster.batches).hasSize(1);
        var command = cluster.batches.getFirst().getFirst();
        assertThat(command).isInstanceOf(KVCommand.Put.class);
        var put = (KVCommand.Put<?, ?>) command;
        assertThat(put.key()).isInstanceOf(GovernorAnnouncementKey.class);
        var value = (GovernorAnnouncementValue) put.value();
        assertThat(value.governorId()).isEqualTo(SELF);
        assertThat(value.members()).containsExactlyInAnyOrder(SELF, PEER_B);
        assertThat(value.communityTerm()).isEqualTo(1L);
        assertThat(value.dissolved()).isFalse();
    }

    @Test
    void onMembershipChange_peerIsLowerId_selfBecomesFollower_noWrite() {
        announcer.onMembershipChange(List.of(alive(PEER_A), alive(PEER_B), alive(SELF)));

        assertThat(announcer.isGovernor()).isFalse();
        assertThat(announcer.currentGovernor().stream().toList()).containsExactly(PEER_A);
        assertThat(cluster.batches).isEmpty();
    }

    @Test
    void onMembershipChange_selfGovernor_thenNewLowerPeerJoins_stickyIncumbent() {
        announcer.onMembershipChange(List.of(alive(SELF), alive(PEER_B)));
        assertThat(announcer.isGovernor()).isTrue();
        var initialBatches = cluster.batches.size();

        // PEER_A has lower id but SELF is incumbent and still alive
        announcer.onMembershipChange(List.of(alive(SELF), alive(PEER_A), alive(PEER_B)));

        assertThat(announcer.isGovernor()).isTrue();
        // No additional governor-change write because incumbent holds
        assertThat(cluster.batches).hasSize(initialBatches);
    }

    @Test
    void onMembershipChange_whenNotStarted_noWrites() {
        announcer.stop();

        announcer.onMembershipChange(List.of(alive(SELF)));

        assertThat(cluster.batches).isEmpty();
    }

    private static SwimMember alive(NodeId id) {
        return SwimMember.swimMember(id, SwimMember.MemberState.ALIVE, 0, new InetSocketAddress("127.0.0.1", 0));
    }

    private static final class RecordingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        final List<List<KVCommand<AetherKey>>> batches = new ArrayList<>();

        @Override public NodeId self() {return SELF;}

        @Override public TopologyManager topologyManager() {
            throw new UnsupportedOperationException("not used");
        }

        @Override public Promise<Unit> start() {return Promise.success(Unit.unit());}
        @Override public Promise<Unit> stop() {return Promise.success(Unit.unit());}

        @SuppressWarnings({"unchecked", "rawtypes"})
        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            batches.add(List.copyOf(commands));
            return (Promise) Promise.success(List.of());
        }
    }
}
