// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.update;

import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;

import static org.assertj.core.api.Assertions.assertThat;

/// #982 — the A/B writer pinned `minInstances = 1` on all three lifecycle writes and nothing ever
/// put the operator's floor back, so a configured floor of 5 became a permanent effective 1.
///
/// The floor is the operator's availability guarantee and is read in two places only, both of them
/// reductions: `SliceAllocationEngine.issueScaleDownCommands` caps removals at
/// `activeCount - minInstances`, and `DecisionTreeController` refuses a scale-down unless
/// `instances > minInstances`. Nothing raises a slice to its floor, so a clobbered floor is silent —
/// the cluster simply runs with a lower minimum than configured, and reports nothing at all.
///
/// Masked until #936. The autoscaler's scale-down ratchet pushed `minInstances` back up to the
/// running count on the next scale, so the clobber could not be observed; removing the ratchet left
/// the floor genuinely at 1. The A/B writer's behaviour was never changed by #936 — only the
/// consequence became reachable.
///
/// **THE FEEDBACK STEP IS LOAD-BEARING, AND MEASURED TO BE — against a PARTIAL fix, not against the
/// original defect.** `observe(...)` returns the A/B writer's own output to the store as consensus
/// does, so the next lifecycle write reads a clobbered record rather than the pristine seed. All four
/// arms were run:
///
/// | production code | feedback | conclusion tests |
/// |---|---|---|
/// | pre-fix (pins both counts unconditionally) | present | RED |
/// | pre-fix | absent | RED |
/// | partial fix (canary clobbers, conclusion carries the observed value) | present | RED |
/// | partial fix | absent | **GREEN — defect missed** |
///
/// So the feedback is NOT what makes these tests see the original defect: that pin is unconditional,
/// and reddens with or without it. What the feedback defends against is the partial fix #982's own
/// "suggested direction" invites — preserve the *observed* floor on the conclusion write and leave
/// the canary clobbering it. Without the feedback, the conclusion write reads the pristine seed, sees
/// the floor it asked for, and every test in [TheConclusionWrites] passes against a live defect.
/// Same trap `ControlLoopScaleDownFloorTest` records for #936, reached by a different route.
///
/// Fixture helpers are shared with [SliceTargetOverridePreservationTest] and referenced through it,
/// matching how `ControlLoopScaleDownFloorTest` borrows `ControlLoopOwnerPreservationTest`'s node.
class AbTestOperatorFloorTest {
    /// Both distinct from 1, and from each other, so a clobber to 1 is observable and a restore
    /// cannot be confused with "whatever was seeded". [SliceTargetOverridePreservationTest] seeds a
    /// floor of 1, which is indistinguishable from the clobbered value and therefore cannot observe
    /// this defect at all — hence a separate fixture rather than an added assertion there.
    private static final int OPERATOR_FLOOR = 5;
    private static final int OPERATOR_TARGET = 6;
    private static final int OPERATOR_CEILING = 9;

    private static final SplitRule SPLIT = SplitRule.HeaderHashSplit.headerHashSplit("X-Request-Id", 2);

    private SliceTargetOverridePreservationTest.CapturingRabiaNode rabiaNode;
    private KVStore<AetherKey, AetherValue> kvStore;
    private AbTestManager manager;

    @BeforeEach
    void setUp() {
        rabiaNode = new SliceTargetOverridePreservationTest.CapturingRabiaNode(SliceTargetOverridePreservationTest.SELF);
        kvStore = new KVStore<>(MessageRouter.mutable(),
                                SliceTargetOverridePreservationTest.stubSerializer(),
                                SliceTargetOverridePreservationTest.stubDeserializer());

        seedSliceTarget(operatorTarget(SliceTargetOverridePreservationTest.V1, OPERATOR_FLOOR));

        manager = AbTestManager.abTestManager(rabiaNode,
                                              kvStore,
                                              InvocationMetricsCollector.invocationMetricsCollector());
        manager.activate().await().onFailure(cause -> Assertions.fail(cause.message()));
    }

    @Nested
    class TheCanaryWrite {
        /// The first write that touches a live slice. It legitimately places the variant at one
        /// instance; it has no business touching the floor.
        @Test
        void createTest_preservesOperatorFloor_onTheVariantWrite() {
            startTest();

            assertThat(onlySliceTargetWrite().minInstances()).as("#982: the canary write must carry the operator's floor, not replace it")
                                                            .isEqualTo(OPERATOR_FLOOR);
        }

        /// Opposite polarity, and the reason the fix is not "stop writing instance counts at all".
        /// The canary must still be a canary — an implementation that preserved the floor by leaving
        /// `targetInstances` alone would pass the test above and deploy the variant at full scale.
        @Test
        void createTest_stillPlacesTheVariantAtOneInstance() {
            startTest();

            assertThat(onlySliceTargetWrite().targetInstances()).as("the variant is a canary: one instance, whatever the floor says")
                                                               .isEqualTo(1);
        }
    }

    @Nested
    class TheConclusionWrites {
        /// The headline sequence: a real canary write, that write returning through the store as
        /// consensus delivers it, then a promotion that has to put the operator's floor back.
        @Test
        void concludeTest_restoresOperatorFloor_afterTheCanaryWrite() {
            var testId = startTest();
            var canary = onlySliceTargetWrite();

            assertThat(canary.targetInstances()).as("the canary write must have happened, or the promotion below is vacuous")
                                               .isEqualTo(1);

            observe(canary);

            var promoted = concludeAndCaptureWrite(testId);

            assertThat(promoted.currentVersion()).as("the promotion must have written the winning version")
                                                 .isEqualTo(SliceTargetOverridePreservationTest.V2);
            assertThat(promoted.minInstances()).as("#982: the operator's floor must survive a test it never agreed to")
                                               .isEqualTo(OPERATOR_FLOOR);
            assertThat(promoted.targetInstances()).as("#982: the promoted version must not be parked below the floor it declares")
                                                  .isEqualTo(OPERATOR_FLOOR);
        }

        /// Rollback is the other conclusion write and carries the identical defect, so pinning only
        /// the promotion would leave an auto-rollback — the path a failing variant actually takes —
        /// free to strand the slice at one instance.
        @Test
        void rollbackTest_restoresOperatorFloor_afterTheCanaryWrite() {
            var testId = startTest();

            observe(onlySliceTargetWrite());
            rabiaNode.appliedCommands.clear();

            manager.rollbackTest(testId)
                   .await()
                   .onFailure(cause -> Assertions.fail(cause.message()));

            var restored = onlySliceTargetWrite();

            assertThat(restored.currentVersion()).as("rollback must have written the baseline version")
                                                 .isEqualTo(SliceTargetOverridePreservationTest.V1);
            assertThat(restored.minInstances()).as("#982: rollback must restore the operator's floor")
                                               .isEqualTo(OPERATOR_FLOOR);
            assertThat(restored.targetInstances()).as("#982: the restored baseline must not be left at the canary's single instance")
                                                  .isEqualTo(OPERATOR_FLOOR);
        }

        /// A slice whose floor is genuinely 1 must conclude at 1. Without this, an implementation
        /// that ignored the observed floor and wrote a constant would pass every assertion above,
        /// and every slice in the cluster would be promoted to five instances.
        @Test
        void concludeTest_withAFloorOfOne_concludesAtOneInstance() {
            seedSliceTarget(operatorTarget(SliceTargetOverridePreservationTest.V1, 1));

            var testId = startTest();

            observe(onlySliceTargetWrite());

            var promoted = concludeAndCaptureWrite(testId);

            assertThat(promoted.minInstances()).as("a floor of 1 is a real floor, carried like any other")
                                               .isEqualTo(1);
            assertThat(promoted.targetInstances()).as("the conclusion write restores the floor's worth of capacity — here, one")
                                                  .isEqualTo(1);
        }

        /// The overrides #424, #698 and #937 fixed must still survive now that the conclusion write
        /// no longer shares a single writer with the canary. Splitting a preserving writer in two is
        /// exactly how a carried field goes missing again.
        @Test
        void concludeTest_stillPreservesOwnerBoundsAndPlacement() {
            var testId = startTest();

            observe(onlySliceTargetWrite());

            var promoted = concludeAndCaptureWrite(testId);

            assertThat(promoted.owningBlueprint()).as("#698: the owner must survive the split writer")
                                                 .isEqualTo(Option.some(SliceTargetOverridePreservationTest.OWNER));
            assertThat(promoted.effectivePlacement()).as("#937: the operator's placement must survive the split writer")
                                                     .isEqualTo(SliceTargetOverridePreservationTest.PLACEMENT);
            assertThat(promoted.maxInstances()).as("#424: the operator's ceiling must survive the split writer")
                                               .isEqualTo(Option.some(OPERATOR_CEILING));
        }
    }

    private String startTest() {
        return manager.createTest(SliceTargetOverridePreservationTest.BASE,
                                  Map.of("canary", SliceTargetOverridePreservationTest.V2),
                                  SPLIT)
                      .await()
                      .onFailure(cause -> Assertions.fail(cause.message()))
                      .unwrap()
                      .testId();
    }

    private SliceTargetValue concludeAndCaptureWrite(String testId) {
        rabiaNode.appliedCommands.clear();
        manager.concludeTest(testId, "canary")
               .await()
               .onFailure(cause -> Assertions.fail(cause.message()));

        return onlySliceTargetWrite();
    }

    /// Returns the A/B writer's own slice-target write to the store, which is what consensus does
    /// and what makes the next lifecycle write read a clobbered record rather than the pristine seed.
    private void observe(SliceTargetValue written) {
        seedSliceTarget(written);
    }

    private void seedSliceTarget(SliceTargetValue value) {
        SliceTargetOverridePreservationTest.seed(kvStore,
                                                 SliceTargetKey.sliceTargetKey(SliceTargetOverridePreservationTest.BASE),
                                                 value);
    }

    /// Asserting on a single captured write rather than `allSatisfy` over a filtered list: a filter
    /// that matches nothing makes `allSatisfy` vacuously true, and the count here is known to be one.
    private SliceTargetValue onlySliceTargetWrite() {
        var writes = SliceTargetOverridePreservationTest.capturedSliceTargets(rabiaNode.appliedCommands);

        assertThat(writes).as("exactly one slice-target write is expected per A/B lifecycle step")
                          .hasSize(1);

        return writes.getFirst();
    }

    private static SliceTargetValue operatorTarget(Version version, int floor) {
        return SliceTargetValue.sliceTargetValue(version,
                                                 OPERATOR_TARGET,
                                                 floor,
                                                 Option.some(SliceTargetOverridePreservationTest.OWNER),
                                                 Option.some(OPERATOR_CEILING),
                                                 Option.some(0.8),
                                                 Option.some(0.2))
                               .withPlacement(SliceTargetOverridePreservationTest.PLACEMENT);
    }
}
