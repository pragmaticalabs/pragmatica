// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.cluster.CommunityPlacement;
import org.pragmatica.aether.config.cluster.CommunityPlacement.Location;
import org.pragmatica.aether.deployment.cluster.CommunityPlacementReconciler;
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
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.SlFixtures.harness;

/// The `CommunityKey` double writer under FSM2 (P3-F plan 3; inventory OPEN QUESTION; r-p3f check 4), on the real applier, with BOTH writers
/// real: the cluster FSM (J5 mint W1, the reconcile edge W2) and the `CommunityPlacementReconciler` itself (restamp, BeginDissolve, Reopen,
/// `markDissolved`, the declared community's align mint), run against the same store. A hook runs a FSM2 step between the reconciler's read and
/// its commit, which is where a race between the two lands. Every write is a whole-value compare-and-set (`KVStore.validMutation`), so no
/// interleaving loses a write; FSM2's from-state keeps it out of the teardown states.
///
/// Red when: a write overwrites a value it did not read (a lost update, in either writer), a refused FSM2 edge is not re-derived at once on the
/// value that refused it, FSM2 writes DISSOLVING or DISSOLVED, the mint race keeps both mints or neither, or an assignment that lands before a
/// dissolve does not end with the reconciler's Reopen.
class CommunityKeyWritersInterleaveTest {
    private static final NodeId W1 = node("worker-1");
    private static final NodeId W2 = node("worker-2");
    private static final NodeId W3 = node("worker-3");
    private static final long GRACE_MS = 60_000L;
    private static final String HEAD = """
        config_version = "1.0.0"
        [cluster]
        name = "test"
        version = "1.0.0"
        [source.eu]
        type = "forge"
        [source.eu.worker]
        count = 4
        """;
    private static final String POLICY = """
        [community_policy]
        max_size = 10
        viability_floor = 3
        max_communities = 5
        dissolve_grace_seconds = 60
        growth = true
        """;
    private static final String DECLARED = """
        [source.s1]
        type = "forge"
        zones = ["z"]
        [source.s1.worker]
        count = 1
        [community.p-a]
        target_size = 4
        [community.p-a.placement.destination]
        source = "s1"
        zone = "z"
        """;

    /// The reconciler over `h`'s store and applier. `beforeCommit` runs once, before the first batch after it is set that writes a
    /// `CommunityKey`, or a directive the race is about: the window between the reconciler's read and its commit.
    private static final class Reconciler {
        private final FsmHarness h;
        private final AtomicLong now = new AtomicLong(1_000_000L);
        private final List<Boolean> accepted = new ArrayList<>();
        /// Every transaction the reconciler submitted, as `key accepted|refused`, in order.
        private final List<String> transactions = new ArrayList<>();
        private Runnable beforeCommit = () -> {};
        private final CommunityPlacementReconciler reconciler;

        Reconciler(FsmHarness h) {
            this.h = h;
            this.reconciler = CommunityPlacementReconciler.communityPlacementReconciler(FsmHarness.SELF,
                                                                                       h.store,
                                                                                       this::apply,
                                                                                       () -> true,
                                                                                       _ -> true,
                                                                                       () -> Set.of(FsmHarness.SELF),
                                                                                       () -> 100,
                                                                                       new NoProvider(),
                                                                                       _ -> {},
                                                                                       (_, _) -> {},
                                                                                       TimeSpan.timeSpan(60_000).millis(),
                                                                                       now::get);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private Promise<List<Object>> apply(List<KVCommand<AetherKey>> commands) {
            if (commands.stream().anyMatch(Reconciler::writesACommunity)) {
                var hook = beforeCommit;

                beforeCommit = () -> {};
                hook.run();
            }
            var results = commands.stream().map(command -> h.applyCommand((KVCommand) command)).toList();

            for (var i = 0; i < results.size(); i++) {
                if (results.get(i) instanceof KVCommand.TransactionResult result) {
                    accepted.add(result.accepted());
                    transactions.add(commands.get(i).key() + " " + (result.accepted() ? "accepted" : "refused"));
                }
            }
            h.drain();

            return Promise.success(results);
        }

        private static boolean writesACommunity(KVCommand<AetherKey> command) {
            return command instanceof KVCommand.LeaderTransaction<?, ?> transaction
                   && transaction.mutations()
                                 .stream()
                                 .anyMatch(mutation -> mutation.key() instanceof CommunityKey);
        }

        Reconciler pass() {
            reconciler.reconcile().await();

            return this;
        }

        Reconciler afterTheGrace() {
            now.addAndGet(GRACE_MS + 1_000L);

            return this;
        }
    }

    private static final class NoProvider implements CommunityPlacementReconciler.Actuator {
        @Override
        public Result<String> sourceBinding(org.pragmatica.aether.environment.SourceName source) {
            return Result.success("binding");
        }

        @Override
        public Promise<Unit> create(AetherValue.CommunityPlacementOperationValue operation) {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Boolean> retirementSafe(AetherValue.CommunityPlacementOperationValue operation) {
            return Promise.success(false);
        }

        @Override
        public Promise<Unit> drain(AetherValue.CommunityPlacementOperationValue operation) {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> terminate(AetherValue.CommunityPlacementOperationValue operation) {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Boolean> previousInstanceExists(AetherValue.CommunityPlacementOperationValue operation) {
            return Promise.success(false);
        }
    }

    private static void configure(FsmHarness h, String toml) {
        h.seedQuietly(AetherKey.ClusterConfigKey.CURRENT,
                      new AetherValue.ClusterConfigValue(Option.some(toml), "test", "1.0.0", List.of(), 3, 3, "forge", 1, 0));
    }

    private static Option<CommunityValue> stored(FsmHarness h, String id) {
        return h.store.get(new CommunityKey(id))
                      .filter(CommunityValue.class::isInstance)
                      .map(CommunityValue.class::cast);
    }

    private static void seedCommunity(FsmHarness h, String id, int targetSize, CommunityState state, NodeId... members) {
        h.seedQuietly(new CommunityKey(id), community("eu", targetSize, state));
        for (var member : members) {
            h.seedQuietly(new ActivationDirectiveKey(member), worker(id));
        }
    }

    /// Derived community `eu-w-0` FORMING at target 3 (the policy says 10, so the reconciler restamps it) with three live, directed members on
    /// its roster: reaching the floor makes FSM2 write FORMING to ACTIVE.
    private static FsmHarness formingDerivedCommunityOfThree() {
        var h = harness(CORE_1);

        h.seams = h.seams.withLocalAlive(Option.some(Set.of(W1, W2, W3)));
        configure(h, HEAD + POLICY);
        seedCommunity(h, "eu-w-0", 3, CommunityState.FORMING, W1, W2, W3);
        h.seedQuietly(new GovernorAnnouncementKey("eu-w-0"), roster(W1, false, W1, W2, W3));

        return h;
    }

    private static List<ClusterEffect.Submit> communitySubmitsFor(FsmHarness h, String id) {
        return h.submits(ClusterModel.SubmitKind.COMMUNITY)
                .stream()
                .filter(submit -> submit.transaction()
                                        .mutations()
                                        .stream()
                                        .anyMatch(mutation -> mutation.key().equals(new CommunityKey(id))))
                .toList();
    }

    private static FsmHarness evaluated(FsmHarness h) {
        h.activate();
        h.advanceBySeconds(1);
        h.advance(31_000L);

        return h;
    }

    // (a) the reconciler's restamp racing the FSM2 edge, both orders
    // Red when: the restamp is lost; the refused edge is not re-derived at once, on the restamped value (M13: no re-derive request); or the
    // reconciler's restamp does not expect the value it read (N15).
    @Test
    void aRestampCommittedWhileTheEdgeIsInFlight_refusesTheEdge_andTheEdgeIsRederivedAtOnce_onTheRestampedValue() {
        var h = formingDerivedCommunityOfThree();
        var reconciler = new Reconciler(h);

        h.policy = FsmHarness.SettlePolicy.HOLD;
        evaluated(h);
        var edge = h.submits(ClusterModel.SubmitKind.COMMUNITY).getFirst();

        reconciler.pass();
        assertThat(stored(h, "eu-w-0").map(CommunityValue::targetSize)).as("the reconciler's restamp committed").isEqualTo(Option.some(10));
        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.held.remove(edge.correlationId());
        h.settleHeld(edge, h.applyTransaction(edge));
        h.drain();

        assertThat(stored(h, "eu-w-0").map(CommunityValue::targetSize)).as("the restamp survives").isEqualTo(Option.some(10));
        assertThat(stored(h, "eu-w-0").map(CommunityValue::state)).as("the refused edge is re-derived at once, before any tick")
                                                                   .isEqualTo(Option.some(CommunityState.ACTIVE));
    }

    @Test
    void aRestampReadBeforeTheEdgeCommitted_isRefused_andTheEdgeSurvives_andTheNextPassRestamps() {
        var h = formingDerivedCommunityOfThree();
        var reconciler = new Reconciler(h);

        h.policy = FsmHarness.SettlePolicy.HOLD;
        evaluated(h);
        var edge = h.submits(ClusterModel.SubmitKind.COMMUNITY).getFirst();

        reconciler.beforeCommit = () -> commitTheHeldEdge(h, edge);
        reconciler.pass();

        assertThat(reconciler.transactions).as("the reconciler's restamp read FORMING, and the edge committed first")
                                           .containsExactly("community/eu-w-0 refused");
        assertThat(stored(h, "eu-w-0").map(CommunityValue::state)).as("no lost update").isEqualTo(Option.some(CommunityState.ACTIVE));
        assertThat(stored(h, "eu-w-0").map(CommunityValue::targetSize)).isEqualTo(Option.some(3));
        reconciler.pass();
        assertThat(stored(h, "eu-w-0")).as("the next pass restamps the edge's value")
                                       .isEqualTo(Option.some(community("eu", 10, CommunityState.ACTIVE)));
    }

    private static void commitTheHeldEdge(FsmHarness h, ClusterEffect.Submit edge) {
        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.held.remove(edge.correlationId());
        h.settleHeld(edge, h.applyTransaction(edge));
        h.drain();
    }

    // (b) the teardown states: FSM2's from-state keeps it out of a community the reconciler began, or finished, dissolving
    /// An empty derived community `eu-w-1` beside a sibling with members; with growth on, the reconciler dissolves it after the grace.
    private static FsmHarness emptyDerivedCommunityBesideASibling() {
        var h = harness(CORE_1);

        h.seams = h.seams.withLocalAlive(Option.some(Set.of(W1, W2, W3)));
        configure(h, HEAD + POLICY);
        seedCommunity(h, "eu-w-0", 10, CommunityState.ACTIVE, node("m-1"), node("m-2"), node("m-3"));
        seedCommunity(h, "eu-w-1", 10, CommunityState.ACTIVE);

        return h;
    }

    /// Live, directed members on a roster of `eu-w-1`: enough for FSM2 to want an edge, were the community open.
    private static void membersOnTheRoster(FsmHarness h) {
        for (var w : List.of(W1, W2, W3)) {
            h.seedQuietly(new ActivationDirectiveKey(w), worker("eu-w-1"));
        }
        h.seedQuietly(new GovernorAnnouncementKey("eu-w-1"), roster(W1, false, W1, W2, W3));
    }

    // Red when: FSM2 writes anything to a community the reconciler's real BeginDissolve left DISSOLVING.
    @Test
    void aCommunityTheReconcilerBeganDissolving_isNeverWrittenByFsm2() {
        var h = emptyDerivedCommunityBesideASibling();

        new Reconciler(h).pass().afterTheGrace().pass();
        assertThat(stored(h, "eu-w-1").map(CommunityValue::state)).as("the reconciler's step one").isEqualTo(Option.some(CommunityState.DISSOLVING));
        membersOnTheRoster(h);
        evaluated(h);

        assertThat(communitySubmitsFor(h, "eu-w-1")).isEmpty();
        assertThat(stored(h, "eu-w-1").map(CommunityValue::state)).isEqualTo(Option.some(CommunityState.DISSOLVING));
    }

    // Red when: FSM2 writes anything to a community the reconciler's real `markDissolved` left DISSOLVED (M12: DISSOLVED moves to ACTIVE).
    @Test
    void aCommunityTheReconcilerDissolved_isNeverWrittenByFsm2() {
        var h = emptyDerivedCommunityBesideASibling();

        new Reconciler(h).pass().afterTheGrace().pass().pass();
        assertThat(stored(h, "eu-w-1").map(CommunityValue::state)).as("the reconciler's markDissolved").isEqualTo(Option.some(CommunityState.DISSOLVED));
        membersOnTheRoster(h);
        evaluated(h);

        assertThat(communitySubmitsFor(h, "eu-w-1")).isEmpty();
        assertThat(stored(h, "eu-w-1").map(CommunityValue::state)).isEqualTo(Option.some(CommunityState.DISSOLVED));
    }

    // (c) mint vs mint of one DECLARED community: the reconciler's real align mint and FSM2's J5 mint (`Directory.assignWorker`, `policy.id()`
    // with sourceName ""). Both write sourceName "" for a declared community. The values still differ (createdAt, state), so the race is
    // real: the first mint stands and the second, expecting none, is refused.
    private static MbWorld declaredPolicy() {
        var w = MbWorld.world(CORE_1, CORE_2, CORE_3)
                       .seams(seams -> seams.withCommunityPolicies(Map.of("p-a", new CommunityPlacement("p-a", 4, List.of(new Location("s1", Option.none(), 0, 1)))))
                                            .withMemberSource(Map.of(W1, "s1")));

        configure(w.h, HEAD + DECLARED);

        return w.activate();
    }

    // Red when: the reconciler's mint is overwritten by FSM2's, or W1 is not assigned into it.
    @Test
    void mintVsMint_theReconcilerFirst_standsUnchanged_andFsm2AssignsIntoIt() {
        var w = declaredPolicy();

        new Reconciler(w.h).pass();
        var reconcilers = stored(w.h, "p-a").unwrap();

        assertThat(reconcilers.sourceName()).as("the reconciler's align mint").isEmpty();
        w.h.deliver(new ClusterInput.WorkerJoin(W1, w.admit(W1, 21L)));
        w.h.drain();

        assertThat(stored(w.h, "p-a")).as("the reconciler's mint stands, unchanged (parity with LEGACY)").isEqualTo(Option.some(reconcilers));
        assertThat(w.stored(new ActivationDirectiveKey(W1)).map(value -> ((ActivationDirectiveValue) value).communityId())).isEqualTo(Option.some("p-a"));
    }

    // Red when: the reconciler's align mint, read before FSM2's mint committed, is not refused, or replaces FSM2's mint.
    @Test
    void mintVsMint_fsm2FirstBetweenTheReconcilersReadAndCommit_refusesTheReconcilersMint() {
        var w = declaredPolicy();
        var reconciler = new Reconciler(w.h);
        var fsm2s = new ArrayList<Option<CommunityValue>>();

        reconciler.beforeCommit = () -> {
            w.h.deliver(new ClusterInput.WorkerJoin(W1, w.admit(W1, 21L)));
            w.h.drain();
            fsm2s.add(stored(w.h, "p-a"));
        };
        reconciler.pass();

        assertThat(reconciler.accepted).as("the align mint expected none").startsWith(false);
        assertThat(fsm2s.getFirst().map(CommunityValue::sourceName)).as("a declared community is minted with no source by FSM2 too")
                                                                     .isEqualTo(Option.some(""));
        assertThat(stored(w.h, "p-a")).as("FSM2's mint stands, unchanged").isEqualTo(fsm2s.getFirst());
    }

    // (d) an assignment that lands before a BeginDissolve: the dissolve commits (it witnesses no directive) and the reconciler's next pass Reopens.
    // The late member's directive is committed between the reconciler's read and its commit, as J5 commits it.
    // Red when: the dissolve is not committed over the late member, the next pass does not Reopen, or the member is lost.
    @Test
    void assignThenBeginDissolve_commits_andTheReconcilersNextPassReopens() {
        var h = emptyDerivedCommunityBesideASibling();
        var reconciler = new Reconciler(h).pass().afterTheGrace();

        reconciler.beforeCommit = () -> h.seed(new ActivationDirectiveKey(W1), worker("eu-w-1"));
        reconciler.pass();
        assertThat(stored(h, "eu-w-1").map(CommunityValue::state)).as("step one committed over the late member")
                                                                   .isEqualTo(Option.some(CommunityState.DISSOLVING));
        reconciler.pass();

        assertThat(stored(h, "eu-w-1").map(CommunityValue::state)).as("the reconciler reopened it").isEqualTo(Option.some(CommunityState.FORMING));
        assertThat(h.store.get(new ActivationDirectiveKey(W1)).map(value -> ((ActivationDirectiveValue) value).communityId())).as("the member is kept")
                                                                                                                         .isEqualTo(Option.some("eu-w-1"));
    }
}
