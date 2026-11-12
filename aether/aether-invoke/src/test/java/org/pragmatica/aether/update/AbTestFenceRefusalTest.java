// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.update;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.metrics.deployment.DeploymentEvent;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.slice.SliceState;
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
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;

import static org.assertj.core.api.Assertions.assertThat;

/// #2964: the three A/B lifecycle writes decide the fenced slice-target write BEFORE a refusable state is persisted (conclude, rollback), or
/// compensate for its refusal (start). A refused fence write therefore leaves no persisted intermediate state, the caller gets the plain
/// refusal (still a 409 through the central rule, and safe to repeat), and the repeated request completes without a second test.
class AbTestFenceRefusalTest {
    private static final long WAIT_BOUND_MS = 6_000;
    private static final String HINT = "retry the request";
    private static final SplitRule SPLIT = SplitRule.HeaderHashSplit.headerHashSplit("X-Request-Id", 2);

    private SliceTargetOverridePreservationTest.CapturingRabiaNode rabiaNode;
    private AbTestManager manager;
    private KVStore<AetherKey, AetherValue> kvStore;

    @BeforeEach
    void setUp() {
        rabiaNode = new SliceTargetOverridePreservationTest.CapturingRabiaNode(SliceTargetOverridePreservationTest.SELF);

        kvStore = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(),
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
        assertThat(refusal.message()).as("the operator hint names the one safe action: roll back, never conclude")
                                     .endsWith(": inspect it, then roll it back")
                                     .doesNotContain("conclude");
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

    /// #2964 claim: `CONCLUDING` / `ROLLING_BACK` is held in memory while the fenced write is in flight, so a second lifecycle request is
    /// refused as it was when the state was persisted first; without the claim both ran and the last writer decided the terminal state.
    @Test
    void rollbackTest_whileAConcludeIsInFlight_isRefused_andExactlyOneTerminalStateIsWritten() {
        var testId = startedTest();

        rabiaNode.appliedCommands.clear();
        rabiaNode.holdNextTransaction = true;
        var concluding = manager.concludeTest(testId, "canary");
        awaitHeld();
        var rollback = manager.rollbackTest(testId).await();

        rabiaNode.releaseHeld.run();

        assertThat(rollback.isFailure()).as("a rollback racing an in-flight conclude is refused").isTrue();
        assertThat(concluding.await().isSuccess()).isTrue();
        assertThat(manager.getTest(testId).unwrap().state()).isEqualTo(AbTestState.COMPLETED);
        assertThat(persistedStates()).containsExactly(testId + "=COMPLETED");
        assertThat(sliceTargetTransactions()).as("one fenced write, not two").isEqualTo(1);
    }

    @Test
    void concludeTest_whileAConcludeIsInFlight_isRefused_andOneTargetIsWritten() {
        var testId = startedTest();

        rabiaNode.appliedCommands.clear();
        rabiaNode.holdNextTransaction = true;
        var first = manager.concludeTest(testId, "canary");
        awaitHeld();
        var second = manager.concludeTest(testId, "canary").await();

        rabiaNode.releaseHeld.run();

        assertThat(second.isFailure()).as("a second conclude racing an in-flight conclude is refused").isTrue();
        assertThat(first.await().isSuccess()).isTrue();
        assertThat(persistedStates()).containsExactly(testId + "=COMPLETED");
        assertThat(sliceTargetTransactions()).as("one fenced write, not two").isEqualTo(1);
    }

    @Test
    void concludeTest_whileARollbackIsInFlight_isRefused_andExactlyOneTerminalStateIsWritten() {
        var testId = startedTest();

        rabiaNode.appliedCommands.clear();
        rabiaNode.holdNextTransaction = true;
        var rolling = manager.rollbackTest(testId);
        awaitHeld();
        var conclude = manager.concludeTest(testId, "canary").await();

        rabiaNode.releaseHeld.run();

        assertThat(conclude.isFailure()).as("a conclude racing an in-flight rollback is refused").isTrue();
        assertThat(rolling.await().isSuccess()).isTrue();
        assertThat(manager.getTest(testId).unwrap().state()).isEqualTo(AbTestState.ROLLED_BACK);
        assertThat(persistedStates()).containsExactly(testId + "=ROLLED_BACK");
        assertThat(sliceTargetTransactions()).as("one fenced write, not two").isEqualTo(1);
    }

    @Test
    void autoRollback_whileAnOperatorConcludeIsInFlight_doesNotWriteASecondTarget() {
        var testId = startedTest();

        rabiaNode.appliedCommands.clear();
        rabiaNode.holdNextTransaction = true;
        var concluding = manager.concludeTest(testId, "canary");
        awaitHeld();
        manager.onDeploymentFailed(DeploymentEvent.DeploymentFailed.deploymentFailed(
            Artifact.artifact("org.test:my-slice:2.0.0").unwrap(), SliceTargetOverridePreservationTest.SELF, SliceState.ACTIVATING, "boom", 0L));

        rabiaNode.releaseHeld.run();

        assertThat(concluding.await().isSuccess()).isTrue();
        assertThat(manager.getTest(testId).unwrap().state()).isEqualTo(AbTestState.COMPLETED);
        assertThat(persistedStates()).containsExactly(testId + "=COMPLETED");
        assertThat(sliceTargetTransactions()).as("the auto-rollback was refused: one fenced write").isEqualTo(1);
    }

    @Test
    void createTest_whenTheFencedWriteNeverCompletes_resolvesWithTheTimeout_andIsNotCompensated() {
        rabiaNode.hangTransactions = true;

        var elapsed = elapsedUntilFailure(hungManager().createTest(SliceTargetOverridePreservationTest.BASE,
                                                                   Map.of("canary", SliceTargetOverridePreservationTest.V2),
                                                                   SPLIT),
                                          cause -> assertThat(cause).isNotInstanceOf(SliceTargetFenceError.class));

        assertThat(elapsed).as("the start's own timeout fired, not the test's wait").isLessThan(WAIT_BOUND_MS / 2);
        assertThat(rabiaNode.appliedCommands.stream().filter(KVCommand.Remove.class::isInstance)).as("not compensated").isEmpty();
    }

    @Test
    void createTest_whenTheCompensatingRemovalNeverCompletes_resolvesWithTheTimeout_andKeepsTheTest() {
        rabiaNode.refuseTransactions = true;
        rabiaNode.hangRemovals = true;
        var hung = hungManager();

        var elapsed = elapsedUntilFailure(hung.createTest(SliceTargetOverridePreservationTest.BASE,
                                                          Map.of("canary", SliceTargetOverridePreservationTest.V2),
                                                          SPLIT),
                                          cause -> assertThat(cause).isInstanceOf(SliceTargetFenceError.ContendedAfterStateChange.class));

        assertThat(elapsed).as("the removal's own timeout fired, not the test's wait").isLessThan(WAIT_BOUND_MS / 2);
        assertThat(hung.allTests()).extracting(AbTestDeployment::state).containsExactly(AbTestState.DEPLOYING_VARIANTS);
    }

    @Test
    void concludeTest_whenTheFencedWriteNeverCompletes_resolvesWithTheTimeout_andReleasesTheClaim() {
        var hung = hungManager();
        var testId = hung.createTest(SliceTargetOverridePreservationTest.BASE,
                                     Map.of("canary", SliceTargetOverridePreservationTest.V2),
                                     SPLIT).await().onFailure(cause -> Assertions.fail(cause.message())).unwrap().testId();

        rabiaNode.hangTransactions = true;

        var elapsed = elapsedUntilFailure(hung.concludeTest(testId, "canary"), _ -> {});

        assertThat(elapsed).as("the conclude's own timeout fired").isLessThan(WAIT_BOUND_MS / 2);
        assertThat(hung.getTest(testId).unwrap().state()).as("the claim is released").isEqualTo(AbTestState.ACTIVE);
    }

    @Test
    void rollbackTest_whenTheFencedWriteNeverCompletes_resolvesWithTheTimeout_andReleasesTheClaim() {
        var hung = hungManager();
        var testId = hung.createTest(SliceTargetOverridePreservationTest.BASE,
                                     Map.of("canary", SliceTargetOverridePreservationTest.V2),
                                     SPLIT).await().onFailure(cause -> Assertions.fail(cause.message())).unwrap().testId();

        rabiaNode.hangTransactions = true;

        var elapsed = elapsedUntilFailure(hung.rollbackTest(testId), _ -> {});

        assertThat(elapsed).as("the rollback's own timeout fired").isLessThan(WAIT_BOUND_MS / 2);
        assertThat(hung.getTest(testId).unwrap().state()).as("the claim is released").isEqualTo(AbTestState.ACTIVE);
    }

    /// A manager over the same node and store whose KV operations time out after 200 ms; the callers wait up to [#WAIT_BOUND_MS], so a
    /// timeout that is missing shows as the wait expiring (the elapsed time), not as a hang.
    private AbTestManager hungManager() {
        var hung = AbTestManager.abTestManager(rabiaNode,
                                               kvStore,
                                               InvocationMetricsCollector.invocationMetricsCollector(),
                                               TimeSpan.timeSpan(200).millis(),
                                               AbTestManager.DEFAULT_TERMINAL_RETENTION_MS);

        hung.activate().await().onFailure(cause -> Assertions.fail(cause.message()));
        return hung;
    }

    private static long elapsedUntilFailure(Promise<?> promise, Consumer<Cause> assertion) {
        var started = System.nanoTime();
        var cause = refusal(promise.await(TimeSpan.timeSpan(WAIT_BOUND_MS).millis()));

        assertion.accept(cause);
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private void awaitHeld() {
        assertThat(rabiaNode.releaseHeld).as("the fenced write is in flight").isNotNull();
    }

    private long sliceTargetTransactions() {
        return rabiaNode.appliedCommands.stream().filter(KVCommand.LeaderTransaction.class::isInstance).count();
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
