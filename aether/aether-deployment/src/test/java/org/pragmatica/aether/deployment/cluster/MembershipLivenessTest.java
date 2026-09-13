// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.Set;

import org.junit.jupiter.api.Test;

import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;

/// #1050 / #1062 — the liveness predicates, pinned term by term and deterministically (no scheduler, no timing).
/// `live` is keyed on liveness EVIDENCE (raw SWIM, or the leader's transport), never on membership projection
/// (verify-1058). `demonstrablyLive` adds a counted membership. `replayProtected` adds tracked and in-flight.
class MembershipLivenessTest {
    private static final NodeId TARGET = nodeId("node-d").unwrap();

    private static MembershipLiveness liveness(Set<NodeId> counted,
                                               Set<NodeId> tracked,
                                               Set<NodeId> swimAlive,
                                               Set<NodeId> transport,
                                               Set<NodeId> inFlight) {
        return MembershipLiveness.membershipLiveness(() -> counted,
                                                     () -> tracked,
                                                     swimAlive::contains,
                                                     transport::contains,
                                                     () -> inFlight,
                                                     () -> 5);
    }

    /// A DEPARTING target (not counted) that raw SWIM still sees alive is live.
    @Test
    void live_departingButSwimAlive_isLive() {
        assertThat(liveness(Set.of(), Set.of(TARGET), Set.of(TARGET), Set.of(), Set.of()).live(TARGET)).isTrue();
    }

    /// A DEPARTING target (not counted) that the leader's transport still reaches is live.
    @Test
    void live_departingButTransportConnected_isLive() {
        assertThat(liveness(Set.of(), Set.of(TARGET), Set.of(), Set.of(TARGET), Set.of()).live(TARGET)).isTrue();
    }

    /// Counted membership alone is NOT liveness evidence: SWIM-dead and disconnected means not live.
    @Test
    void live_countedButSwimDeadAndDisconnected_isNotLive() {
        assertThat(liveness(Set.of(TARGET), Set.of(TARGET), Set.of(), Set.of(), Set.of()).live(TARGET)).isFalse();
    }

    @Test
    void demonstrablyLive_countedOnly_isLive() {
        assertThat(liveness(Set.of(TARGET), Set.of(), Set.of(), Set.of(), Set.of()).demonstrablyLive(TARGET)).isTrue();
    }

    @Test
    void demonstrablyLive_noEvidence_isNotLive() {
        assertThat(liveness(Set.of(), Set.of(TARGET), Set.of(), Set.of(), Set.of(TARGET)).demonstrablyLive(TARGET)).isFalse();
    }

    @Test
    void replayProtected_trackedOnly_isProtected() {
        assertThat(liveness(Set.of(), Set.of(TARGET), Set.of(), Set.of(), Set.of()).replayProtected(TARGET)).isTrue();
    }

    @Test
    void replayProtected_inFlightOnly_isProtected() {
        assertThat(liveness(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(TARGET)).replayProtected(TARGET)).isTrue();
    }

    @Test
    void replayProtected_noEvidence_isUnprotected() {
        assertThat(liveness(Set.of(), Set.of(), Set.of(), Set.of(), Set.of()).replayProtected(TARGET)).isFalse();
    }

    /// SF-1: protected by raw SWIM life and nothing else — the shape the replay parks.
    @Test
    void swimOnlyProtected_swimAliveAlone_isTrue() {
        assertThat(liveness(Set.of(), Set.of(), Set.of(TARGET), Set.of(), Set.of()).swimOnlyProtected(TARGET)).isTrue();
    }

    /// Any second protection — tracked, counted, in flight, or the link up — is not SWIM-only; nor is no SWIM life.
    @Test
    void swimOnlyProtected_anyOtherProtection_orNoSwimLife_isFalse() {
        assertThat(liveness(Set.of(), Set.of(TARGET), Set.of(TARGET), Set.of(), Set.of()).swimOnlyProtected(TARGET)).as("tracked").isFalse();
        assertThat(liveness(Set.of(TARGET), Set.of(), Set.of(TARGET), Set.of(), Set.of()).swimOnlyProtected(TARGET)).as("counted").isFalse();
        assertThat(liveness(Set.of(), Set.of(), Set.of(TARGET), Set.of(), Set.of(TARGET)).swimOnlyProtected(TARGET)).as("in flight").isFalse();
        assertThat(liveness(Set.of(), Set.of(), Set.of(TARGET), Set.of(TARGET), Set.of()).swimOnlyProtected(TARGET)).as("link up").isFalse();
        assertThat(liveness(Set.of(), Set.of(), Set.of(), Set.of(), Set.of()).swimOnlyProtected(TARGET)).as("no SWIM life").isFalse();
    }
}
