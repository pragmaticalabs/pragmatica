// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.view.MembershipView;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.swim.SwimHealth;

import java.net.SocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests for the A2 (`GET /api/nodes/live`) join logic — `StatusRoutes.toLiveNodeEntry`.
/// Exercises the three load-bearing semantics from the harness-resilience spec §A2:
///   - a healthy node present in both SWIM and topology surfaces `swimAlive=true` + its address,
///   - a node's `role` is read from its `role` label, defaulting to CORE when unlabeled,
///   - a zombie (present only in a stale reported-state map, absent from topology and SWIM)
///     surfaces `swimAlive=false` and `address=null`.
class StatusRoutesLiveNodesTest {

    private static NodeId nodeId(String id) {
        return NodeId.nodeId(id).unwrap();
    }

    private static NodeAddress address(String host, int port) {
        return NodeAddress.nodeAddress(host, port).unwrap();
    }

    @Test
    void toLiveNodeEntry_presentNode_reportsAddressRoleAndAlive() {
        var id = nodeId("node-1");
        var info = NodeInfo.nodeInfo(id, address("10.0.0.7", 7100), Map.of(NodeInfo.LABEL_ROLE, "WORKER"));
        var topology = TestTopologyManager.with(Map.of(id, info));
        var view = TestMembershipView.presenting(id);

        var entry = StatusRoutes.toLiveNodeEntry(view, topology, id, "READY");

        assertThat(entry.nodeId()).isEqualTo("node-1");
        assertThat(entry.address().or("")).isEqualTo("10.0.0.7:7100");
        assertThat(entry.role()).isEqualTo("WORKER");
        assertThat(entry.swimAlive()).isTrue();
        assertThat(entry.reportedState()).isEqualTo("READY");
    }

    @Test
    void toLiveNodeEntry_unlabeledNode_defaultsRoleToCore() {
        var id = nodeId("node-2");
        var info = NodeInfo.nodeInfo(id, address("10.0.0.8", 7100));
        var topology = TestTopologyManager.with(Map.of(id, info));
        var view = TestMembershipView.presenting(id);

        var entry = StatusRoutes.toLiveNodeEntry(view, topology, id, "READY");

        assertThat(entry.role()).isEqualTo("CORE");
        assertThat(entry.swimAlive()).isTrue();
    }

    @Test
    void toLiveNodeEntry_zombie_absentFromTopologyAndSwim_hasNullAddressAndNotAlive() {
        var id = nodeId("node-3");
        var topology = TestTopologyManager.with(Map.<NodeId, NodeInfo>of());
        var view = TestMembershipView.presenting();

        var entry = StatusRoutes.toLiveNodeEntry(view, topology, id, "READY");

        assertThat(entry.nodeId()).isEqualTo("node-3");
        assertThat(entry.address().isEmpty()).isTrue();
        assertThat(entry.role()).isEqualTo("CORE");
        assertThat(entry.swimAlive()).isFalse();
        assertThat(entry.reportedState()).isEqualTo("READY");
    }

    @Test
    void toLiveNodeEntry_inTopologyButNotInSwim_reportsAddressButNotAlive() {
        var id = nodeId("node-4");
        var info = NodeInfo.nodeInfo(id, address("10.0.0.9", 7100));
        var topology = TestTopologyManager.with(Map.of(id, info));
        var view = TestMembershipView.presenting();

        var entry = StatusRoutes.toLiveNodeEntry(view, topology, id, "SYNCING");

        assertThat(entry.address().or("")).isEqualTo("10.0.0.9:7100");
        assertThat(entry.swimAlive()).isFalse();
        assertThat(entry.reportedState()).isEqualTo("SYNCING");
    }

    /// Map-backed `MembershipView` stub: a node is present iff it is in `present`.
    record TestMembershipView(Set<NodeId> present) implements MembershipView {
        static TestMembershipView presenting(NodeId... ids) {
            return new TestMembershipView(Set.of(ids));
        }

        @Override
        public Map<NodeId, MemberView> snapshot() {
            return present.stream().collect(Collectors.toMap(id -> id,
                                                             id -> new MemberView(id, SwimHealth.HEALTHY)));
        }

        @Override
        public Option<MemberView> get(NodeId peer) {
            return present.contains(peer)
                   ? Option.some(new MemberView(peer, SwimHealth.HEALTHY))
                   : Option.none();
        }
    }

    /// Map-backed `TopologyManager` stub: `get` resolves from `infos`; the remaining abstract
    /// methods are inert (never reached by `toLiveNodeEntry`).
    record TestTopologyManager(Map<NodeId, NodeInfo> infos) implements TopologyManager {
        static TestTopologyManager with(Map<NodeId, NodeInfo> infos) {
            return new TestTopologyManager(Map.copyOf(infos));
        }

        @Override
        public Option<NodeInfo> get(NodeId id) {
            return Option.option(infos.get(id));
        }

        @Override
        public NodeInfo self() {
            return null;
        }

        @Override
        public int clusterSize() {
            return infos.size();
        }

        @Override
        public Option<NodeId> reverseLookup(SocketAddress socketAddress) {
            return Option.none();
        }

        @Override
        public Promise<Unit> start() {
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.success(Unit.unit());
        }

        @Override
        public TimeSpan pingInterval() {
            return TimeSpan.timeSpan(1).seconds();
        }

        @Override
        public TimeSpan helloTimeout() {
            return TimeSpan.timeSpan(5).seconds();
        }

        @Override
        public Option<NodeState> getState(NodeId id) {
            return Option.none();
        }

        @Override
        public List<NodeId> topology() {
            return List.copyOf(infos.keySet());
        }
    }
}
