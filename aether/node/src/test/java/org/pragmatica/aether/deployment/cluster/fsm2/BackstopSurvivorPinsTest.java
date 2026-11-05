// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;
import java.util.Set;

import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.MbWorld.C;
import static org.pragmatica.aether.deployment.cluster.fsm2.MbWorld.GOV;
import static org.pragmatica.aether.deployment.cluster.fsm2.MbWorld.tokenOf;
import static org.assertj.core.api.Assertions.assertThat;


/// Survivor pins for [Backstop] (F1 closure, lane fsmf1): each test names the line whose mutation it holds.
class BackstopSurvivorPinsTest {
    private static final long TOKEN_2 = 0x2222L;
    private static final String SUPPRESSED = "aether_deployment_fsm_departures_inferred_suppressed_total";

    /// Red when: the breaker emits its held-back metric when no worker is held back (`held > 0` weakened to `>= 0`).
    @Test
    void breaker_withNoWorkerPastItsBound_emitsNoHeldBackMetric() {
        var members = new NodeId[]{node("e1"), node("e2"), node("e3"), node("e4"), node("e5"), node("e6")};
        var present = List.of(members[4], members[5]);
        var w = MbWorld.world(CORE_1);

        w.admit(CORE_1, 0x1111L);
        w.workers("e-comm", 10, Set.of(members), members);
        for (var member : members) {
            w.instance(member, BILLING, SliceGoal.ACTIVE, SliceObservedState.ACTIVE, gen(1), tokenOf(member));
        }

        w.activate();
        w.evolve("e-comm", GOV, Set.copyOf(present), present, 40_000L);

        var absent = List.of(members[0], members[1], members[2], members[3]);
        var earliest = absent.stream().mapToLong(node -> w.since(node).or(Long.MAX_VALUE)).min().orElseThrow();
        var m = w.h.model();
        var ctx = new Ctx(w.h.settings, w.h.wallMs, earliest + bounds().workerRemoval());

        assertThat(Backstop.breakerHolds(m, "e-comm")).as("precondition: the breaker holds").isTrue();
        m.announcementSeenAt = m.announcementSeenAt.put("e-comm", ctx.monoNs());
        Backstop.run(m, ctx);

        assertThat(ctx.effects().stream().filter(ClusterEffect.Metric.class::isInstance).map(ClusterEffect.Metric.class::cast)
                      .filter(metric -> SUPPRESSED.equals(metric.name()))).as("nobody is past the bound: nothing held back").isEmpty();
    }

    private static String recordedWarning(MbWorld w) {
        var warns = w.h.warns("departure-inferred");

        assertThat(warns).as("precondition: one departure was inferred").hasSize(1);

        return warns.getFirst().message();
    }

    /// Red when: an inferred departure's warning carries the announcement age for a core and not for a worker (`==` weakened to `!=`).
    @Test
    void inferredWorkerDeparture_warningCarriesTheAnnouncementAge() {
        var w7 = node("w7");
        var others = List.of(node("m1"), node("m2"), node("m3"), node("m4"));
        var alive = Set.copyOf(others);
        var w = MbWorld.world(CORE_1);

        w.admit(CORE_1, 0x1111L);
        w.workers(C, 10, alive, w7, others.get(0), others.get(1), others.get(2), others.get(3))
         .target(ORDERS, 1, "WORKERS_ONLY");
        w.instance(w7, ORDERS, SliceGoal.ACTIVE, SliceObservedState.ACTIVE, gen(1), tokenOf(w7)).activate();
        w.evolve(C, GOV, alive, others, 5_000L);
        w.evolveUntil(w.since(w7).or(0L) + bounds().workerRemoval() + 1_000_000L + 31_000_000_000L, C, GOV, alive, others);

        assertThat(recordedWarning(w)).contains("announcement age");
    }

    @Test
    void inferredCoreDeparture_warningHasNoAnnouncementAge() {
        var w = MbWorld.world(CORE_1, CORE_2);

        w.admit(CORE_1, 0x1111L);
        w.admit(CORE_2, TOKEN_2);
        w.directive(CORE_2, ActivationDirectiveValue.core())
         .target(ORDERS, 1, "CORE_ONLY")
         .instance(CORE_2, ORDERS, SliceGoal.ACTIVE, SliceObservedState.ACTIVE, gen(1), TOKEN_2)
         .record(CORE_2, DepartureState.ADMITTED, 1L, TOKEN_2, null)
         .seams(s -> s.withCoreView(Option.some(Set.of(CORE_1))));
        w.activate();
        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.h.advance(ms(bounds().coreRemoval()) + 31_000L);

        assertThat(recordedWarning(w)).doesNotContain("announcement age");
    }
}
