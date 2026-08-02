// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;

/// #568 — committed stream ownership is only authoritative while its holder is ALIVE.
///
/// Committed ownership has no relinquish path other than the owner releasing it, so a record whose holder
/// died outlives it indefinitely. Observed live: a CTM replacement HRW-ranked itself owner of a stream with
/// existing history, but `isSelfOwner` reads COMMITTED ownership and answered false, so `promoteOwner` was
/// never taken; the flow fell through to `waitThenPromote`, which suppressed self-promotion precisely
/// BECAUSE a committed owner existed. Every step correct, composed into a permanent wedge — the owner sat
/// SYNCING at `-1` for 13+ minutes, looping every 20s, while four replicas held the data CAUGHT_UP.
class CommittedOwnerLivenessTest {
    private static final NodeId OWNER = new NodeId("node-owner");
    private static final NodeId OTHER = new NodeId("node-other");

    /// THE regression. A confirmed-DEAD holder must not keep the partition hostage.
    @Test
    void committedOwnerStillAlive_ownerDeparted_isNotAlive() {
        var fsm = MembershipFsm.membershipFsm();

        fsm.onSwimHealthy(OWNER, 1L);
        fsm.onSwimHealthy(OTHER, 1L);
        fsm.onSwimDeparted(OWNER, 2L);

        assertThat(fsm.countedMembers()).doesNotContain(OWNER);
        assertThat(AetherNode.committedOwnerStillAlive(fsm, OWNER)).isFalse();
    }

    @Test
    void committedOwnerStillAlive_ownerIsLiveMember_isAlive() {
        var fsm = MembershipFsm.membershipFsm();

        fsm.onSwimHealthy(OWNER, 1L);

        assertThat(AetherNode.committedOwnerStillAlive(fsm, OWNER)).isTrue();
    }

    /// Ownership must NOT flap on a transient link wobble — `countedMembers` is MEMBER + SUSPECT precisely
    /// so a suspected owner keeps its record. Only confirmed death releases it.
    @Test
    void committedOwnerStillAlive_ownerSuspected_isStillAlive() {
        var fsm = MembershipFsm.membershipFsm();

        fsm.onSwimHealthy(OWNER, 1L);
        fsm.onSwimSuspect(OWNER, 2L);

        assertThat(fsm.countedMembers()).contains(OWNER);
        assertThat(AetherNode.committedOwnerStillAlive(fsm, OWNER)).isTrue();
    }

    /// The boot-window guard, and the reason it exists. Before the FSM has any members, an unguarded
    /// liveness filter would reject EVERY committed owner and reintroduce the #491 F4 self-promote the
    /// committed-owner gate exists to prevent. Empty membership means "cannot judge liveness", not
    /// "nobody is alive".
    @Test
    void committedOwnerStillAlive_emptyMembership_defaultsToAlive() {
        var fsm = MembershipFsm.membershipFsm();

        assertThat(fsm.countedMembers()).isEmpty();
        assertThat(AetherNode.committedOwnerStillAlive(fsm, OWNER)).isTrue();
    }

    /// An owner absent from a NON-empty membership is dead or never seen — either way its record must not
    /// gate anything. This is the case the boot guard must not accidentally swallow.
    @Test
    void committedOwnerStillAlive_unknownOwnerWithLiveMembership_isNotAlive() {
        var fsm = MembershipFsm.membershipFsm();

        fsm.onSwimHealthy(OTHER, 1L);

        assertThat(AetherNode.committedOwnerStillAlive(fsm, OWNER)).isFalse();
    }
}
