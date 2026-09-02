// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.deployment.membership.ntt.QuorumLossSnapshot;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// `StatusRoutes.quorumStatus` is the single derivation behind `/api/status` (`cluster.quorate`),
/// `/api/health` (`quorum`) and `/health/ready` (the `quorum` component). Before the fix both the
/// health and readiness sites computed `hasQuorum = connectedCount + 1 >= 2` ("connected to at
/// least one peer") — so a 2-of-5 minority node falsely reported quorum UP. It must now derive from
/// the consensus simple-majority threshold: minority reports DOWN, majority UP, single-node UP.
class StatusRoutesQuorumTest {
    private static final QuorumLossSnapshot NO_SNAPSHOT = null;
    private static final MembershipFsm NO_FSM = null;
    private static final List<NodeId> NO_TOPOLOGY = null;
    /// #557: `fallbackQuorumStatus` reads `node.self()` so it can count self toward the observed
    /// quorum numerator (self is reachable by definition and never observes itself). The proxy's
    /// default branch throws, so an unstubbed `self` fails every fallback case at RUNTIME while
    /// still compiling — which is why `test-compile` alone cannot vouch for this file.
    private static final NodeId PROXY_SELF = new NodeId("proxy-self");

    private static ManageableNode nodeWith(Option<QuorumLossSnapshot> snapshot, MembershipFsm fsm, List<NodeId> topology) {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, _) -> stubbed(method.getName(), snapshot, fsm, topology));
    }

    private static Object stubbed(String method,
                                  Option<QuorumLossSnapshot> snapshot,
                                  MembershipFsm fsm,
                                  List<NodeId> topology) {
        return switch (method) {
            case "quorumLossSnapshot" -> snapshot;
            case "membershipFsm" -> fsm;
            case "initialTopology" -> topology;
            case "self" -> PROXY_SELF;
            default -> throw new UnsupportedOperationException("Not stubbed in test proxy: " + method);
        };
    }

    private static QuorumLossSnapshot snapshot(int strictMembers, int requiredThreshold, boolean belowThreshold) {
        return new QuorumLossSnapshot(strictMembers, requiredThreshold, belowThreshold, false);
    }

    private static MembershipFsm fsmWithCountedMembers(int count) {
        var fsm = MembershipFsm.membershipFsm();

        for (int i = 0; i < count; i++) {
            fsm.onSwimHealthy(new NodeId("counted-" + i), 1L);
        }

        return fsm;
    }

    private static List<NodeId> coreTopology(int size) {
        var ids = new ArrayList<NodeId>();

        for (int i = 0; i < size; i++) {
            ids.add(new NodeId("core-" + i));
        }

        return ids;
    }

    @Nested
    class FromConsensusSnapshot {
        @Test
        void quorumStatus_minorityBelowThreshold_reportsNotHeld() {
            var node = nodeWith(Option.some(snapshot(2, 3, true)), NO_FSM, NO_TOPOLOGY);

            assertThat(StatusRoutes.quorumStatus(node).held())
                .as("a 2-of-5 minority (strict 2 < required 3) must report NO quorum")
                .isFalse();
        }

        @Test
        void quorumStatus_majorityAtThreshold_reportsHeld() {
            var node = nodeWith(Option.some(snapshot(3, 3, false)), NO_FSM, NO_TOPOLOGY);

            assertThat(StatusRoutes.quorumStatus(node).held())
                .as("a 3-of-5 majority (strict 3 >= required 3) must report quorum")
                .isTrue();
        }

        @Test
        void quorumStatus_singleNodeCluster_reportsHeld() {
            var node = nodeWith(Option.some(snapshot(1, 1, false)), NO_FSM, NO_TOPOLOGY);

            assertThat(StatusRoutes.quorumStatus(node).held())
                .as("a single-node cluster (core=1) is trivially quorate")
                .isTrue();
        }

        @Test
        void quorumStatus_detail_reportsCountedAndRequired() {
            var node = nodeWith(Option.some(snapshot(5, 3, false)), NO_FSM, NO_TOPOLOGY);

            assertThat(StatusRoutes.quorumStatus(node).detail())
                .isEqualTo("Reachable core members: 5 / required: 3");
        }
    }

    @Nested
    class FallbackWhenSnapshotAbsent {
        @Test
        void quorumStatus_noSnapshotCountedCoreMajority_reportsHeld() {
            var node = nodeWith(Option.none(), fsmWithCountedMembers(2), coreTopology(3));

            assertThat(StatusRoutes.quorumStatus(node).held())
                .as("cold-start fallback: 2 counted core of configured 3 (required 2) is quorate")
                .isTrue();
        }

        @Test
        void quorumStatus_noSnapshotCountedCoreMinority_reportsNotHeld() {
            var node = nodeWith(Option.none(), fsmWithCountedMembers(1), coreTopology(3));

            assertThat(StatusRoutes.quorumStatus(node).held())
                .as("cold-start fallback: 1 counted core of configured 3 (required 2) is below quorum")
                .isFalse();
        }
    }
}
