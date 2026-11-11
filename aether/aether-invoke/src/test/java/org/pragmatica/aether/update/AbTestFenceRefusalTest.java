// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.update;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AbTestKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AbTestValue;
import org.pragmatica.aether.slice.kvstore.SliceTargetFence.SliceTargetFenceError;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.messaging.MessageRouter;

import static org.assertj.core.api.Assertions.assertThat;

/// #2964: the three A/B lifecycle writes decide the fenced slice-target write BEFORE a refusable state is persisted (conclude, rollback), or
/// compensate for its refusal (start). A refused fence write therefore leaves no persisted intermediate state, the caller gets the plain
/// refusal (still a 409 through the central rule, and safe to repeat), and the repeated request completes without a second test.
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
    void createTest_whenTheFenceRefuses_leavesNoTestPersisted_andARetryCreatesExactlyOne() {
        rabiaNode.refuseTransactions = true;

        var refusal = refusal(startTest());

        assertPlainRefusal(refusal);
        assertThat(manager.allTests()).as("the refused start is forgotten").isEmpty();
        assertThat(persistedStates()).as("its record was written and then removed, so nothing is persisted").isEmpty();

        rabiaNode.refuseTransactions = false;
        var retried = startTest().await().onFailure(cause -> Assertions.fail(cause.message())).unwrap();

        assertThat(manager.allTests()).as("one test, no duplicate").containsExactly(retried);
        assertThat(retried.state()).isEqualTo(AbTestState.ACTIVE);
        assertThat(persistedStates()).containsExactly(retried.testId() + "=ACTIVE");
    }

    @Test
    void createTest_whenTheFenceRefusesAndTheRecordCannotBeRemoved_keepsTheTestAndSaysTheRetryIsUnsafe() {
        rabiaNode.refuseTransactions = true;
        rabiaNode.failRemovals = true;

        var refusal = refusal(startTest());

        assertThat(refusal).isInstanceOf(SliceTargetFenceError.ContendedAfterStateChange.class);
        assertThat(refusal.message()).doesNotContain(HINT).contains("DEPLOYING_VARIANTS");
        assertThat(manager.allTests()).extracting(AbTestDeployment::state).containsExactly(AbTestState.DEPLOYING_VARIANTS);
        assertThat(persistedStates()).hasSize(1).allMatch(state -> state.endsWith("=DEPLOYING_VARIANTS"));
        assertThat(refusal(startTest()).message()).as("a repeated start is refused as a duplicate").isNotEmpty();
        assertThat(manager.allTests()).as("still one test").hasSize(1);
    }

    @Test
    void createTest_whenTheFencedWriteFailsInConsensus_isNotCompensated_becauseTheWriteMayHaveLanded() {
        rabiaNode.failTransactions = true;

        var failure = refusal(startTest());

        assertThat(failure).isNotInstanceOf(SliceTargetFenceError.class);
        assertThat(manager.allTests()).extracting(AbTestDeployment::state).containsExactly(AbTestState.DEPLOYING_VARIANTS);
        assertThat(rabiaNode.appliedCommands.stream().filter(KVCommand.Remove.class::isInstance)).isEmpty();
    }

    @Test
    void concludeTest_whenTheFenceRefuses_persistsNothing_andARetryCompletes() {
        var testId = startedTest();

        rabiaNode.appliedCommands.clear();
        rabiaNode.refuseTransactions = true;

        assertPlainRefusal(refusal(manager.concludeTest(testId, "canary").await()));
        assertThat(manager.getTest(testId).unwrap().state()).as("the test is still ACTIVE").isEqualTo(AbTestState.ACTIVE);
        assertThat(persistedStates()).as("no AbTestKey command was applied").isEmpty();

        rabiaNode.refuseTransactions = false;
        var concluded = manager.concludeTest(testId, "canary").await().onFailure(cause -> Assertions.fail(cause.message())).unwrap();

        assertThat(concluded.state()).isEqualTo(AbTestState.COMPLETED);
        assertThat(persistedStates()).containsExactly(testId + "=COMPLETED");
    }

    @Test
    void rollbackTest_whenTheFenceRefuses_persistsNothing_andARetryCompletes() {
        var testId = startedTest();

        rabiaNode.appliedCommands.clear();
        rabiaNode.refuseTransactions = true;

        assertPlainRefusal(refusal(manager.rollbackTest(testId).await()));
        assertThat(manager.getTest(testId).unwrap().state()).as("the test is still ACTIVE").isEqualTo(AbTestState.ACTIVE);
        assertThat(persistedStates()).as("no AbTestKey command was applied").isEmpty();

        rabiaNode.refuseTransactions = false;
        var rolledBack = manager.rollbackTest(testId).await().onFailure(cause -> Assertions.fail(cause.message())).unwrap();

        assertThat(rolledBack.state()).isEqualTo(AbTestState.ROLLED_BACK);
        assertThat(persistedStates()).containsExactly(testId + "=ROLLED_BACK");
    }

    private Promise<AbTestDeployment> startTest() {
        return manager.createTest(SliceTargetOverridePreservationTest.BASE,
                                  Map.of("canary", SliceTargetOverridePreservationTest.V2),
                                  SPLIT);
    }

    private String startedTest() {
        return startTest().await()
                          .onFailure(cause -> Assertions.fail(cause.message()))
                          .unwrap()
                          .testId();
    }

    /// The AbTestKey writes still standing in the applied commands: a Put sets `testId=STATE`, a Remove deletes it. In order.
    private List<String> persistedStates() {
        var standing = new LinkedHashMap<String, String>();

        rabiaNode.appliedCommands.forEach(command -> track(standing, command));

        return standing.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue()).toList();
    }

    private static void track(Map<String, String> standing, KVCommand<AetherKey> command) {
        switch (command) {
            case KVCommand.Put<AetherKey, ?> put when put.key() instanceof AbTestKey key
                                                      && put.value() instanceof AbTestValue value -> standing.put(key.testId(), value.state());
            case KVCommand.Remove<AetherKey> remove when remove.key() instanceof AbTestKey key -> standing.remove(key.testId());
            default -> {}
        }
    }

    private static Cause refusal(Result<?> result) {
        return result.fold(cause -> cause, _ -> Assertions.fail("expected the failure, the write succeeded"));
    }

    private static Cause refusal(Promise<?> promise) {
        return refusal(promise.await());
    }

    private static void assertPlainRefusal(Cause refusal) {
        assertThat(refusal).as("still a fence refusal, so the central rule answers 409")
                           .isInstanceOf(SliceTargetFenceError.Contended.class);
        assertThat(refusal.message()).as("nothing was persisted, so a repeated request is safe").contains(HINT);
    }
}
