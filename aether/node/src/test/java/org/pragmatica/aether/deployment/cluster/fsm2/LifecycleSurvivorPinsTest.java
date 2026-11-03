// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import org.pragmatica.cluster.state.kvstore.LeaderValue;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;


/// Survivor pins for [Lifecycle] (F1 closure, lane fsmf1): each test names the line whose mutation it holds.
class LifecycleSurvivorPinsTest {
    private static boolean resumed(ClusterModel m, Ctx ctx) {
        return m.joinWarningsDue || ctx.effects().stream().anyMatch(ClusterEffect.Enqueue.class::isInstance);
    }

    private static ClusterModel activeModel(FsmHarness h) {
        var m = h.model();

        m.joinWarningsDue = false;
        m.reconcileQueued = false;

        return m;
    }

    /// Red when: a leader value that names this node again, while it was not suspended, runs the resume (`wasSuspended && !isSuspended`
    /// weakened to `||`).
    @Test
    void onLeader_refreshOfTheOwnLeadership_doesNotResume() {
        var w = MbWorld.world(CORE_1).activate();
        var m = activeModel(w.h);
        var ctx = new Ctx(w.h.settings, w.h.wallMs, w.h.monoNs);

        Lifecycle.onLeader(m, ctx, new LeaderValue(FsmHarness.SELF, 5L));

        assertThat(resumed(m, ctx)).isFalse();
    }

    /// Red when: a leader value that names another node, while already suspended behind another leader, runs the resume (same weakening).
    @Test
    void onLeader_anotherLeaderWhileSuspended_doesNotResume() {
        var w = MbWorld.world(CORE_1).activate();
        var m = activeModel(w.h);
        var ctx = new Ctx(w.h.settings, w.h.wallMs, w.h.monoNs);

        Lifecycle.onLeader(m, ctx, new LeaderValue(CORE_1, 5L));
        assertThat(Lifecycle.isSuspended(m, ctx)).as("precondition: suspended behind CORE_1").isTrue();
        Lifecycle.onLeader(m, ctx, new LeaderValue(CORE_1, 6L));

        assertThat(resumed(m, ctx)).isFalse();
    }

    /// The positive arm: naming this node again after a suspension resumes.
    @Test
    void onLeader_ownLeadershipAfterASuspension_resumes() {
        var w = MbWorld.world(CORE_1).activate();
        var m = activeModel(w.h);
        var ctx = new Ctx(w.h.settings, w.h.wallMs, w.h.monoNs);

        Lifecycle.onLeader(m, ctx, new LeaderValue(CORE_1, 5L));
        Lifecycle.onLeader(m, ctx, new LeaderValue(FsmHarness.SELF, 6L));

        assertThat(m.joinWarningsDue).isTrue();
    }
}
