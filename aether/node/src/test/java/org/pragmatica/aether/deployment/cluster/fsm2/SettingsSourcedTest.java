// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.membership.MembershipConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey.ArtifactJudgmentKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaStatus;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.SlFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;


/// What the cluster FSM takes from its `Settings` (spec R-3.4.10, MQ-7, P3-Q12, P3-Q16) and what it routes (H-4 (a)): the submission expiry,
/// the divergence timeouts, the process that qualifies a correlation id, and the enriched `ActivationBlocked`.
class SettingsSourcedTest {
    private static FsmHarness harness(UnaryOperator<Settings> change) {
        var h = new FsmHarness(change.apply(Settings.settings(FsmHarness.SELF,
                                                              DeploymentAtomicity.ALL_OR_NOTHING,
                                                              Set.of(),
                                                              bounds())));
        var cores = Set.of(CORE_1, CORE_2, CORE_3);

        h.commitLeader(FsmHarness.SELF, 1L);
        h.seams = Seams.UNWIRED.withCountedCores(cores).withReady(cores);

        return h;
    }

    // -- MQ-7: Settings.submissionExpiry ---------------------------------------------------------------------------------
    /// A fresh leader whose goal submissions are all lost (#2143).
    private static FsmHarness withLostSubmissions(UnaryOperator<Settings> change) {
        var h = harness(change);

        seedTarget(h, ORDERS, 1, 1);
        h.policy = FsmHarness.SettlePolicy.LOSE;
        h.activate();

        return h;
    }

    // Red when: a lost submission is released after `departingReread` (30 s) instead of `submissionExpiry` (default applyTimeout 30 s + 5 s): the
    // FSM would release a batch the consensus engine still owns.
    @Test
    void lostSubmission_isReleasedAtTheSubmissionExpiry_notAtTheDepartingReread() {
        var h = withLostSubmissions(s -> s);

        assertThat(h.settings.submissionExpiry()).as("applyTimeout 30 s + 5 s").isEqualTo(35_000L);
        assertThat(h.model().submittedCount()).as("precondition: lost, so in flight").isPositive();
        h.advance(34_000L);
        h.deliver(new ClusterInput.Reconcile());
        assertThat(h.metricTotal("aether_deployment_fsm_submissions_total", tags -> "lost".equals(tags.get("outcome"))))
                  .as("one consensus timeout (30 s) is not enough")
                  .isZero();
        h.advance(2_000L);
        h.deliver(new ClusterInput.Reconcile());
        assertThat(h.metricTotal("aether_deployment_fsm_submissions_total", tags -> "lost".equals(tags.get("outcome"))))
                  .as("released once the expiry has passed")
                  .isPositive();
    }

    // Red when: the expiry is not read from Settings: a configured `applyTimeout` above 30 s is ignored.
    @Test
    void lostSubmission_followsAConfiguredSubmissionExpiry() {
        var h = withLostSubmissions(s -> s.withSubmissionExpiry(95_000L));

        h.advance(94_000L);
        h.deliver(new ClusterInput.Reconcile());
        assertThat(h.metricTotal("aether_deployment_fsm_submissions_total", tags -> "lost".equals(tags.get("outcome")))).isZero();
        h.advance(2_000L);
        h.deliver(new ClusterInput.Reconcile());
        assertThat(h.metricTotal("aether_deployment_fsm_submissions_total", tags -> "lost".equals(tags.get("outcome")))).isPositive();
    }

    // -- P3-Q12: the divergence timeouts ------------------------------------------------------------------------------------
    // Red when: `timeoutOf` ignores the configured bounds for any of the four expected next steps.
    @Test
    void divergenceTimeouts_comeFromTheBounds() {
        var bounds = DivergenceBounds.divergenceBounds(11_000L, 22_000L, 33_000L, 44_000L);
        var loading = Option.some(row(SliceObservedState.LOADING, 1L, gen(1), process(7)));
        var loaded = Option.some(row(SliceObservedState.LOADED, 1L, gen(1), process(7)));
        var active = Option.some(row(SliceObservedState.ACTIVE, 1L, gen(1), process(7)));

        assertThat(Divergence.timeoutOf(bounds, SliceGoal.ACTIVE, loading)).as("loading").isEqualTo(11_000L);
        assertThat(Divergence.timeoutOf(bounds, SliceGoal.LOADED, loading)).as("loading towards LOADED").isEqualTo(11_000L);
        assertThat(Divergence.timeoutOf(bounds, SliceGoal.ACTIVE, loaded)).as("activating").isEqualTo(22_000L);
        assertThat(Divergence.timeoutOf(bounds, SliceGoal.LOADED, active)).as("deactivating").isEqualTo(33_000L);
        assertThat(Divergence.timeoutOf(bounds, SliceGoal.UNLOADED, loaded)).as("unloading").isEqualTo(44_000L);
        assertThat(DivergenceBounds.DEFAULT).as("the defaults are the existing config defaults").isEqualTo(new DivergenceBounds(120_000L,
                                                                                                                                   90_000L,
                                                                                                                                   30_000L,
                                                                                                                                   120_000L));
    }

    private static boolean retracted(FsmHarness h) {
        return h.submits()
                .stream()
                .anyMatch(s -> s.transaction()
                                .mutations()
                                .stream()
                                .anyMatch(m -> m.key() instanceof ArtifactJudgmentKey));
    }

    // Red when: the FSM times a stuck DEACTIVATING row with the compiled-in 30 s instead of the configured bound: with T = 5 s the retraction comes
    // after 3T = 15 s, not 90 s.
    @Test
    void divergence_retractsAtThreeTimesTheConfiguredBound() {
        var h = harness(s -> s.withDivergence(DivergenceBounds.divergenceBounds(120_000L, 90_000L, 5_000L, 120_000L)));

        seedTarget(h, ORDERS, 1, 1);
        seedGoal(h, CORE_1, ORDERS, SliceGoal.LOADED, G1);
        seedRow(h, CORE_1, ORDERS, SliceObservedState.DEACTIVATING, G1, 0L);
        h.activate();
        h.advance(15_000L);
        h.deliver(new ClusterInput.Reconcile());
        assertThat(retracted(h)).as("not at exactly 3T").isFalse();
        h.advance(1L);
        h.deliver(new ClusterInput.Reconcile());
        assertThat(retracted(h)).as("strictly after 3T of the configured bound").isTrue();
    }

    // -- P3-Q16: the process qualifies a correlation id -----------------------------------------------------------------------
    private static String firstCorrelation(ProcessIncarnation process) {
        var h = harness(s -> s.withProcess(process));

        seedTarget(h, ORDERS, 1, 1);
        h.policy = FsmHarness.SettlePolicy.LOSE;
        h.activate();

        return h.submits().getFirst().correlationId();
    }

    // Red when: two leader processes at the same incarnation and sequence mint the same transaction id (the id carries no process), or the id does
    // not name the process it was minted in.
    @Test
    void correlationId_isQualifiedByTheProcess() {
        var first = firstCorrelation(new ProcessIncarnation(1_760_000_000_001L, 0xAAAAL));
        var second = firstCorrelation(new ProcessIncarnation(1_760_000_000_002L, 0xBBBBL));

        assertThat(first).isNotEqualTo(second);
        assertThat(first).startsWith("c1760000000001." + 0xAAAAL + "-");
        assertThat(second).startsWith("c1760000000002." + 0xBBBBL + "-");
        assertThat(first.substring(first.indexOf('-'))).as("the incarnation and the sequence are the same in both processes")
                  .isEqualTo(second.substring(second.indexOf('-')));
    }

    // Red when: ids of one process are not unique (the sequence stops advancing).
    @Test
    void correlationId_isUniqueWithinOneProcess() {
        var h = harness(s -> s.withProcess(new ProcessIncarnation(5L, 6L)));

        seedTarget(h, ORDERS, 1, 1);
        seedTarget(h, BILLING, 1, 1);
        h.policy = FsmHarness.SettlePolicy.LOSE;
        h.activate();

        assertThat(h.submits()).hasSizeGreaterThan(1);
        assertThat(h.submits().stream().map(ClusterEffect.Submit::correlationId).distinct().count()).isEqualTo(h.submits().size());
    }

    // -- H-4 (a): the enriched ActivationBlocked ------------------------------------------------------------------------------
    // Red when: the routed event drops what the indexed SchemaVersionValue knows: the owning blueprint as a typed id, the artifact coordinates, the
    // attempt count; or the string form of the owner (`owner()`) disagrees with the typed id.
    @Test
    void activationBlocked_carriesTheOwningBlueprint_theCoordinatesAndTheAttemptCount() {
        var h = harness(s -> s);
        var owner = blueprintId("org.example:app:1.0.0");

        h.activate();
        publish(h, blueprint("org.example:app:1.0.0", false, ATTEMPT, slice(ORDERS, 1, 1, Set.of())), Option.none());
        h.seed(new SchemaVersionKey("orders-db"),
               new SchemaVersionValue("orders-db", 1, "V1", SchemaStatus.FAILED, "org.example:migrations:1.0.0", owner, 3, FsmHarness.START_WALL));
        h.drain();
        var blocked = h.effects(ClusterEffect.Route.class)
                       .stream()
                       .map(ClusterEffect.Route::event)
                       .filter(RoutedEvent.ActivationBlocked.class::isInstance)
                       .map(RoutedEvent.ActivationBlocked.class::cast)
                       .toList();

        assertThat(blocked).hasSize(1);
        assertThat(blocked.getFirst().datasource()).isEqualTo("orders-db");
        assertThat(blocked.getFirst().owningBlueprint()).isEqualTo(owner);
        assertThat(blocked.getFirst().owner()).isEqualTo(owner.asString());
        assertThat(blocked.getFirst().artifactCoords()).isEqualTo("org.example:migrations:1.0.0");
        assertThat(blocked.getFirst().attemptCount()).isEqualTo(3);
        assertThat(blocked.getFirst().slices()).contains(ORDERS.asString());
    }

    // Red when: the default settings carry a process: the unknown process (0, 0) is the default, so an id minted without one is `c0.0-<inc>-<seq>`.
    @Test
    void correlationId_defaultsToTheUnknownProcess() {
        var h = harness(s -> s);

        seedTarget(h, ORDERS, 1, 1);
        h.policy = FsmHarness.SettlePolicy.LOSE;
        h.activate();

        assertThat(h.submits().getFirst().correlationId()).startsWith("c0.0-1-");
    }

    // Red when: any `with*` of Settings drops a field it does not set (the fields added for the process, the divergence timeouts and the submission
    // expiry are carried by every copy).
    @Test
    void withers_keepEveryOtherField() {
        var timing = Timing.DEFAULT.withTick(12_345L);
        var divergence = DivergenceBounds.divergenceBounds(1L, 2L, 3L, 4L);
        var process = new ProcessIncarnation(7L, 8L);
        var settings = Settings.settings(FsmHarness.SELF, DeploymentAtomicity.BEST_EFFORT, Set.of(CORE_1), bounds())
                               .withTiming(timing)
                               .withCommunitySizing(7, 2)
                               .withDivergence(divergence)
                               .withSubmissionExpiry(99_000L)
                               .withProcess(process)
                               .withJitter(_ -> 0.25d);

        assertThat(settings.timing()).isEqualTo(timing);
        assertThat(settings.communityTargetSize()).isEqualTo(7);
        assertThat(settings.communityViabilityFloor()).isEqualTo(2);
        assertThat(settings.divergence()).isEqualTo(divergence);
        assertThat(settings.submissionExpiry()).isEqualTo(99_000L);
        assertThat(settings.process()).isEqualTo(process);
        assertThat(settings.jitter().applyAsDouble("x")).isEqualTo(0.25d);
        assertThat(settings.atomicity()).isEqualTo(DeploymentAtomicity.BEST_EFFORT);
        assertThat(settings.seedNodes()).containsExactly(CORE_1);
        assertThat(settings.bounds()).isEqualTo(bounds());
    }

    // Red when: the suspicion window is shorter than the worst case a dead core stays listed (any term of the lag dropped), or B_rm stops being
    // the window itself (each wait counted once: splitTimeout is already in the lag); the worker removal bound (170 s) and the announcement freshness (60 s) move at the real defaults; or the
    // factory scrambles its arguments.
    @Test
    void absenceBounds_deriveTheSuspicionWindow_andTheBoundsOnTopOfIt() {
        var bounds = bounds();

        assertThat(bounds.coreSuspicion()).as("covers the worst case a dead core stays listed").isGreaterThanOrEqualTo(worstCaseListedNanos(FIXTURE_MAX_CORES));
        assertThat(bounds.coreRemoval()).as("B_rm = the window, splitTimeout counted once (in the lag)").isEqualTo(bounds.coreSuspicion());
        assertThat(ms(bounds.workerRemoval())).as("B_w").isEqualTo(170_000L);
        assertThat(ms(bounds.announcementFreshness())).as("S_ann").isEqualTo(60_000L);
        var built = AbsenceBounds.absenceBounds(1L, 2L, 3L, 4L).unwrap();

        assertThat(List.of(built.coreRemoval(), built.workerRemoval(), built.announcementFreshness(), built.coreSuspicion()))
                  .as("the factory keeps its arguments in order").containsExactly(1L, 2L, 3L, 4L);
        assertThat(built).isEqualTo(AbsenceBounds.absenceBounds(1L, 2L, 3L, 4L).unwrap());
    }

    // Red when: an admission does not carry the process it was built with, or `withProcess` keeps the old one.
    @Test
    void admission_carriesTheProcess() {
        var admission = Admission.admission(process(0x77L));

        assertThat(admission.process()).isEqualTo(process(0x77L));
        assertThat(admission.token()).isEqualTo(0x77L);
        assertThat(admission.withProcess(process(0x88L)).process()).isEqualTo(process(0x88L));
        assertThat(Admission.UNKNOWN.tokenKnown()).isFalse();
    }

    // Red when: the expiry of an `unresponsive` exclusion is timed with the compiled-in 10 x 120 s instead of 10 x the configured bound.
    @Test
    void unresponsiveExclusion_expiresAtTenTimesTheConfiguredBound() {
        var h = harness(s -> s.withDivergence(DivergenceBounds.divergenceBounds(12_000L, 90_000L, 30_000L, 120_000L)));

        seedTarget(h, ORDERS, 1, 1);
        h.activate();
        h.advance(3 * 12_000L + 31_000L);
        assertThat(h.store.get(new ArtifactJudgmentKey(ORDERS)).isPresent()).as("precondition: retracted after 3T of the configured bound").isTrue();
        h.advance(10 * 12_000L - 60_000L);
        assertThat(h.warns("slice-unresponsive-expired")).as("not before 10T").isEmpty();
        h.advance(2 * 60_000L);
        assertThat(h.warns("slice-unresponsive-expired")).as("10 x the configured bound, long before 10 x 120 s").isNotEmpty();
    }

    // Red when: any `with*` of Settings loses a field that an earlier call set: every wither is applied last, after all the others.
    @Test
    void withers_keepEveryOtherField_whicheverIsAppliedLast() {
        var timing = Timing.DEFAULT.withTick(12_345L);
        var divergence = DivergenceBounds.divergenceBounds(1L, 2L, 3L, 4L);
        var process = new ProcessIncarnation(7L, 8L);
        var all = List.<UnaryOperator<Settings>> of(s -> s.withTiming(timing),
                                                     s -> s.withCommunitySizing(7, 2),
                                                     s -> s.withDivergence(divergence),
                                                     s -> s.withSubmissionExpiry(99_000L),
                                                     s -> s.withProcess(process),
                                                     s -> s.withJitter(_ -> 0.25d));

        for (var last = 0; last < all.size(); last++) {
            var settings = Settings.settings(FsmHarness.SELF, DeploymentAtomicity.ALL_OR_NOTHING, Set.of(), bounds());

            for (var i = 0; i < all.size(); i++) {
                if (i != last) {
                    settings = all.get(i).apply(settings);
                }
            }

            settings = all.get(last).apply(settings);
            assertThat(settings.timing()).as("timing, last=" + last).isEqualTo(timing);
            assertThat(settings.communityTargetSize()).as("target size, last=" + last).isEqualTo(7);
            assertThat(settings.communityViabilityFloor()).as("floor, last=" + last).isEqualTo(2);
            assertThat(settings.divergence()).as("divergence, last=" + last).isEqualTo(divergence);
            assertThat(settings.submissionExpiry()).as("expiry, last=" + last).isEqualTo(99_000L);
            assertThat(settings.process()).as("process, last=" + last).isEqualTo(process);
            assertThat(settings.jitter().applyAsDouble("x")).as("jitter, last=" + last).isEqualTo(0.25d);
        }
    }

    // Red when: an absence bounds record can be built with a suspicion window that is not positive (W5): a window of 0 counts a core as alive the moment
    // it is first listed, so a Rebuild snapshot alone would promote it at C1.
    @Test
    void absenceBounds_refuseAWindowThatIsNotPositive() {
        assertThat(AbsenceBounds.absenceBounds(1L, 2L, 3L, 0L).isFailure()).as("zero").isTrue();
        assertThat(AbsenceBounds.absenceBounds(1L, 2L, 3L, -1L).isFailure()).as("negative").isTrue();
        assertThat(AbsenceBounds.absenceBounds(1L, 2L, 3L, 1L).isSuccess()).as("the smallest legal window").isTrue();
        assertThat(AbsenceBounds.derive(TimeSpan.timeSpan(0).seconds(),
                                        TimeSpan.timeSpan(5).seconds(),
                                        8,
                                        3.0,
                                        TimeSpan.timeSpan(30).seconds(),
                                        TimeSpan.timeSpan(60).seconds(),
                                        TimeSpan.timeSpan(0).seconds())
                                .isFailure()).as("a zero suspect timeout derives a zero window").isTrue();
    }

    // Red when: AbsenceBounds' value equality ignores a field, the suspicion window included (v-2190 delta, N-b). `Settings` is a record, so its
    // equality (and `settings_*`'s `isEqualTo(bounds())`) reads this one; bounds that differ only in the window must differ.
    @Test
    void absenceBounds_equalityCoversEveryBound() {
        var base = AbsenceBounds.absenceBounds(1L, 2L, 3L, 4L).unwrap();

        assertThat(base).isEqualTo(AbsenceBounds.absenceBounds(1L, 2L, 3L, 4L).unwrap());
        assertThat(base.hashCode()).isEqualTo(AbsenceBounds.absenceBounds(1L, 2L, 3L, 4L).unwrap().hashCode());
        assertThat(base).as("coreRemoval").isNotEqualTo(AbsenceBounds.absenceBounds(9L, 2L, 3L, 4L).unwrap());
        assertThat(base).as("workerRemoval").isNotEqualTo(AbsenceBounds.absenceBounds(1L, 9L, 3L, 4L).unwrap());
        assertThat(base).as("announcementFreshness").isNotEqualTo(AbsenceBounds.absenceBounds(1L, 2L, 9L, 4L).unwrap());
        assertThat(base).as("coreSuspicion").isNotEqualTo(AbsenceBounds.absenceBounds(1L, 2L, 3L, 9L).unwrap());
        assertThat(base.hashCode()).as("hashCode reads coreSuspicion").isNotEqualTo(AbsenceBounds.absenceBounds(1L, 2L, 3L, 9L).unwrap().hashCode());
    }

    // Red when: an absence bounds value can be built without the Result factory (W5 on every path, v-2190 N3, CTO ruling 2026-10-14): the type must be
    // final and every constructor private, so `absenceBounds`/`derive`, which refuse a non-positive window, are the only construction path.
    @Test
    void absenceBounds_hasNoConstructionPathButTheFactory() {
        assertThat(Modifier.isFinal(AbsenceBounds.class.getModifiers())).as("final: no subclass constructor").isTrue();
        assertThat(AbsenceBounds.class.getDeclaredConstructors()).as("every constructor is private")
                  .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()))
                  .isNotEmpty();
        assertThat(AbsenceBounds.class.isRecord()).as("a record's canonical constructor cannot be private").isFalse();
    }
}
