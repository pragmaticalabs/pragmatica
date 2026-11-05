// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.CommunityKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentApplyKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentOutcomeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.VersionRoutingKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ArtifactJudgmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.VersionRoutingValue;
import org.pragmatica.aether.slice.kvstore.CommunityState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.aether.slice.generation.Epoch;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind.TARGETS;
import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.SlFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;


/// Target intake and loss (NT-11), rollback (NT-25, NT-26) and community states (NT-42), on the real applier.
class SlBlueprintTest {
    private static final String APP = "org.example:app:1.0.0";

    @Nested
    class Targets {
        /// NT-11 control. Red when: a lost target put is not re-submitted once the awaited-target bound has passed.
        @Test
        void lostTargetPut_isResubmittedAfterTheBound() {
            var h = threeCores();

            h.activate();
            h.policy = FsmHarness.SettlePolicy.LOSE;
            h.seed(new AppBlueprintKey(blueprintId(APP)), blueprint(APP, false, ATTEMPT, slice(ORDERS, 1, 1, Set.of())));
            h.drain();
            var first = h.submits(TARGETS).size();

            h.advanceBySeconds(160);
            assertThat(first).isEqualTo(1);
            assertThat(h.submits(TARGETS)).as("re-submitted once").hasSize(2);
            assertThat(h.warns("slice-start-awaiting-target")).hasSize(1);
        }

        /// NT-11. Red when: a foreign target remove, followed by the bound and 1 ms, re-submits a target.
        @Test
        void foreignTargetRemove_isNotResubmitted() {
            var h = threeCores();

            h.activate();
            h.policy = FsmHarness.SettlePolicy.LOSE;
            h.seed(new AppBlueprintKey(blueprintId(APP)), blueprint(APP, false, ATTEMPT, slice(ORDERS, 1, 1, Set.of())));
            h.drain();
            h.seedRemove(new SliceTargetKey(ORDERS.base()));
            h.drain();
            h.advanceBySeconds(160);
            assertThat(h.submits(TARGETS)).as("no new target put after the foreign remove").hasSize(1);
        }
    }

    @Nested
    class Rollback {
        private FsmHarness midApplyOnANewLeader(boolean withPrevious) {
            var h = threeCores();
            var v1 = blueprint(APP, false, "attempt-1", slice(ORDERS, 1, 1, Set.of()));
            var v2 = blueprint(APP, false, "attempt-2", slice(ORDERS, 1, 1, Set.of()), slice(BILLING, 1, 1, Set.of()));

            h.activate();
            publish(h, v1, Option.none());
            node(h, hosts(h, ORDERS).getFirst(), 1L).follows(ORDERS);
            h.drain();
            node(h, hosts(h, ORDERS).getFirst(), 1L).follows(ORDERS);
            h.drain();
            h.seedRemove(new DeploymentOutcomeKey(blueprintId(APP)));
            publish(h,
                    v2,
                    withPrevious
                    ? Option.some(v1)
                    : Option.none());
            h.deliver(new ClusterInput.Deactivate());
            h.activate();

            return h;
        }

        /// NT-25. Red when: after a leader change a fatal report deletes the blueprint instead of restoring `previous`; or the
        /// outcome is not ROLLED_BACK; or the apply record stays.
        @Test
        void fatalReport_afterALeaderChange_restoresPrevious() {
            var h = midApplyOnANewLeader(true);
            var billingHost = hosts(h, BILLING).getFirst();

            node(h, billingHost, 3L).fails(BILLING, true);
            h.drain();
            var id = blueprintId(APP);
            var restored = h.store.get(new AppBlueprintKey(id));

            assertThat(restored.isPresent()).as("restored, not deleted").isTrue();
            assertThat(restored.map(v -> ((AppBlueprintValue) v).blueprint()
                                                                .loadOrder()
                                                                .size())).as("the one-slice previous")
                      .isEqualTo(Option.some(1));
            assertThat(restored.map(v -> ((AppBlueprintValue) v).attemptId())).as("carries the rolled-back attempt's id")
                      .isEqualTo(Option.some("attempt-2"));
            assertThat(h.store.get(new DeploymentOutcomeKey(id)).map(v -> ((DeploymentOutcomeValue) v).status())).isEqualTo(Option.some(DeploymentOutcomeStatus.ROLLED_BACK));
            assertThat(h.store.get(new DeploymentApplyKey(id)).isPresent()).as("the apply record is garbage collected")
                      .isFalse();
            assertThat(h.model().judgment(BILLING).map(ArtifactJudgmentValue::permanentFailure)).as("the permanent mark rides the failure")
                      .isEqualTo(Option.some(true));
            assertThat(h.store.get(new SliceTargetKey(BILLING.base())).isPresent()).as("the added slice's target goes")
                      .isFalse();
        }

        /// NT-25 control. Red when: with no `previous` the blueprint is not removed.
        @Test
        void fatalReport_withNoPrevious_deletesTheBlueprint() {
            var h = midApplyOnANewLeader(false);

            node(h, hosts(h, BILLING).getFirst(), 3L).fails(BILLING, true);
            h.drain();
            assertThat(h.store.get(new AppBlueprintKey(blueprintId(APP))).isPresent()).isFalse();
        }

        /// NT-26. Red when: a fatal report during a rolling update submits a rollback.
        @Test
        void fatalReport_duringARollingUpdate_submitsNoRollback() {
            var h = threeCores();

            h.activate();
            publish(h,
                    blueprint(APP, false, ATTEMPT, slice(ORDERS, 1, 1, Set.of()), slice(BILLING, 1, 1, Set.of())),
                    Option.none());
            h.seed(new VersionRoutingKey(BILLING.base()),
                   new VersionRoutingValue(BILLING.version(), BILLING.version(), 50, 50, h.wallMs));
            h.drain();
            node(h, hosts(h, BILLING).getFirst(), 3L).fails(BILLING, true);
            h.drain();
            assertThat(h.submits(ClusterModel.SubmitKind.ROLLBACK)).isEmpty();
            assertThat(h.model().judgment(BILLING).map(ArtifactJudgmentValue::permanentFailure)).as("the failure is still recorded")
                      .isEqualTo(Option.some(true));
            assertThat(h.store.get(new AppBlueprintKey(blueprintId(APP))).isPresent()).isTrue();
        }
    }

    @Nested
    class Communities {
        private static final NodeId W1 = node("worker-1");
        private static final NodeId W2 = node("worker-2");
        private static final NodeId W3 = node("worker-3");

        private FsmHarness formingCommunityOfThree() {
            var h = harness(CORE_1);

            h.seams = h.seams.withLocalAlive(Option.some(Set.of(W1, W2, W3)));
            h.seedQuietly(new CommunityKey("c"), community("default", 3, CommunityState.FORMING));
            for (var w : List.of(W1, W2, W3)) {
                h.seedQuietly(new ActivationDirectiveKey(w), worker("c"));
            }

            h.seedQuietly(new GovernorAnnouncementKey("c"), roster(W1, false, W1, W2, W3));

            return h;
        }

        /// NT-42. Red when: a FORMING community that reached the viability floor is not written ACTIVE.
        @Test
        void forming_becomesActive_atTheFloor() {
            var h = formingCommunityOfThree();

            h.activate();
            h.advanceBySeconds(1);
            h.advance(31_000L);
            assertThat(h.store.get(new CommunityKey("c")).map(v -> ((CommunityValue) v).state())).isEqualTo(Option.some(CommunityState.ACTIVE));
        }

        /// NT-42. Red when: an UNKNOWN community is written.
        @Test
        void unknown_isNeverWritten() {
            var h = formingCommunityOfThree();

            h.seedQuietly(new CommunityKey("c"), community("default", 3, CommunityState.UNKNOWN));
            h.activate();
            h.advance(61_000L);
            assertThat(h.submits(ClusterModel.SubmitKind.COMMUNITY)).isEmpty();
        }

        /// NT-42. Red when: a refused community transaction is not re-evaluated at once (no new COMMUNITY transaction follows the
        /// refusal, and the community does not become ACTIVE).
        @Test
        void refusedTransaction_isReevaluated() {
            var h = formingCommunityOfThree();

            h.policy = FsmHarness.SettlePolicy.HOLD;
            h.activate();
            var first = h.submits(ClusterModel.SubmitKind.COMMUNITY).getFirst();

            h.seed(new GovernorAnnouncementKey("c"),
                   roster(W1, false, W1, W2, W3).withMembers(List.of(W1, W2, W3), "10.0.0.2:9000", Epoch.ZERO));
            h.drain();
            var second = h.submits(ClusterModel.SubmitKind.COMMUNITY).getLast();
            var before = h.submits(ClusterModel.SubmitKind.COMMUNITY).size();

            h.held.remove(first.correlationId());
            h.settleHeld(first, h.applyTransaction(first));
            assertThat(h.submits(ClusterModel.SubmitKind.COMMUNITY)).as("the refusal is re-evaluated")
                      .hasSize(before + 1);
            h.held.remove(second.correlationId());
            h.settleHeld(second, h.applyTransaction(second));
            assertThat(h.store.get(new CommunityKey("c")).map(v -> ((CommunityValue) v).state())).isEqualTo(Option.some(CommunityState.ACTIVE));
        }
    }
}
