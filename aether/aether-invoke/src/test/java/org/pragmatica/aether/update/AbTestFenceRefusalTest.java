// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.update;

import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.SliceTargetFence.SliceTargetFenceError;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.messaging.MessageRouter;

import static org.assertj.core.api.Assertions.assertThat;

/// #2944 / #2964: the three A/B lifecycle writes commit the test's next state BEFORE the fenced slice-target write, so a contended refusal
/// leaves the test in that state and the request is NOT safe to repeat. The refusal must still be a fence refusal (409 through the central
/// status rule) but must not say "retry the request", the phrase the CLI retries a 409 on.
class AbTestFenceRefusalTest {
    private static final String HINT = "retry the request";
    private static final SplitRule SPLIT = SplitRule.HeaderHashSplit.headerHashSplit("X-Request-Id", 2);

    private SliceTargetOverridePreservationTest.CapturingRabiaNode rabiaNode;
    private AbTestManager manager;

    @BeforeEach
    void setUp() {
        rabiaNode = new SliceTargetOverridePreservationTest.CapturingRabiaNode(SliceTargetOverridePreservationTest.SELF);

        var kvStore = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(),
                                                          SliceTargetOverridePreservationTest.stubSerializer(),
                                                          SliceTargetOverridePreservationTest.stubDeserializer());

        SliceTargetOverridePreservationTest.seedLeaderAndOwner(kvStore);
        SliceTargetOverridePreservationTest.seed(kvStore,
                                                 SliceTargetKey.sliceTargetKey(SliceTargetOverridePreservationTest.BASE),
                                                 SliceTargetOverridePreservationTest.targetWithOverrides(SliceTargetOverridePreservationTest.V1));
        manager = AbTestManager.abTestManager(rabiaNode,
                                              kvStore,
                                              InvocationMetricsCollector.invocationMetricsCollector());
        manager.activate().await().onFailure(cause -> Assertions.fail(cause.message()));
    }

    @Test
    void createTest_whenTheFenceRefuses_failsWithoutTheRetryHint_andNamesTheState() {
        rabiaNode.refuseTransactions = true;

        var refusal = refusal(manager.createTest(SliceTargetOverridePreservationTest.BASE,
                                                 Map.of("canary", SliceTargetOverridePreservationTest.V2),
                                                 SPLIT)
                                     .await());

        assertNoHint(refusal, "DEPLOYING_VARIANTS");
    }

    @Test
    void concludeTest_whenTheFenceRefuses_failsWithoutTheRetryHint_andNamesTheState() {
        var testId = startTest();

        rabiaNode.refuseTransactions = true;

        assertNoHint(refusal(manager.concludeTest(testId, "canary").await()), "CONCLUDING");
    }

    @Test
    void rollbackTest_whenTheFenceRefuses_failsWithoutTheRetryHint_andNamesTheState() {
        var testId = startTest();

        rabiaNode.refuseTransactions = true;

        assertNoHint(refusal(manager.rollbackTest(testId).await()), "ROLLING_BACK");
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

    private static Cause refusal(Result<?> result) {
        return result.fold(cause -> cause, _ -> Assertions.fail("expected the fence refusal, the write succeeded"));
    }

    private static void assertNoHint(Cause refusal, String state) {
        assertThat(refusal).as("still a fence refusal, so the central rule answers 409")
                           .isInstanceOf(SliceTargetFenceError.ContendedAfterStateChange.class);
        assertThat(refusal.message()).as("a repeated request is not safe, so the CLI must not be told to retry it")
                                     .doesNotContain(HINT)
                                     .contains(state);
    }
}
