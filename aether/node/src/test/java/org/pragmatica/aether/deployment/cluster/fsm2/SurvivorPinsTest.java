// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Set;

import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.slice.kvstore.AetherKey.ArtifactJudgmentKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceDesiredKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureState;
import org.pragmatica.aether.slice.kvstore.AetherValue.Generation;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.aether.slice.kvstore.CommunityState;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;


/// Boundaries and one-per-node properties that the first mutation battery left unpinned (v-2174 F17). Each test names the line
/// whose comparison or guard it holds.
class SurvivorPinsTest {
    private static final long TOKEN_2 = 0x2222L;
    private static final long T_LOAD_MS = 120_000L;

    private static MbWorld coreWorld() {
        var w = MbWorld.world(CORE_1, CORE_2);

        w.admit(CORE_1, 1L, 0x1111L);
        w.admit(CORE_2, 1L, TOKEN_2);

        return w.directive(CORE_2,
                           ActivationDirectiveValue.core())
                .target(ORDERS, 1, "CORE_ONLY")
                .instance(CORE_2,
                          ORDERS,
                          SliceGoal.ACTIVE,
                          SliceObservedState.ACTIVE,
                          gen(1),
                          TOKEN_2)
                .record(CORE_2, DepartureState.ADMITTED, 1L, TOKEN_2, null)
                .seams(s -> s.withCoreView(Option.some(Set.of(CORE_1))));
    }

    /// Red when: step 7 records a core at exactly its removal bound (`>=` instead of `>`), or does not record it one nanosecond later.
    @Test
    void backstop_recordsOnlyPastTheBound_notAtIt() {
        var w = coreWorld().activate();
        var since = w.since(CORE_2).or(0L);
        var bound = bounds().coreRemoval();

        assertThat(Backstop.warrants(w.h.model(),
                                     new Ctx(w.h.settings, w.h.wallMs, since + bound),
                                     CORE_2)).as("at the bound")
                  .isFalse();
        assertThat(Backstop.warrants(w.h.model(),
                                     new Ctx(w.h.settings, w.h.wallMs, since + bound + 1),
                                     CORE_2)).as("one ns past it")
                  .isTrue();
    }

    /// Red when: an announcement is fresh at exactly `S_ann` (`<=` instead of `<`), or stale one nanosecond before it.
    @Test
    void announcement_isFreshBelowSAnn_andStaleAtIt() {
        var w = MbWorld.world(CORE_1);
        var members = new NodeId[]{node("m1"), node("m2"), node("m3")};

        w.admit(CORE_1, 1L, 0x1111L);
        w.community("c", 10, CommunityState.ACTIVE).activate();
        w.announce("c", members[0], members);
        var seen = w.h.model().announcementSeenAt.get("c").or(0L);
        var freshness = bounds().announcementFreshness();

        assertThat(Absence.isFresh(w.h.model(),
                                   new Ctx(w.h.settings, w.h.wallMs, seen + freshness - 1),
                                   "c")).as("one ns before")
                  .isTrue();
        assertThat(Absence.isFresh(w.h.model(),
                                   new Ctx(w.h.settings, w.h.wallMs, seen + freshness),
                                   "c")).as("at S_ann")
                  .isFalse();
    }

    /// Red when: a generation equal to the counter is not noted, so the next fresh generation equals an observed one (NT-114, `>=`).
    @Test
    void noteGeneration_movesTheCounterPastAnObservedEqualGeneration() {
        var w = coreWorld().activate();
        var model = w.h.model();
        var sequence = model.leader().map(leader -> leader.viewSequence()).or(0L);
        var before = model.genCounter;

        Indexer.noteGeneration(model, new Generation(sequence, before));
        assertThat(model.genCounter).isEqualTo(before + 1);
    }

    /// Red when: a recorded exclusion at the goal's own generation is recorded again by every reconcile (`>= 0` instead of `> 0`). The
    /// node never answers the retraction's UNLOAD, so the "goal already UNLOADED: record only" arm runs and must then stay quiet.
    @Test
    void retraction_isRecordedOnce_notOnEveryReconcile() {
        var h = LcSupport.active();

        h.seed(new SliceTargetKey(ORDERS.base()),
               SliceTargetValue.sliceTargetValue(ORDERS.version(), 1, 1));
        h.drain();
        new NodeSim(h, CORE_1, process(11L)).writes(ORDERS, SliceObservedState.LOADING);
        h.drain();
        h.advance(3 * T_LOAD_MS + 1_000L);
        h.deliver(new ClusterInput.Reconcile());
        h.advance(3 * T_LOAD_MS + 1_000L);
        h.deliver(new ClusterInput.Reconcile());
        h.deliver(new ClusterInput.Reconcile());
        var settled = judgmentWrites(h);

        h.deliver(new ClusterInput.Reconcile());
        h.deliver(new ClusterInput.Reconcile());
        assertThat(judgmentWrites(h)).as("no further judgment write once the exclusion is recorded").isEqualTo(settled);
    }

    private static long judgmentWrites(FsmHarness h) {
        return h.submits()
                .stream()
                .flatMap(submit -> submit.transaction()
                                         .mutations()
                                         .stream())
                .filter(mutation -> mutation.key() instanceof ArtifactJudgmentKey)
                .count();
    }

    /// Red when: a second C17b is built for a node while one is in flight (the `cleanupInFlight` guard).
    @Test
    void cleanup_isBuiltOncePerNode_whileOneIsInFlight() {
        var w = coreWorld().activate();

        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));
        w.h.applyHeld(w.h.submits(SubmitKind.DEPARTURE).getFirst().correlationId());
        w.h.drain();
        assertThat(w.h.submits(SubmitKind.CLEANUP)).hasSize(1);
        w.h.deliver(new ClusterInput.Reconcile());
        w.h.deliver(new ClusterInput.Reconcile());
        assertThat(w.h.submits(SubmitKind.CLEANUP)).as("still one: the first is in flight").hasSize(1);
    }

    /// Red when: a departed node (DEPARTED record committed by the backstop, its rows not yet collected) is given a goal by the divergence
    /// clock: the departed half of "a departing or departed node gets no new goals". The departure is inferred (C17s), because a
    /// membership departure removes the node's desired keys in its own transaction and leaves nothing to re-issue.
    @Test
    void departedNode_getsNoReissuedGoal() {
        var w = coreWorld();

        w.seams(s -> s.withCoreView(Option.some(Set.of(CORE_1, CORE_3))));
        w.h.seedQuietly(new SliceDesiredKey(CORE_2, ORDERS), new SliceDesiredValue(SliceGoal.LOADED, gen(7), w.h.wallMs));
        w.activate();
        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.h.advance(ms(bounds().coreRemoval()) + 31_000L);
        assertThat(w.inferred()).as("the backstop recorded the departure").hasSize(1);
        w.h.applyHeld(w.inferred().getFirst().correlationId());
        w.h.drain();
        assertThat(w.h.model().departed.contains(CORE_2)).isTrue();
        var before = goalPuts(w.h);

        w.h.advance(T_LOAD_MS + 1_000L);
        w.h.deliver(new ClusterInput.Reconcile());
        assertThat(w.h.model().desired(SlicePair.slicePair(CORE_2, ORDERS))).as("the desired key is still there to be re-issued")
                  .isNotEqualTo(Option.none());
        assertThat(goalPuts(w.h)).isEqualTo(before);
    }

    private static long goalPuts(FsmHarness h) {
        return h.submits()
                .stream()
                .flatMap(submit -> submit.transaction()
                                         .mutations()
                                         .stream())
                .filter(mutation -> mutation.key() instanceof SliceDesiredKey desired
                                    && desired.nodeId()
                                              .equals(CORE_2)
                                    && mutation.replacement()
                                               .isPresent())
                .count();
    }
}
