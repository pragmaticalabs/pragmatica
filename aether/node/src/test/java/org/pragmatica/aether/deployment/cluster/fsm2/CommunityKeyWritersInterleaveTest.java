// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.CommunitySizing;
import org.pragmatica.aether.config.cluster.CommunityPlacement;
import org.pragmatica.aether.config.cluster.CommunityPlacement.Location;
import org.pragmatica.aether.config.cluster.CommunityPolicy;
import org.pragmatica.aether.deployment.cluster.DerivedCommunityPlan;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.CommunityKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityValue;
import org.pragmatica.aether.slice.kvstore.CommunityState;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.SlFixtures.harness;

/// The `CommunityKey` double writer under FSM2 (P3-F plan 3; inventory OPEN QUESTION; r-p3f check 4), on the real applier. The cluster FSM
/// (J5 mint W1, the reconcile edge W2) and the community placement reconciler (restamp, BeginDissolve, Reopen W3, the align mint W5) write the
/// same key. Every write is a whole-value compare-and-set (`KVStore.validMutation`), so no interleaving loses a write; FSM2's from-state keeps
/// it out of the teardown states. The reconciler's writes are applied here in the exact shape `CommunityPlacementReconciler` builds them
/// (one `LeaderTransaction` mutation, expecting the value it read) `[instrument: the reconciler is not run; its transactions are reproduced]`.
///
/// Red when: a write overwrites a value it did not read (a lost update), FSM2 writes DISSOLVING or DISSOLVED, the mint race keeps both mints
/// or neither, or an assignment that lands before a dissolve does not end with the reconciler's Reopen.
class CommunityKeyWritersInterleaveTest {
    private static final NodeId W1 = node("worker-1");
    private static final NodeId W2 = node("worker-2");
    private static final NodeId W3 = node("worker-3");
    private static final CommunityKey C = new CommunityKey("c");

    /// A reconciler write: ONE leader transaction whose mutation expects `expected`, as `commitRewrite`/`alignCommunity`/`markDissolved` build it.
    private static boolean cprWrite(FsmHarness h, CommunityKey key, Option<AetherValue> expected, Option<AetherValue> replacement) {
        var transaction = new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key,
                                                                                  "cpr-" + UUID.randomUUID(),
                                                                                  h.committedLeader().unwrap(),
                                                                                  List.of(),
                                                                                  List.of(new KVCommand.Mutation<>(key, expected, replacement)));
        var accepted = ((KVCommand.TransactionResult) h.applyCommand(transaction)).accepted();

        h.drain();

        return accepted;
    }

    private static Option<CommunityValue> stored(FsmHarness h, CommunityKey key) {
        return h.store.get(key)
                      .filter(CommunityValue.class::isInstance)
                      .map(CommunityValue.class::cast);
    }

    /// The SlBlueprintTest fixture: community `c` FORMING with three live, directed members on its roster; reaching the floor makes FSM2 write
    /// FORMING to ACTIVE.
    private static FsmHarness formingCommunityOfThree() {
        var h = harness(CORE_1);

        h.seams = h.seams.withLocalAlive(Option.some(Set.of(W1, W2, W3)));
        h.seedQuietly(C, community("default", 3, CommunityState.FORMING));
        for (var w : List.of(W1, W2, W3)) {
            h.seedQuietly(new ActivationDirectiveKey(w), worker("c"));
        }
        h.seedQuietly(new GovernorAnnouncementKey("c"), roster(W1, false, W1, W2, W3));

        return h;
    }

    private static FsmHarness evaluated(FsmHarness h) {
        h.activate();
        h.advanceBySeconds(1);
        h.advance(31_000L);

        return h;
    }

    // (a) a reconciler restamp racing the FSM2 edge, both orders
    @Test
    void aRestampCommittedWhileTheEdgeIsInFlight_refusesTheEdge_andTheEdgeIsRederivedOnTheRestampedValue() {
        var h = formingCommunityOfThree();
        var forming = stored(h, C).unwrap();

        h.policy = FsmHarness.SettlePolicy.HOLD;
        evaluated(h);
        var edge = h.submits(ClusterModel.SubmitKind.COMMUNITY).getFirst();

        assertThat(cprWrite(h, C, Option.some(forming), Option.some(forming.withTargetSize(7)))).as("the restamp read the current value").isTrue();
        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.held.remove(edge.correlationId());
        h.settleHeld(edge, h.applyTransaction(edge));
        h.advance(31_000L);

        assertThat(stored(h, C).map(CommunityValue::targetSize)).as("the restamp survives").isEqualTo(Option.some(7));
        assertThat(stored(h, C).map(CommunityValue::state)).as("and so does the re-derived edge").isEqualTo(Option.some(CommunityState.ACTIVE));
    }

    @Test
    void aRestampBuiltBeforeTheEdgeCommitted_isRefused_andTheEdgeSurvives() {
        var h = formingCommunityOfThree();
        var forming = stored(h, C).unwrap();

        evaluated(h);

        assertThat(stored(h, C).map(CommunityValue::state)).isEqualTo(Option.some(CommunityState.ACTIVE));
        assertThat(cprWrite(h, C, Option.some(forming), Option.some(forming.withTargetSize(7)))).as("a stale reconciler read is refused").isFalse();
        assertThat(stored(h, C).map(CommunityValue::state)).as("no lost update").isEqualTo(Option.some(CommunityState.ACTIVE));
    }

    /// FSM2's from-state: once the reconciler began a dissolve, FSM2 writes nothing to the community, however its members change.
    @Test
    void aDissolvingCommunity_isNeverWrittenByFsm2() {
        var h = formingCommunityOfThree();
        var forming = stored(h, C).unwrap();

        assertThat(cprWrite(h, C, Option.some(forming), Option.some(forming.withState(CommunityState.DISSOLVING)))).isTrue();
        evaluated(h);

        assertThat(h.submits(ClusterModel.SubmitKind.COMMUNITY)).isEmpty();
        assertThat(stored(h, C).map(CommunityValue::state)).isEqualTo(Option.some(CommunityState.DISSOLVING));
    }

    // (b) mint vs mint of one DECLARED community: the reconciler's align mint and FSM2's J5 mint (`Directory.assignWorker`, `policy.id()` with
    // sourceName ""). Both write sourceName "" for a declared community; a derived `<source>-w-N` is minted by FSM2 alone. The values still differ
    // (createdAt, state), so the race is real: the first mint stands and the second, expecting none, is refused.
    private static MbWorld declaredPolicy() {
        return MbWorld.world(CORE_1, CORE_2, CORE_3)
                      .seams(seams -> seams.withCommunityPolicies(Map.of("p-a", new CommunityPlacement("p-a", 4, List.of(new Location("s1", Option.none(), 0, 1)))))
                                           .withMemberSource(Map.of(W1, "s1")))
                      .activate();
    }

    private static Option<AetherValue> alignMint() {
        return Option.some(CommunityValue.communityValue("", ActivationDirectiveValue.WORKER, 4));
    }

    @Test
    void mintVsMint_theReconcilerFirst_standsUnchanged_andFsm2AssignsIntoIt() {
        var w = declaredPolicy();
        var key = new CommunityKey("p-a");

        assertThat(cprWrite(w.h, key, Option.none(), alignMint())).isTrue();
        var reconcilers = stored(w.h, key).unwrap();

        w.h.deliver(new ClusterInput.WorkerJoin(W1, w.admit(W1, 21L)));
        w.h.drain();

        assertThat(stored(w.h, key)).as("the reconciler's mint stands, unchanged (parity with LEGACY)").isEqualTo(Option.some(reconcilers));
        assertThat(w.stored(new ActivationDirectiveKey(W1)).map(value -> ((ActivationDirectiveValue) value).communityId())).isEqualTo(Option.some("p-a"));
    }

    @Test
    void mintVsMint_fsm2First_refusesTheReconcilersMint() {
        var w = declaredPolicy();
        var key = new CommunityKey("p-a");

        w.h.deliver(new ClusterInput.WorkerJoin(W1, w.admit(W1, 21L)));
        w.h.drain();

        var fsm2s = stored(w.h, key).unwrap();

        assertThat(fsm2s.sourceName()).as("a declared community is minted with no source by FSM2 too").isEmpty();
        assertThat(cprWrite(w.h, key, Option.none(), alignMint())).as("the reconciler expected none").isFalse();
        assertThat(stored(w.h, key)).as("FSM2's mint stands, unchanged").isEqualTo(Option.some(fsm2s));
    }

    // (c) an assignment that lands before a BeginDissolve: the dissolve commits (it witnesses no directive) and the reconciler Reopens
    @Test
    void assignThenBeginDissolve_commits_andTheReconcilersNextPassReopens() {
        var w = MbWorld.world(CORE_1, CORE_2, CORE_3)
                       .seams(seams -> seams.withCommunityPolicies(Map.of()).withMemberSource(Map.of(W1, "east")))
                       .activate();
        var key = new CommunityKey("east-w-0");

        w.h.deliver(new ClusterInput.WorkerJoin(W1, w.admit(W1, 21L)));
        w.h.drain();
        var assigned = stored(w.h, key).unwrap();

        assertThat(cprWrite(w.h, key, Option.some(assigned), Option.some(assigned.withState(CommunityState.DISSOLVING)))).as("no directive witness").isTrue();
        var members = (int) w.h.model()
                               .directives
                               .entries()
                               .stream()
                               .filter(entry -> entry.getValue().communityId().equals("east-w-0"))
                               .count();
        var plan = DerivedCommunityPlan.plan(List.of(DerivedCommunityPlan.View.view("east-w-0", "east", CommunityState.DISSOLVING, assigned.targetSize(), members)),
                                             CommunityPolicy.communityPolicy(CommunitySizing.communitySizing(100, 3)),
                                             Map.of(),
                                             0L);

        assertThat(plan).as("a dissolving community with a member is reopened").containsExactly(DerivedCommunityPlan.Action.Reopen.reopen("east-w-0"));
        var dissolving = stored(w.h, key).unwrap();

        assertThat(cprWrite(w.h, key, Option.some(dissolving), Option.some(dissolving.withState(CommunityState.FORMING)))).isTrue();
        assertThat(stored(w.h, key).map(CommunityValue::state)).isEqualTo(Option.some(CommunityState.FORMING));
        assertThat(w.stored(new ActivationDirectiveKey(W1)).map(value -> ((ActivationDirectiveValue) value).communityId())).as("the member is kept")
                                                                                                                       .isEqualTo(Option.some("east-w-0"));
    }
}
