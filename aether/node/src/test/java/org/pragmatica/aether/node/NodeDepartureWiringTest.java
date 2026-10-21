// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1835 wiring pins for the confirmed-death edge in `AetherNode`'s assembly, which no unit test constructs.
/// Following `OperatorWarningWiringTest`, these read the assembly source with comments stripped and whitespace
/// removed, so they pin that the wiring is PRESENT and has the intended shape.
///
/// Two properties, which the FSM-level and notifier-level tests cannot express because the lambda lives here:
/// the unconditional heal/link-drop/quorum-nudge callback stays UNGATED, and the NODE_FAILED + CRITICAL pair is
/// reached only through the reachability-split listeners, never from the unconditional callback.
class NodeDepartureWiringTest {
    @Test
    void membershipDeath_staysUnconditional_andCarriesNothingElse() {
        assertThat(assemblyCode()).containsOnlyOnce("membershipFsm.onConfirmedDeparture(departed->{onMembershipDeath(departed,dropDeadPeerLink,quorumLossDetectorRef,leaderReconcilerRef);});");
    }

    @Test
    void failedPair_rideOnlyTheReachableDeathEdge_neverJoinedHasItsOwn() {
        var code = assemblyCode();

        assertThat(code).containsOnlyOnce("membershipFsm.onReachableDeath(departureNotifier::onConfirmedDeparture);");
        assertThat(code).containsOnlyOnce("membershipFsm.onNeverReachableDeath(departureNotifier::onNeverJoined);");
        assertThat(code).doesNotContain("departureNotifier.onConfirmedDeparture(departed)");
    }

    /// #1720: a floor-refused operator drain target that dies closes its refusal ON THE TRANSITION. The hook rides the
    /// single `onTransition` listener (the DEAD-edge listener above is pinned to carry nothing else) and reaches the
    /// management server only for the DEAD state.
    @Test
    void sliceFloorRefusal_isClosedFromTheTransitionListener_forTheDeadStateOnly() {
        var code = assemblyCode();

        assertThat(code).containsOnlyOnce("closeSliceFloorRefusalOnDeath(managementServerRef,record);");
        assertThat(code).containsOnlyOnce("if(\"Dead\".equals(record.toState())){managementServerRef.get().onPresent(server->server.onMemberDeparted(record.nodeId()));}");
    }

    /// The assembly source (`AetherNode.java` plus `node/assembly/*.java`) with line comments removed and all whitespace
    /// stripped; each pin must match exactly once across that set. See [AssemblySource].
    static String assemblyCode() {
        return AssemblySource.stripped();
    }
}
