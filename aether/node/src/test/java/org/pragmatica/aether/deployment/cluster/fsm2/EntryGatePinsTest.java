// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureCause;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.MbWorld.GOV;
import static org.pragmatica.aether.deployment.cluster.fsm2.MbWorld.tokenOf;
import static org.pragmatica.aether.deployment.cluster.fsm2.SlFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;


/// Pins for the L2 entry gate: the cluster-FSM lines a full-line mutation run (v-2463 T2) left alive. Each test names the line it
/// holds.
class EntryGatePinsTest {
    private static final Artifact A = artifact("org.example:a:1.0.0");
    private static final Artifact B = artifact("org.example:b:1.0.0");
    private static final Artifact C = artifact("org.example:c:1.0.0");
    private static final String ATTEMPT = LcSupport.ATTEMPT;
    private static final String SUPPRESSED = "aether_deployment_fsm_departures_inferred_suppressed_total";

    // ------------------------------------------------------------------------------------------ Backstop.java: held-back count

    private static MbWorld sixWorkers(NodeId[] members) {
        var w = MbWorld.world(CORE_1);

        w.admit(CORE_1, 0x1111L);
        w.workers("e-comm", 10, Set.of(members), members);
        for (var member : members) {
            w.instance(member, BILLING, SliceGoal.ACTIVE, SliceObservedState.ACTIVE, gen(1), tokenOf(member));
        }

        return w.activate();
    }

    /// The community's announcement is made fresh at the probed instant, so the pass judges the workers instead of treating them
    /// as unjudgeable.
    private static long heldBack(ClusterModel model, Ctx ctx) {
        model.announcementSeenAt = model.announcementSeenAt.put("e-comm", ctx.monoNs());
        Backstop.run(model, ctx);

        return ctx.effects()
                  .stream()
                  .filter(ClusterEffect.Metric.class::isInstance)
                  .map(ClusterEffect.Metric.class::cast)
                  .filter(metric -> SUPPRESSED.equals(metric.name()))
                  .mapToLong(ClusterEffect.Metric::delta)
                  .sum();
    }

    /// Red when: the breaker's held-back count (`departure-inferred-suppressed`) counts a worker absent for exactly its removal
    /// bound (`>=` instead of `>`), or leaves out one absent for longer.
    @Test
    void breakerHeldBackCount_countsOnlyWorkersAbsentPastTheirBound_notAtIt() {
        var members = new NodeId[]{node("e1"), node("e2"), node("e3"), node("e4"), node("e5"), node("e6")};
        var present = List.of(members[4], members[5]);
        var w = sixWorkers(members);

        w.evolve("e-comm", GOV, Set.copyOf(present), present, 40_000L);

        var absent = List.of(members[0], members[1], members[2], members[3]);
        var latest = absent.stream().mapToLong(node -> w.since(node).or(-1L)).max().orElseThrow();
        var earlier = absent.stream().filter(node -> w.since(node).or(-1L) < latest).count();
        var bound = bounds().workerRemoval();

        assertThat(absent).as("precondition: every one of them is recorded absent").allMatch(node -> w.since(node).isPresent());
        assertThat(Backstop.breakerHolds(w.h.model(), "e-comm")).as("precondition: the breaker holds").isTrue();
        assertThat(heldBack(w.h.model(), new Ctx(w.h.settings, w.h.wallMs, latest + bound))).as("at the bound: only the workers absent longer").isEqualTo(earlier);
        assertThat(heldBack(w.h.model(), new Ctx(w.h.settings, w.h.wallMs, latest + bound + 1))).as("one ns past it: all four").isEqualTo(4L);
    }

    // ------------------------------------------------------------------------------------------ Rebalance.java: the two-node floor

    /// Red when: a cluster of exactly two allocatable cores is not levelled (`size() < 2` weakened to `<= 2`).
    @Test
    void rebalance_levelsTwoAllocatableCores() {
        var h = harness(CORE_1, CORE_2);

        for (var artifact : new Artifact[]{A, B, C}) {
            seedTarget(h, artifact, 1, 1);
            seedGoal(h, CORE_1, artifact, SliceGoal.ACTIVE, G1);
            seedRow(h, CORE_1, artifact, SliceObservedState.ACTIVE, G1, 11L);
        }

        h.activate();
        h.deliver(new ClusterInput.Reconcile());

        assertThat(goalOf(h, CORE_1, A)).as("one artifact leaves the loaded core").isEqualTo(Option.some(SliceGoal.UNLOADED));
        assertThat(goalOf(h, CORE_2, A)).as("and loads on the other").isEqualTo(Option.some(SliceGoal.LOADED));
    }

    // ------------------------------------------------------------------------------------------ Placement.java: the placeable roster

    /// Red when: a community's placeable members include a roster entry that is not a recorded worker (a counted core) or one that is
    /// a recorded worker but no longer counts (`&&` weakened to `||`).
    @Test
    void placeableMembers_areTheRecordedWorkersThatStillCount() {
        var live = List.of(node("p1"), node("p3"), node("p4"));
        var gone = node("p2");
        var everyone = new NodeId[]{live.get(0), gone, live.get(1), live.get(2)};
        var w = MbWorld.world(CORE_1);

        w.admit(CORE_1, 0x1111L);
        w.workers("pc", 10, Set.of(everyone), everyone);
        w.record(gone, DepartureState.DEPARTED, 2L, tokenOf(gone) + 1L, DepartureCause.WORKER_LEAVE);
        w.activate();
        w.announce("pc", live.get(0), live.get(0), gone, live.get(1), live.get(2), CORE_1);

        assertThat(w.counts(live.get(0))).as("precondition: a live worker counts").isTrue();
        assertThat(w.counts(gone)).as("precondition: the departed worker does not").isFalse();
        assertThat(w.counts(CORE_1)).as("precondition: the listed core counts").isTrue();
        assertThat(Placement.workersByCommunity(w.h.model()).get("pc")).containsExactlyInAnyOrderElementsOf(live);
    }

    // ------------------------------------------------------------------------------------------ Absence.java: core classification

    private static Absence.NodeClass classWith(NodeId node, boolean directive, boolean counted, boolean view) {
        var w = MbWorld.world(CORE_1);

        w.admit(CORE_1, 0x1111L);
        if (directive) {
            w.directive(node, ActivationDirectiveValue.core());
        }

        w.seams(s -> s.withCountedCores(counted ? Set.of(CORE_1, node) : Set.of(CORE_1))
                      .withCoreView(Option.some(view ? Set.of(CORE_1, node) : Set.of(CORE_1))));
        w.activate();

        return Absence.classOf(w.h.model(), node);
    }

    /// Red when: a node is classed CORE only if all three sources agree (`||` weakened to `&&`): its directive, the counted cores,
    /// or the local view of the core set each name it alone.
    @Test
    void nodeClass_isCore_whenAnyOneSourceNamesIt() {
        var n = node("x1");

        assertThat(classWith(n, true, false, false)).as("directive only").isEqualTo(Absence.NodeClass.CORE);
        assertThat(classWith(n, false, true, false)).as("counted cores only").isEqualTo(Absence.NodeClass.CORE);
        assertThat(classWith(n, false, false, true)).as("core view only").isEqualTo(Absence.NodeClass.CORE);
        assertThat(classWith(n, false, false, false)).as("none").isEqualTo(Absence.NodeClass.UNASSIGNED_WORKER);
    }

    // ------------------------------------------------------------------------------------------ Failures.java: settle once per artifact

    private FsmHarness twoInstances() {
        var h = LcSupport.leader(DeploymentAtomicity.ALL_OR_NOTHING, 0.5d, CORE_1, CORE_2, CORE_3);

        seedTarget(h, A, 2, 1);
        h.activate();
        for (var node : hosts(h)) {
            LcSupport.node(h, node, 11L).writes(A, SliceObservedState.LOADING);
        }

        h.drain();

        return h;
    }

    private static List<NodeId> hosts(FsmHarness h) {
        return new ArrayList<>(h.model().desiredByArtifact.get(A).or(PSet.<NodeId> empty()).toList());
    }

    private static long failedRoutes(FsmHarness h) {
        return h.effects(ClusterEffect.Route.class).stream().filter(route -> route.event() instanceof RoutedEvent.DeploymentFailed).count();
    }

    /// Red when: two fatal failures of one artifact in one drain settle twice, each routing `DeploymentFailed` (the mark made earlier in
    /// this drain is not honoured: `permanentlyFailed || markedThisDrain` weakened to `&&`).
    @Test
    void twoFatalFailuresOfOneArtifact_oneDrain_settleOnce() {
        var h = twoInstances();

        h.clearLog();
        for (var node : hosts(h)) {
            LcSupport.node(h, node, 11L).fails(A, true);
        }

        h.drain();
        assertThat(failedRoutes(h)).isEqualTo(1L);
    }

    /// Red when: a fatal failure of an artifact whose permanent mark is already committed settles again (the committed mark is not
    /// honoured: `permanentlyFailed || markedThisDrain` weakened to `&&`).
    @Test
    void fatalFailure_ofAnArtifactAlreadyMarkedPermanent_doesNotSettleAgain() {
        var h = twoInstances();
        var nodes = hosts(h);

        LcSupport.node(h, nodes.get(0), 11L).fails(A, true);
        h.drain();
        assertThat(h.model().judgments.get(A).map(j -> j.permanentFailure()).or(false)).as("precondition: the mark is committed").isTrue();
        assertThat(failedRoutes(h)).as("precondition: the first failure was announced").isEqualTo(1L);

        h.clearLog();
        LcSupport.node(h, nodes.get(1), 11L).fails(A, true);
        h.drain();
        assertThat(failedRoutes(h)).as("the second is not announced again").isZero();
    }

    // ------------------------------------------------------------------------------------------ Gc.java: one draft per drain

    /// Red when: the desired-key removals of one drain are not merged into one transaction (the lookup of the existing GC draft matches
    /// the wrong kind).
    @Test
    void gc_batchesTheDesiredRemovalsOfOneDrainIntoOneDraft() {
        var h = harness(CORE_1, CORE_2);
        var first = SlicePair.slicePair(CORE_1, A);
        var second = SlicePair.slicePair(CORE_2, B);

        h.activate();
        h.seed(first.desiredKey(), desired(SliceGoal.UNLOADED, gen(1)));
        h.seed(second.desiredKey(), desired(SliceGoal.UNLOADED, gen(1)));
        h.drain();

        var model = h.model();

        Gc.removeDesired(model, first);
        Gc.removeDesired(model, second);

        var drafts = model.outbox.stream().filter(draft -> draft.kind() == SubmitKind.GC).toList();

        assertThat(drafts).as("one GC transaction").hasSize(1);
        assertThat(drafts.getFirst().mutations()).as("removing both desired keys").hasSize(2);
    }
}
