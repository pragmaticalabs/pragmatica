// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Set;
import java.util.function.UnaryOperator;

import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.slice.kvstore.AetherKey.ArtifactJudgmentKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaStatus;
import org.pragmatica.lang.Option;

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
}
