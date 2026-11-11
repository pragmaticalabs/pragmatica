// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.update;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AbTestKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AbTestValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.update.SliceTargetOverridePreservationTest.BASE;
import static org.pragmatica.aether.update.SliceTargetOverridePreservationTest.V1;
import static org.pragmatica.aether.update.SliceTargetOverridePreservationTest.V2;

/// #2964: the in-memory claim of conclude / rollback is atomic (a CAS on the value the caller read), and its release on a failed write is a CAS
/// too, completed before the caller can observe the failure. Races are released together by a spin barrier and bounded by a deadline.
class AbTestClaimTest {
    private static final SplitRule SPLIT = SplitRule.HeaderHashSplit.headerHashSplit("X-Request-Id", 2);
    private static final int RACE_ITERATIONS = 500;
    private static final int LAG_ITERATIONS = 400;
    private static final long DEADLINE_NS = 120_000_000_000L;

    private static final BiFunction<AbTestManager, String, Promise<AbTestDeployment>> CONCLUDE = (m, id) -> m.concludeTest(id, "canary");
    private static final BiFunction<AbTestManager, String, Promise<AbTestDeployment>> ROLLBACK = (m, id) -> m.rollbackTest(id);

    private SliceTargetOverridePreservationTest.CapturingRabiaNode rabia;
    private KVStore<AetherKey, AetherValue> kv;

    @Test
    void concludeVsRollback_exactlyOneWins_andTheLoserIsRefusedAsInFlight() {
        race(CONCLUDE, AbTestState.CONCLUDING, ROLLBACK, AbTestState.ROLLING_BACK);
    }

    @Test
    void concludeVsConclude_exactlyOneWins_andTheLoserIsRefusedAsInFlight() {
        race(CONCLUDE, AbTestState.CONCLUDING, CONCLUDE, AbTestState.CONCLUDING);
    }

    @Test
    void rollbackVsRollback_exactlyOneWins_andTheLoserIsRefusedAsInFlight() {
        race(ROLLBACK, AbTestState.ROLLING_BACK, ROLLBACK, AbTestState.ROLLING_BACK);
    }

    @Test
    void concludeTest_afterAFailedWrite_theReleaseIsVisibleWhenTheFailureIs() {
        releaseIsVisibleWithTheFailure(CONCLUDE);
    }

    @Test
    void rollbackTest_afterAFailedWrite_theReleaseIsVisibleWhenTheFailureIs() {
        releaseIsVisibleWithTheFailure(ROLLBACK);
    }

    @Test
    void release_whenAnotherWriterReplacedTheClaim_keepsTheirValue() {
        var manager = manager(200);
        var testId = started(manager);

        rabia.hangTransactions = true;

        var concluding = manager.concludeTest(testId, "canary");

        awaitState(manager, testId, AbTestState.CONCLUDING);
        SliceTargetOverridePreservationTest.seed(kv, new AbTestKey(testId), storedAs(testId, AbTestState.COMPLETED));
        manager.reloadRestoredState();

        assertThat(concluding.await(TimeSpan.timeSpan(6_000).millis()).isFailure()).as("the hung write timed out").isTrue();
        assertThat(manager.getTest(testId).unwrap().state()).as("the restored value is not clobbered by the release")
                                                           .isEqualTo(AbTestState.COMPLETED);
    }

    private void race(BiFunction<AbTestManager, String, Promise<AbTestDeployment>> first,
                      AbTestState firstTarget,
                      BiFunction<AbTestManager, String, Promise<AbTestDeployment>> second,
                      AbTestState secondTarget) {
        var deadline = System.nanoTime() + DEADLINE_NS;
        var doubles = new AtomicInteger();
        var wrongRefusals = new AtomicInteger();
        int done = 0;

        for (; done < RACE_ITERATIONS && System.nanoTime() < deadline; done++) {
            var manager = manager(20_000);
            var testId = started(manager);
            var go = new AtomicBoolean();
            var ready = new AtomicInteger();
            var r1 = new AtomicReference<Result<AbTestDeployment>>();
            var r2 = new AtomicReference<Result<AbTestDeployment>>();
            var t1 = new Thread(() -> run(go, ready, r1, () -> first.apply(manager, testId)));
            var t2 = new Thread(() -> run(go, ready, r2, () -> second.apply(manager, testId)));

            t1.start();
            t2.start();
            while (ready.get() < 2) {
                Thread.onSpinWait();
            }
            go.set(true);
            join(t1);
            join(t2);
            if (r1.get().isSuccess() && r2.get().isSuccess()) {
                doubles.incrementAndGet();
            } else if (!isCorrectLoser(r1.get(), firstTarget) || !isCorrectLoser(r2.get(), secondTarget)) {
                wrongRefusals.incrementAndGet();
            }
        }
        assertThat(done).as("iterations completed before the deadline").isGreaterThanOrEqualTo(RACE_ITERATIONS / 2);
        assertThat(doubles.get()).as("both requests passed the claim").isZero();
        assertThat(wrongRefusals.get()).as("a loser refused with the exact in-flight state and its own target").isZero();
    }

    /// A winner passes; a loser is refused from the winner's state (in flight or already terminal, never ACTIVE) to its own target: by the claim
    /// or the state check as an `InvalidTestState`, or by the state machine (a rollback over an in-flight claim) as an invalid transition.
    private static boolean isCorrectLoser(Result<AbTestDeployment> result, AbTestState target) {
        return result.fold(cause -> isRefusalToTarget(cause, target), _ -> true);
    }

    private static boolean isRefusalToTarget(Cause cause, AbTestState target) {
        return cause instanceof AbTestDeploymentError.InvalidTestState refusal
               ? refusal.from() != AbTestState.ACTIVE && refusal.to() == target
               : cause.message().matches("Invalid A/B test state transition: (?!ACTIVE )\\w+ -> " + target);
    }

    private void releaseIsVisibleWithTheFailure(BiFunction<AbTestManager, String, Promise<AbTestDeployment>> call) {
        var manager = manager(1);
        var testId = started(manager);

        rabia.hangTransactions = true;

        int lagged = 0;

        for (int i = 0; i < LAG_ITERATIONS; i++) {
            assertThat(call.apply(manager, testId).await().isFailure()).isTrue();
            if (manager.getTest(testId).unwrap().state() != AbTestState.ACTIVE) {
                lagged++;
            }
        }
        assertThat(lagged).as("failures observed while the claim was still held").isZero();
    }

    private AbTestManager manager(long kvTimeoutMs) {
        rabia = new SliceTargetOverridePreservationTest.CapturingRabiaNode(SliceTargetOverridePreservationTest.SELF);
        kv = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(),
                                                 SliceTargetOverridePreservationTest.stubSerializer(),
                                                 SliceTargetOverridePreservationTest.stubDeserializer());
        SliceTargetOverridePreservationTest.seedLeaderAndOwner(kv);
        SliceTargetOverridePreservationTest.seed(kv,
                                                 SliceTargetKey.sliceTargetKey(BASE),
                                                 SliceTargetOverridePreservationTest.targetWithOverrides(V1));

        var manager = AbTestManager.abTestManager(rabia,
                                                  kv,
                                                  InvocationMetricsCollector.invocationMetricsCollector(),
                                                  TimeSpan.timeSpan(kvTimeoutMs).millis(),
                                                  AbTestManager.DEFAULT_TERMINAL_RETENTION_MS);

        manager.activate().await().onFailure(cause -> Assertions.fail(cause.message()));
        return manager;
    }

    private static String started(AbTestManager manager) {
        return manager.createTest(BASE, Map.of("canary", V2), SPLIT)
                      .await()
                      .onFailure(cause -> Assertions.fail(cause.message()))
                      .unwrap()
                      .testId();
    }

    private static void awaitState(AbTestManager manager, String testId, AbTestState expected) {
        var deadline = System.nanoTime() + 5_000_000_000L;

        while (manager.getTest(testId).unwrap().state() != expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(manager.getTest(testId).unwrap().state()).isEqualTo(expected);
    }

    private static AbTestValue storedAs(String testId, AbTestState state) {
        var now = System.currentTimeMillis();

        return AbTestValue.abTestValue(testId, BASE, V1, "canary=" + V2, state.name(), "header-hash:X-Request-Id:2", 50, 50, "", now, now);
    }

    private static void run(AtomicBoolean go,
                            AtomicInteger ready,
                            AtomicReference<Result<AbTestDeployment>> out,
                            java.util.function.Supplier<Promise<AbTestDeployment>> call) {
        ready.incrementAndGet();
        while (!go.get()) {
            Thread.onSpinWait();
        }
        out.set(call.get().await(TimeSpan.timeSpan(20_000).millis()));
    }

    private static void join(Thread thread) {
        try {
            thread.join(25_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Assertions.fail("interrupted");
        }
    }
}
