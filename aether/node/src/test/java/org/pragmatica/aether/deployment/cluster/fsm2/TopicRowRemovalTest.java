// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;
import java.util.Set;

import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeDepartureKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.TopicPublisherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.TopicSubscriptionKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureCause;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeDepartureValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.TopicPublisherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.TopicSubscriptionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.TopicTier;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;

/// #1337 (R-CF.15) topic rows beyond the C17 / C17b paths (PR #2832 fix round, v2832 items 1-3):
///
/// - **R-4.3.3** carries the departed node's topic rows: after it the node is no longer `departed`, so C17b never reaches a row it leaves.
/// - **The stale topic-row sweep** (legacy `StaleEntryCleaner.cleanupStaleTopicRows`, `StaleEntryCleaner.java:65-98`, run every pass by
///   `ClusterDeploymentState.sweepStaleEntries`, `:3545-3560`): a committed row whose node is neither a counted core nor a WORKER-directive
///   node, and is neither departing nor departed, is removed at its committed value.
/// - **C17s** (an inferred departure) removes no topic row in its own draft; C17b removes them once the record commits. Legacy removes a node's
///   topic rows only in its node-removal batch (`handleNodeRemoval`, `ClusterDeploymentState.java:3417`, rows at `:3442`), which its own
///   absence inference (the dead restored worker sweep, `:875-892`) also reaches, so the rows of an inferred-absent node go in both engines:
///   in FSM2 one batch later, behind the fenced record (spec C17s "removes nothing", `DepartureFlow.java:37`).
class TopicRowRemovalTest {
    private static final long TOKEN_2 = 0x2222L;
    private static final NodeId WORKER = node("worker-1");
    private static final ResourceAddress ORDERS_TOPIC = ResourceAddress.resourceAddress("org.example", "orders", "1.0.0").unwrap();
    private static final MethodName ON_ORDER = MethodName.methodName("onOrder").unwrap();
    private static final TopicSubscriptionKey SUBSCRIPTION = TopicSubscriptionKey.topicSubscriptionKey(ORDERS_TOPIC, ORDERS, ON_ORDER, CORE_2);
    private static final TopicPublisherKey PUBLISHER = TopicPublisherKey.topicPublisherKey(ORDERS_TOPIC, ORDERS, CORE_2);
    private static final TopicSubscriptionValue SUBSCRIPTION_VALUE = TopicSubscriptionValue.topicSubscriptionValue(CORE_2, TopicTier.ANY);
    private static final TopicPublisherValue PUBLISHER_VALUE = TopicPublisherValue.topicPublisherValue(CORE_2, TopicTier.EPHEMERAL);
    private static final Set<NodeId> ALL_CORES = Set.of(CORE_1, CORE_2, CORE_3);
    private static final Set<NodeId> WITHOUT_CORE_2 = Set.of(CORE_1, CORE_3);
    private static final long TICKS = 120_000L;

    /// core-2 runs ORDERS, has routes, a subscription row and a publisher row; the core membership is wired (all three cores).
    private static MbWorld world() {
        var w = MbWorld.world(CORE_1, CORE_2, CORE_3)
                       .seams(seams -> seams.withCoreView(Option.some(ALL_CORES)));

        w.admit(CORE_1, 0x1111L);
        w.admit(CORE_2, TOKEN_2);
        w.admit(CORE_3, 0x3333L);
        w.target(ORDERS, 1, "CORE_ONLY")
         .directive(CORE_2, ActivationDirectiveValue.core())
         .instance(CORE_2, ORDERS, SliceGoal.ACTIVE, SliceObservedState.ACTIVE, gen(1), TOKEN_2)
         .routes(CORE_2, ORDERS);
        w.h.seedQuietly(SUBSCRIPTION, SUBSCRIPTION_VALUE);
        w.h.seedQuietly(PUBLISHER, PUBLISHER_VALUE);

        return w;
    }

    /// Membership has dropped core-2 (counted cores and core view).
    private static MbWorld withoutCore2(MbWorld w) {
        return w.refreshSeams(seams -> seams.withCountedCores(WITHOUT_CORE_2)
                                            .withCoreView(Option.some(WITHOUT_CORE_2)));
    }

    private static List<KVCommand.Mutation<AetherKey, AetherValue>> staleRowRemovals(MbWorld w) {
        return w.h.submits(SubmitKind.STALE_TOPIC_ROW)
                  .stream()
                  .flatMap(submit -> submit.transaction()
                                           .mutations()
                                           .stream())
                  .toList();
    }

    // -- item 1: R-4.3.3 ---------------------------------------------------------------------------------------------
    /// v2832 P1. Red when: `Directory.removalMutations` drops `DepartureFlow.topicRowRemovals` (the record goes, the node leaves `departed`,
    /// and no C17b reaches the late row).
    @Test
    void recordRemoval_carriesALateTopicRowOfTheDepartedNode_inItsOneTransaction() {
        var w = world().activate();

        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));
        w.h.drain();
        assertThat(w.present(SUBSCRIPTION)).as("control: C17 removed the rows").isFalse();

        var late = TopicPublisherKey.topicPublisherKey(ORDERS_TOPIC, BILLING, CORE_2);
        var lateValue = TopicPublisherValue.topicPublisherValue(CORE_2, TopicTier.EPHEMERAL);

        w.h.seed(late, lateValue);
        w.h.clearLog();
        w.h.seedRemove(new ActivationDirectiveKey(CORE_2));
        w.h.drain();

        var removal = w.h.submits(SubmitKind.DIRECTIVE_REMOVAL);

        assertThat(removal).as("one R-4.3.3 transaction").hasSize(1);
        assertThat(removal.getFirst().transaction().mutations().stream().filter(mutation -> mutation.key().equals(late)).map(mutation -> mutation.expected()))
            .as("the late topic row rides the record removal at its committed value").containsExactly(Option.some(lateValue));
        assertThat(w.present(new NodeDepartureKey(CORE_2))).as("control: R-4.3.3 removed the record").isFalse();
        assertThat(w.present(late)).as("the departed node's late topic row is gone with its record").isFalse();
    }

    // -- item 2: the stale topic-row sweep ---------------------------------------------------------------------------
    /// The CTO's case: core-2 leaves membership while another leader leads, and the batch that should have removed its topic rows is lost
    /// with that leader's term. The next leader rebuilds with the rows in the store, core-2 neither a member nor departing nor departed (no
    /// record), and sweeps them, each at its committed value. Red when: the sweep is not called from the pass, the membership test treats
    /// core-2 as a member, or the removal is unguarded.
    @Test
    void rowsLeftByARemovalBatchLostAcrossALeaderChange_areSweptByTheNextLeader_atTheirCommittedValues() {
        var w = world().activate();

        w.h.deliver(new ClusterInput.Deactivate());
        w.seams(seams -> seams.withCountedCores(WITHOUT_CORE_2)
                              .withCoreView(Option.some(WITHOUT_CORE_2)));
        w.h.seedQuietly(new ActivationDirectiveKey(CORE_2), ActivationDirectiveValue.core());
        w.h.seedRemove(new ActivationDirectiveKey(CORE_2));
        w.h.drain();
        assertThat(List.of(w.present(SUBSCRIPTION), w.present(PUBLISHER))).as("control: the lost batch left both rows").containsExactly(true, true);
        assertThat(w.present(new NodeDepartureKey(CORE_2))).as("control: no record reaches core-2").isFalse();

        w.h.clearLog();
        w.h.activate();
        w.h.advance(TICKS);

        assertThat(staleRowRemovals(w)).as("each row removed at its committed value")
                                       .extracting(mutation -> mutation.key(), mutation -> mutation.expected())
                                       .containsExactlyInAnyOrder(Tuple.tuple(SUBSCRIPTION, Option.some(SUBSCRIPTION_VALUE)),
                                                                  Tuple.tuple(PUBLISHER, Option.some(PUBLISHER_VALUE)));
        assertThat(w.h.submits(SubmitKind.INFERRED_DEPARTURE)).as("control: no departure path removed them").isEmpty();
        assertThat(List.of(w.present(SUBSCRIPTION), w.present(PUBLISHER))).as("the next leader swept both rows").containsExactly(false, false);
    }

    /// v2832 P2: a topic row of core-2 commits after R-4.3.3 removed its record; nothing departure-scoped reaches it, the sweep does.
    @Test
    void aTopicRowCommittedAfterTheRecordRemoval_isSwept() {
        var w = world().activate();

        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));
        w.h.drain();
        withoutCore2(w);
        w.h.seedRemove(new ActivationDirectiveKey(CORE_2));
        w.h.drain();
        assertThat(w.present(new NodeDepartureKey(CORE_2))).as("control: R-4.3.3 removed the record").isFalse();

        var late = TopicSubscriptionKey.topicSubscriptionKey(ORDERS_TOPIC, BILLING, ON_ORDER, CORE_2);

        w.h.seed(late, TopicSubscriptionValue.topicSubscriptionValue(CORE_2, TopicTier.DURABLE));
        assertThat(w.present(late)).as("control: the late row committed").isTrue();
        w.h.advance(TICKS);

        assertThat(w.present(late)).as("the gone node's late topic row is swept").isFalse();
    }

    /// Legacy `coreMembershipResolved` (`ClusterDeploymentState.java:1614`): with the core view unwired nothing is swept. Red when: the gate is
    /// dropped (an unwired membership would read every row as stale).
    @Test
    void anUnwiredCoreView_sweepsNothing() {
        var w = world().activate();

        w.refreshSeams(seams -> seams.withCountedCores(WITHOUT_CORE_2)
                                     .withCoreView(Option.none()));
        w.h.seedQuietly(new ActivationDirectiveKey(CORE_2), ActivationDirectiveValue.core());
        w.h.clearLog();
        w.h.advance(TICKS);

        assertThat(staleRowRemovals(w)).isEmpty();
        assertThat(List.of(w.present(SUBSCRIPTION), w.present(PUBLISHER))).containsExactly(true, true);
    }

    /// Legacy `placementNodes` (`ClusterDeploymentState.java:1600`): a counted core's rows and a WORKER-directive node's rows stay. Red when:
    /// either arm of the membership test is dropped.
    @Test
    void theRowsOfACountedCore_andOfAWorker_stay() {
        var w = world().directive(WORKER, ActivationDirectiveValue.worker())
                       .activate();
        var workerRow = TopicSubscriptionKey.topicSubscriptionKey(ORDERS_TOPIC, BILLING, ON_ORDER, WORKER);

        w.h.seed(workerRow, TopicSubscriptionValue.topicSubscriptionValue(WORKER, TopicTier.ANY));
        w.h.clearLog();
        w.h.advance(TICKS);

        assertThat(staleRowRemovals(w)).isEmpty();
        assertThat(List.of(w.present(SUBSCRIPTION), w.present(PUBLISHER), w.present(workerRow))).containsExactly(true, true, true);
    }

    /// A departing node's rows belong to its C17 draft. Red when: the sweep stops excluding `departing` (it would race the departure batch).
    @Test
    void aDepartingNodesRows_areLeftToItsDepartureDraft() {
        var w = world().activate();

        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));
        withoutCore2(w);
        w.h.advance(TICKS);

        assertThat(w.h.model().isDeparting(CORE_2)).as("control: still departing").isTrue();
        assertThat(staleRowRemovals(w)).isEmpty();
    }

    /// A departed node's rows belong to C17b (which keeps W3' and G-4). Red when: the sweep stops excluding `departed`.
    @Test
    void aDepartedNodesRows_areLeftToC17b() {
        var w = world().activate();

        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.h.seed(new NodeDepartureKey(CORE_2),
                 new NodeDepartureValue(DepartureState.DEPARTED, 1L, process(TOKEN_2), Option.some(DepartureCause.STALE), w.h.wallMs));
        withoutCore2(w);
        w.h.advance(TICKS);

        assertThat(w.h.model().isDeparted(CORE_2)).as("control: departed").isTrue();
        assertThat(w.h.submits(SubmitKind.CLEANUP)).as("control: C17b took the rows").isNotEmpty();
        assertThat(staleRowRemovals(w)).isEmpty();
    }

    /// In flight a removal is not sent again: further passes before it settles derive nothing. Red when: the in-flight filter is dropped.
    @Test
    void aStaleRowRemovalInFlight_isNotSentAgain() {
        var w = world().activate();

        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.h.seedQuietly(new ActivationDirectiveKey(CORE_2), ActivationDirectiveValue.core());
        w.h.seedRemove(new ActivationDirectiveKey(CORE_2));
        withoutCore2(w);
        w.h.deliver(new ClusterInput.Reconcile());
        w.h.deliver(new ClusterInput.Reconcile());
        w.h.drain();

        assertThat(w.h.held.values().stream().filter(submit -> submit.kind() == SubmitKind.STALE_TOPIC_ROW)).as("control: the first removals are still in flight")
                                                                                                     .hasSize(2);
        assertThat(staleRowRemovals(w)).extracting(mutation -> mutation.key())
                                       .containsExactlyInAnyOrder(SUBSCRIPTION, PUBLISHER);
    }

    /// A refused removal is not resubmitted at once; the next pass derives it again from the mirror. Red when: `Outbox.rederive` routes
    /// STALE_TOPIC_ROW to the per-pair resubmission.
    @Test
    void aRefusedStaleRowRemoval_isDerivedAgainByTheNextPass_notResubmittedAtOnce() {
        var w = world().activate();

        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.h.seedQuietly(new ActivationDirectiveKey(CORE_2), ActivationDirectiveValue.core());
        w.h.seedRemove(new ActivationDirectiveKey(CORE_2));
        w.h.clearLog();
        withoutCore2(w);
        w.h.drain();

        var first = w.h.submits(SubmitKind.STALE_TOPIC_ROW);

        assertThat(first).as("control: both rows submitted").hasSize(2);
        w.h.held.clear();
        first.forEach(submit -> w.h.settleHeld(submit, w.h.refused(submit)));
        assertThat(w.h.submits(SubmitKind.STALE_TOPIC_ROW)).as("not resubmitted at the refusal").hasSize(2);

        w.h.policy = FsmHarness.SettlePolicy.APPLY;
        w.h.advance(TICKS);

        assertThat(List.of(w.present(SUBSCRIPTION), w.present(PUBLISHER))).as("the next pass derived them again").containsExactly(false, false);
    }

    // -- item 3: C17s ------------------------------------------------------------------------------------------------
    /// v2832 mutant v3. Red when: the inferred departure's draft also removes topic rows (`mutations.addAll(topicRowRemovals(...))` moved out
    /// of `if (!inferred)`): spec C17s removes nothing, so a refused record never takes a live node's rows. Once the record commits, C17b
    /// removes them, the end state legacy's node-removal batch reaches.
    @Test
    void anInferredDeparture_removesNoTopicRowInItsDraft_andC17bRemovesThemOnceTheRecordCommits() {
        var w = world().seams(seams -> seams.withCoreView(Option.some(WITHOUT_CORE_2)))
                       .activate();
        var bound = ms(Fixtures.bounds().coreRemoval());

        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.h.advanceBySeconds(bound / 1000 + 61);

        var inferred = w.h.submits(SubmitKind.INFERRED_DEPARTURE);

        assertThat(inferred).as("control: C17s recorded core-2").isNotEmpty();
        assertThat(inferred.stream()
                           .flatMap(submit -> submit.transaction().mutations().stream())
                           .map(mutation -> mutation.key()))
            .as("C17s removes nothing: no topic row in its draft").doesNotContain(SUBSCRIPTION, PUBLISHER);
        assertThat(List.of(w.present(SUBSCRIPTION), w.present(PUBLISHER))).as("the rows stay while the record is unsettled").containsExactly(true, true);

        w.h.policy = FsmHarness.SettlePolicy.APPLY;
        List.copyOf(w.h.held.keySet()).forEach(w.h::applyHeld);
        w.h.drain();
        w.h.advance(TICKS);

        assertThat(w.recordOf(CORE_2).map(NodeDepartureValue::state)).as("control: the record committed").isEqualTo(Option.some(DepartureState.DEPARTED));
        assertThat(List.of(w.present(SUBSCRIPTION), w.present(PUBLISHER))).as("C17b removed them after the record").containsExactly(false, false);
    }
}
