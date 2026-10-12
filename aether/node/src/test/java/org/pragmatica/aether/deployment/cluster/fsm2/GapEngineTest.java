// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.SlFixtures.*;

/// The outer states of the FSM (spec 3.2) and the incarnation discipline (R-3.1.3), arms the NT rows do not reach.
class GapEngineTest {
    private static ClusterInput.Timer staleTimer(long incarnation) {
        return new ClusterInput.Timer(TimerId.timerId(TimerId.Kind.TICK), incarnation);
    }

    /// Red when: an input that arrives between `Activate` and `Rebuild` is dropped or handled on an empty model, or `Deactivate`
    /// while rebuilding does not return to Dormant with the incarnation remembered (R-3.1.6, NT-01 held-input arm).
    @Test
    void rebuilding_holdsInputs_ignoresActivate_dropsStaleCompletions_andDeactivateReturnsToDormant() {
        var h = threeCores();

        h.step(new ClusterInput.Activate());
        h.mailbox.clear();

        assertThat(h.state).isInstanceOf(ClusterState.Rebuilding.class);
        h.step(new ClusterInput.Activate());
        h.step(staleTimer(1L));
        h.step(new ClusterInput.WriteSettled("c1-1", 1L, new ClusterInput.Settlement.Accepted()));
        assertThat(((ClusterState.Rebuilding) h.state).held()).as("a second Activate, a timer and a completion are not held").isEmpty();

        h.step(new ClusterInput.KvPut(new SliceTargetKey(ORDERS.base()), target(ORDERS, 1, 1, "CORE_ONLY")));
        assertThat(((ClusterState.Rebuilding) h.state).held()).as("any other input is held, in order").hasSize(1);

        h.step(new ClusterInput.Deactivate());
        assertThat(h.state).isEqualTo(new ClusterState.Dormant(1L));

        h.step(new ClusterInput.Activate());
        assertThat(((ClusterState.Rebuilding) h.state).incarnation()).as("a fresh Active entry gets a fresh incarnation").isEqualTo(2L);
    }

    /// Red when: a timer or a completion of an earlier incarnation is handled after the FSM was deactivated and activated again
    /// (R-3.1.3): it would write for a leadership that no longer exists.
    @Test
    void staleIncarnation_timersAndCompletions_areDropped() {
        var h = threeCores();

        h.activate();
        h.deliver(new ClusterInput.Deactivate());
        h.activate();
        h.clearLog();
        var model = h.model();

        h.deliver(staleTimer(1L));
        h.deliver(new ClusterInput.WriteSettled("c1-1", 1L, new ClusterInput.Settlement.Accepted()));

        assertThat(h.model().incarnation()).isEqualTo(2L);
        assertThat(h.effects(ClusterEffect.Schedule.class)).as("the stale tick re-armed nothing").isEmpty();
        assertThat(h.metricTotal("aether_deployment_fsm_inputs_total", tags -> "dropped_stale".equals(tags.get("result")))).isEqualTo(2L);
        assertThat(h.model().tickCount).isEqualTo(model.tickCount);
    }

    /// Red when: a `Rebuild` delivered to an Active FSM is ignored, so the model keeps a view the mirror no longer has.
    @Test
    void rebuild_whileActive_rebuildsTheModelFromTheSnapshot() {
        var h = threeCores();

        h.activate();
        h.seedQuietly(new SliceTargetKey(ORDERS.base()), target(ORDERS, 2, 1, "CORE_ONLY"));
        assertThat(h.model().targets.containsKey(ORDERS.base())).isFalse();

        h.deliver(new ClusterInput.Rebuild(h.snapshot()));

        assertThat(h.model().targets.containsKey(ORDERS.base())).isTrue();
        assertThat(h.model().incarnation()).as("the incarnation is kept").isEqualTo(1L);
    }

    /// Red when: the interpreter's `WitnessStale` does not suspend submissions, or the committed leader value naming this node
    /// again does not resume them (3.2, R-5.7.1 (a)).
    @Test
    void witnessStale_suspendsSubmissions_andTheLeaderValueNamingSelfResumes() {
        var h = threeCores();

        h.activate();
        h.deliver(new ClusterInput.WitnessStale());
        h.seed(new SliceTargetKey(ORDERS.base()), target(ORDERS, 1, 1, "CORE_ONLY"));
        h.drain();

        assertThat(h.submits()).as("suspended: the target was indexed, nothing was submitted").isEmpty();
        assertThat(h.model().targets.containsKey(ORDERS.base())).isTrue();

        h.commitLeader(FsmHarness.SELF, 2L);
        h.drain();

        assertThat(h.model().leader()).isEqualTo(org.pragmatica.lang.Option.some(new LeaderValue(FsmHarness.SELF, 2L)));
        assertThat(h.submits()).as("resumed: the reconcile placed the instance").isNotEmpty();
    }

    /// Red when: a refusal whose re-read shows the committed leader value moved (but still names this node) is budgeted or
    /// dropped instead of being resubmitted at once with the new witness (R-5.7.2: own witness stale, not budgeted).
    @Test
    void ownWitnessStale_isRebuiltAndResubmitted_withoutSpendingTheBudget() {
        var h = threeCores();

        seedTarget(h, ORDERS, 1, 1);
        h.policy = FsmHarness.SettlePolicy.HOLD;
        h.activate();
        var original = h.submits().getFirst();

        h.commitLeader(FsmHarness.SELF, 2L);
        h.drain();
        h.held.clear();
        h.settleHeld(original, h.refused(original));

        var resubmitted = h.submits().getLast();

        assertThat(h.submits()).hasSize(2);
        assertThat(resubmitted.transaction().leader()).as("the new witness").isEqualTo(new LeaderValue(FsmHarness.SELF, 2L));
        assertThat(resubmitted.transaction().mutations()).as("the identical write").isEqualTo(original.transaction().mutations());
    }

    /// Red when: a refusal whose re-read shows another node as the committed leader resubmits anything instead of suspending
    /// (R-5.7.2 deposed, NT-91 second arm).
    @Test
    void deposed_suspends_andResubmitsNothing() {
        var h = threeCores();

        seedTarget(h, ORDERS, 1, 1);
        h.policy = FsmHarness.SettlePolicy.HOLD;
        h.activate();
        var original = h.submits().getFirst();

        h.commitLeader(CORE_2, 2L);
        h.drain();
        h.held.clear();
        h.settleHeld(original, h.refused(original));

        assertThat(h.submits()).as("nothing was resubmitted").hasSize(1);
        assertThat(h.model().pendingGoals.isEmpty()).isTrue();
    }

    /// Red when: `SeamsRefreshed` with a changed counted-core set does not queue a reconcile, so a node that became ready is
    /// never used.
    @Test
    void seamsRefresh_withANewCore_replacesTheShortfall() {
        var h = harness(CORE_1);

        seedTarget(h, ORDERS, 2, 1);
        h.activate();
        assertThat(hosts(h, ORDERS)).containsExactly(CORE_1);

        h.deliver(new ClusterInput.SeamsRefreshed(h.seams.withCountedCores(Set.of(CORE_1, CORE_2)).withReady(Set.of(CORE_1, CORE_2))));

        assertThat(hosts(h, ORDERS)).containsExactly(CORE_1, CORE_2);
    }
}
