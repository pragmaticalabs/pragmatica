// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.health;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;


/// Tripwire pair for the dial-policy half of M-02 (cluster T assessment, rc4 `7c786db0d3`): `ClusterNetwork.setConnectionInitiator`
/// requires "one deterministic initiator for each peer pair ... both endpoints must use the same immutable-role rule; health must
/// not change the selected initiator". The policy `AetherNode` installs (`AetherNode.java:5375`) is
/// `initiatesCoreBootstrap(bootstrapInitiator(...), transferPeer) || isConnectionInitiator(peer, ...)`, and both halves answer
/// "yes" at BOTH ends of a pair: an application dependency makes the dependent end the initiator regardless of the other end
/// ("directed edges: the destination need not request a reverse link"), and two bootstrapping cores that list each other as
/// transfer peers both initiate. The role facts it reads are local mutable views (`routingCoreIds`, `memberDescriptor`,
/// `workerEndpointDirectory`).
///
/// Level: the policy record itself (a pure object), because the lambda in `AetherNode` is only a composition of these two
/// calls over local state; the full lambda is reachable only through a started node (see `DualDialTripwireTest` in the
/// consensus module for the transport consequence). The wiring itself is not exercised here (`[unverified]`).
class HierarchyPeerPolicyDualInitiatorTripwireTest {
    private static final NodeId A = new NodeId("core-a");
    private static final NodeId B = new NodeId("core-b");

    private final HierarchyPeerPolicy atA = HierarchyPeerPolicy.hierarchyPeerPolicy(A, false);
    private final HierarchyPeerPolicy atB = HierarchyPeerPolicy.hierarchyPeerPolicy(B, false);

    private boolean bothEndsInitiateOverAnApplicationDependency() {
        return atA.isConnectionInitiator(B, true, false, true) && atB.isConnectionInitiator(A, true, false, true);
    }

    private boolean bothEndsInitiateWhileBootstrappingTowardEachOther() {
        return atA.initiatesCoreBootstrap(true, true) && atB.initiatesCoreBootstrap(true, true);
    }

    @Test
    void connectionInitiator_isTrueAtBothEnds_whenEachEndHasAnApplicationDependencyOnTheOther() {
        assertThat(bothEndsInitiateOverAnApplicationDependency())
            .as("TRIPWIRE M-02: both ends of one pair are told to initiate when each has an application dependency on the "
                + "other; the installed rule is now single-initiator per pair. Delete this tripwire and enable "
                + "connectionInitiator_isTrueAtMostAtOneEnd_overApplicationDependencies.")
            .isTrue();
    }

    @Test
    void connectionInitiator_isTrueAtBothEnds_whenBothCoresBootstrapTowardEachOther() {
        assertThat(bothEndsInitiateWhileBootstrappingTowardEachOther())
            .as("TRIPWIRE M-02: two bootstrapping cores that list each other as transfer peers both initiate; the rule is "
                + "now single-initiator. Delete this tripwire and enable "
                + "connectionInitiator_isTrueAtMostAtOneEnd_whenBothCoresBootstrap.")
            .isTrue();
    }

    @Disabled("M-02: enable when the installed initiator rule is a pure function of the pair (single initiator per pair), "
              + "or delete the pair if the overhaul keeps dual dial and arbitrates it. Today red: both ends initiate.")
    @Test
    void connectionInitiator_isTrueAtMostAtOneEnd_overApplicationDependencies() {
        assertThat(bothEndsInitiateOverAnApplicationDependency()).as("both ends of the pair initiate").isFalse();
    }

    @Disabled("M-02: enable with the initiator rule fix (see the application-dependency twin). Today red: both ends initiate.")
    @Test
    void connectionInitiator_isTrueAtMostAtOneEnd_whenBothCoresBootstrap() {
        assertThat(bothEndsInitiateWhileBootstrappingTowardEachOther()).as("both ends of the pair initiate").isFalse();
    }
}
