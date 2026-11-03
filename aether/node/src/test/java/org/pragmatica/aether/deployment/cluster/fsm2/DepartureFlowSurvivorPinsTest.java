// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;
import java.util.Set;

import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;


/// Survivor pins for [DepartureFlow] (F1 closure, lane fsmf1): each test names the line whose mutation it holds.
class DepartureFlowSurvivorPinsTest {
    private static final long TOKEN_2 = 0x2222L;

    private static MbWorld coreWorld() {
        var w = MbWorld.world(CORE_1, CORE_2);

        w.admit(CORE_1, 0x1111L);
        w.admit(CORE_2, TOKEN_2);

        return w.directive(CORE_2, ActivationDirectiveValue.core())
                .target(ORDERS, 1, "CORE_ONLY")
                .instance(CORE_2, ORDERS, SliceGoal.ACTIVE, SliceObservedState.ACTIVE, gen(1), TOKEN_2)
                .record(CORE_2, DepartureState.ADMITTED, 1L, TOKEN_2, null)
                .seams(s -> s.withCoreView(Option.some(Set.of(CORE_1))));
    }

    /// CORE_2 is departed (its record committed), its rows are still there, and nothing is submitted or queued.
    private static MbWorld departedWorld() {
        var w = coreWorld().activate();

        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));
        w.h.applyHeld(w.h.submits(SubmitKind.DEPARTURE).getFirst().correlationId());
        w.h.drain();

        var m = w.h.model();

        assertThat(m.departed.contains(CORE_2)).as("precondition: departed").isTrue();
        m.submitted.keys().forEach(key -> m.submitted = m.submitted.remove(key));
        m.outbox = List.of();

        return w;
    }

    private static long cleanupDrafts(ClusterModel m) {
        return m.outbox.stream().filter(draft -> draft.kind() == SubmitKind.CLEANUP).count();
    }

    /// Red when: a C17b queued in the outbox, not yet submitted, is not seen by `cleanupInFlight` (the outbox arm's `==` weakened to `!=`):
    /// a second call in the same step builds a second C17b for the node.
    @Test
    void cleanup_calledTwiceBeforeTheFlush_queuesOneDraft() {
        var w = departedWorld();
        var m = w.h.model();
        var ctx = new Ctx(w.h.settings, w.h.wallMs, w.h.monoNs);

        DepartureFlow.cleanup(m, ctx, CORE_2, 0);
        assertThat(cleanupDrafts(m)).as("precondition: the first call queued one").isEqualTo(1L);
        DepartureFlow.cleanup(m, ctx, CORE_2, 0);
        assertThat(cleanupDrafts(m)).as("the second sees the queued one").isEqualTo(1L);
    }

    /// Red when: an overdue `departing` entry is re-read only strictly after the re-read period (`>=` weakened to `>`).
    @Test
    void rereadOverdue_rereadsAtExactlyTheRereadPeriod_notBefore() {
        var w = coreWorld().activate();

        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));

        var m = w.h.model();
        var since = m.departing.get(CORE_2).map(pending -> pending.sinceMono()).or(-1L);
        var bound = w.h.settings.timing().departingReread() * 1_000_000L;

        assertThat(since).as("precondition: CORE_2 is departing").isGreaterThanOrEqualTo(0L);
        m.outbox = List.of();
        DepartureFlow.rereadOverdue(m, new Ctx(w.h.settings, w.h.wallMs, since + bound - 1L));
        assertThat(m.outbox).as("one ns short of the period: not re-read").isEmpty();
        DepartureFlow.rereadOverdue(m, new Ctx(w.h.settings, w.h.wallMs, since + bound));
        assertThat(m.outbox.stream().filter(draft -> draft.kind() == SubmitKind.DEPARTURE).count()).as("at the period: re-read, resubmitted").isEqualTo(1L);
    }

    private static ClusterModel.TxDraft cleanupDraftOf(ClusterModel m, Ctx ctx, int attempt) {
        DepartureFlow.cleanup(m, ctx, CORE_2, attempt);

        var draft = m.outbox.getFirst();

        m.outbox = List.of();

        return draft;
    }

    /// Red when: a refused C17b is resubmitted at the attempt bound (`<` weakened to `<=`), or the resubmission does not count the attempt
    /// (`attempt() + 1` weakened to `+ 0`).
    @Test
    void cleanupRefused_resubmitsCountingTheAttempt_andStopsAtTheBound() {
        var w = departedWorld();
        var m = w.h.model();
        var ctx = new Ctx(w.h.settings, w.h.wallMs, w.h.monoNs);
        var last = Outbox.GOAL_RESUBMISSIONS;

        DepartureFlow.cleanupRefused(m, ctx, cleanupDraftOf(m, ctx, last - 1));
        assertThat(m.outbox).as("below the bound: resubmitted").hasSize(1);
        assertThat(m.outbox.getFirst().attempt()).as("counting the attempt").isEqualTo(last);
        m.outbox = List.of();
        DepartureFlow.cleanupRefused(m, ctx, cleanupDraftOf(m, ctx, last));
        assertThat(m.outbox).as("at the bound: left to the next reconcile").isEmpty();
    }

    private static Ctx ctxOf(MbWorld w) {
        return new Ctx(w.h.settings, w.h.wallMs, w.h.monoNs);
    }

    private static ClusterModel.TxDraft departureDraftOf(ClusterModel m) {
        return m.submitted.values()
                .stream()
                .flatMap(submitted -> submitted.drafts().stream())
                .filter(draft -> draft.kind() == SubmitKind.DEPARTURE)
                .findFirst()
                .orElseThrow();
    }

    private static long schedules(Ctx ctx) {
        return ctx.effects().stream().filter(ClusterEffect.Schedule.class::isInstance).count();
    }

    /// Red when: a failed departure schedules its re-read for a node that is no longer departing, or a second time when the timer is already
    /// armed (`departing && !timer` weakened to `||`).
    @Test
    void failedDeparture_schedulesOneReread_onlyWhileDepartingAndUnscheduled() {
        var w = coreWorld().activate();

        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));

        var m = w.h.model();
        var draft = departureDraftOf(m);
        var timer = TimerId.timerId(TimerId.Kind.DEPARTING_REREAD, CORE_2.id());

        m.timers = m.timers.remove(timer);
        var unscheduled = ctxOf(w);

        DepartureFlow.failed(m, unscheduled, draft);
        assertThat(schedules(unscheduled)).as("departing, no timer: scheduled").isEqualTo(1L);

        var armed = ctxOf(w);

        m.timers = m.timers.add(timer);
        DepartureFlow.failed(m, armed, draft);
        assertThat(schedules(armed)).as("departing, timer armed: not scheduled again").isZero();

        var resolved = ctxOf(w);

        m.timers = m.timers.remove(timer);
        m.departing = m.departing.remove(CORE_2);
        DepartureFlow.failed(m, resolved, draft);
        assertThat(schedules(resolved)).as("no longer departing: nothing to re-read").isZero();
    }
}
