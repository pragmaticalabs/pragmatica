// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.controller.fsm;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.controller.RequirementCombiner.LeaderCombiner;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceRequirementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.RequirementKind;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceRequirementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// Coverage for the [RequirementCombiner] lifecycle that the ordering test never reaches (v2713 B2, mutants r02-r05, r07, r08, r10,
/// r11, r16-r19): the activation sweep, the active flag, and the expiry timers (zero live timers after deactivate, disarm, orphan
/// removal and re-arm). The combiner is the production [LeaderCombiner] over a real `KVStore`; its timers are the real scheduler's, so
/// a timer is judged by its `isCancelled()` and by the combiner's own timer map.
class RequirementCombinerLifecycleTest {
    private static final NodeId SELF = NodeId.nodeId("leader").unwrap();
    private static final Artifact V1 = Artifact.artifact("org.example:lifecycle-slice:1.0.0").unwrap();
    private static final BlueprintId OWNER = BlueprintId.blueprintId("org.example:lifecycle-app:1.0.0").unwrap();
    private static final ArtifactBase BASE = V1.base();
    private static final SliceTargetKey TARGET_KEY = SliceTargetKey.sliceTargetKey(BASE);
    private static final SliceRequirementKey OPERATOR = SliceRequirementKey.sliceRequirementKey(BASE, RequirementKind.OPERATOR);
    private static final SliceRequirementKey SCHEDULED = SliceRequirementKey.sliceRequirementKey(BASE, RequirementKind.SCHEDULED);
    private static final long AWAIT_MS = 3_000;

    private KVStore<AetherKey, AetherValue> store;
    private final AtomicInteger applied = new AtomicInteger();
    private final AtomicBoolean failApply = new AtomicBoolean();
    private volatile Promise<List<Object>> gate;
    private LeaderCombiner combiner;

    @BeforeEach
    void leaderWithAnOwnedTarget() {
        store = FencedTargetWrites.fencedStore(SELF, OWNER);
        commit(new KVCommand.Put<>(TARGET_KEY, targetOf(2)));
        combiner = new LeaderCombiner(store,
                                      this::apply,
                                      System::currentTimeMillis,
                                      () -> 9,
                                      new AtomicBoolean(false),
                                      new ConcurrentHashMap<>(),
                                      new ConcurrentHashMap<>());
    }

    @AfterEach
    void stopCombiner() {
        combiner.deactivate();
    }

    /// r02: activation combines a requirement that is already in the store, with no notification to trigger it.
    @Test
    void activate_sweepsRequirementsAlreadyInTheStore() {
        commit(new KVCommand.Put<>(OPERATOR, requirement(6, Option.none())));

        combiner.activate();

        assertThat(await(() -> committedTarget() == 6)).as("the activation sweep combined the held requirement").isTrue();
    }

    /// r03 (r10 is masked by the guard in `evaluate`, see below): after deactivation a notification writes nothing.
    @Test
    void deactivated_requirementPut_writesNothing() {
        combiner.activate();
        combiner.deactivate();
        commit(new KVCommand.Put<>(OPERATOR, requirement(6, Option.none())));

        combiner.onRequirementPut(OPERATOR);
        pause(400);

        assertThat(applied.get()).as("a deactivated combiner submits nothing").isZero();
        assertThat(committedTarget()).isEqualTo(2);
    }

    /// r11: a retry already scheduled while leading must not write once leadership is lost. (r10, `schedule` always scheduling, is
    /// equivalent: `evaluate` re-checks the flag, so the two guards mask each other and no observable behaviour differs.)
    @Test
    void retryScheduledWhileLeading_doesNotWriteAfterDeactivate() {
        combiner.activate();
        commit(new KVCommand.Put<>(OPERATOR, requirement(6, Option.none())));
        failApply.set(true);
        combiner.onRequirementPut(OPERATOR);
        assertThat(await(() -> applied.get() >= 1)).as("the first write was attempted and failed").isTrue();

        combiner.deactivate();
        failApply.set(false);
        var attempts = applied.get();
        pause(1_600);

        assertThat(applied.get()).as("the retry scheduled before deactivation wrote nothing").isEqualTo(attempts);
        assertThat(committedTarget()).isEqualTo(2);
    }

    /// r04, r05: deactivation cancels the armed expiry timer and forgets it.
    @Test
    void deactivate_cancelsTheArmedTimerAndForgetsIt() {
        var timer = armedTimer();

        combiner.deactivate();

        assertThat(timer.isCancelled()).as("the timer is cancelled").isTrue();
        assertThat(combiner.expiryTimers()).as("no timer is kept").isEmpty();
    }

    /// r17: re-arming cancels the timer it replaces.
    @Test
    void rearm_cancelsThePreviousTimer() {
        var first = armedTimer();

        combiner.onRequirementPut(SCHEDULED);

        assertThat(await(() -> combiner.expiryTimers().get(BASE) != first)).as("a new timer replaced the first").isTrue();
        assertThat(first.isCancelled()).as("the replaced timer is cancelled").isTrue();
    }

    /// r16, r18 (and r07, the removal handler): once the last expiring requirement is removed the timer is disarmed, which cancels it.
    @Test
    void lastExpiringRequirementRemoved_disarmsAndCancelsTheTimer() {
        var timer = armedTimer();

        commit(new KVCommand.Remove<>(SCHEDULED, store.get(SCHEDULED).map(Object.class::cast)));
        combiner.onRequirementRemove(SCHEDULED);

        assertThat(await(() -> combiner.expiryTimers().isEmpty())).as("the timer is forgotten").isTrue();
        assertThat(timer.isCancelled()).as("the timer is cancelled").isTrue();
    }

    /// r19: removing orphaned requirements disarms the slice's timer.
    @Test
    void targetRemoved_orphanPathDisarmsAndCancelsTheTimer() {
        var timer = armedTimer();

        commit(new KVCommand.Remove<>(TARGET_KEY, store.get(TARGET_KEY).map(Object.class::cast)));
        combiner.onSliceTargetRemove(TARGET_KEY);

        assertThat(await(() -> combiner.expiryTimers().isEmpty())).as("the timer is forgotten").isTrue();
        assertThat(timer.isCancelled()).as("the timer is cancelled").isTrue();
    }

    /// r07: the removal handler itself combines (a requirement gone from the store changes the combination).
    @Test
    void onRequirementRemove_combinesTheSlice() {
        combiner.activate();
        commit(new KVCommand.Put<>(OPERATOR, requirement(6, Option.none())));

        combiner.onRequirementRemove(OPERATOR);

        assertThat(await(() -> committedTarget() == 6)).as("the removal notification combined").isTrue();
    }

    /// r08: the target-put handler itself combines (a blueprint publish rewrites the target and arrives this way).
    @Test
    void onSliceTargetPut_combinesTheSlice() {
        combiner.activate();
        commit(new KVCommand.Put<>(OPERATOR, requirement(6, Option.none())));

        combiner.onSliceTargetPut(TARGET_KEY);

        assertThat(await(() -> committedTarget() == 6)).as("the target-put notification combined").isTrue();
    }

    /// q11: every failed write counts as another attempt (the count grows with each retry).
    @Test
    void failedWrite_isCountedAsGrowingAttempts() {
        failingWriteIsRetrying();

        assertThat(await(() -> combiner.retries().getOrDefault(BASE, 0) >= 2)).as("the second failure is attempt 2").isTrue();
    }

    /// q05, q06, q07: a retry that finally writes forgets its attempt count.
    @Test
    void retryThatWrites_clearsTheRetryCount() {
        failingWriteIsRetrying();
        failApply.set(false);

        assertThat(await(() -> committedTarget() == 6)).as("the retry wrote the combination").isTrue();
        assertThat(await(() -> combiner.retries().isEmpty())).as("the written combination cleared its retry count").isTrue();
    }

    /// q08, q09, r20: a retry that finds the postcondition holding (Settled) clears the count and is not retried again.
    @Test
    void retryThatFindsThePostconditionHolding_clearsTheCountAndStops() {
        failingWriteIsRetrying();
        failApply.set(false);
        commit(new KVCommand.Put<>(TARGET_KEY, targetOf(6)));

        assertThat(await(() -> combiner.retries().isEmpty())).as("a CombinerOutcome is not a failure to retry").isTrue();
        pause(300);
        assertThat(combiner.retries()).as("and nothing re-armed a retry").isEmpty();
    }

    /// q06: an orphan removal that finally writes also forgets its attempt count.
    @Test
    void orphanRemovalRetryThatWrites_clearsTheRetryCount() {
        combiner.activate();
        commit(new KVCommand.Put<>(OPERATOR, requirement(6, Option.none())));
        commit(new KVCommand.Remove<>(TARGET_KEY, store.get(TARGET_KEY).map(Object.class::cast)));
        failApply.set(true);
        combiner.onSliceTargetRemove(TARGET_KEY);
        assertThat(await(() -> combiner.retries().containsKey(BASE))).as("the failed removal is counted for a retry").isTrue();

        failApply.set(false);

        assertThat(await(() -> store.get(OPERATOR).isEmpty())).as("the retry removed the orphan requirement").isTrue();
        assertThat(await(() -> combiner.retries().isEmpty())).as("the written removal cleared its retry count").isTrue();
    }

    /// q02: deactivation forgets the pending retry counts.
    @Test
    void deactivate_forgetsPendingRetries() {
        failingWriteIsRetrying();

        combiner.deactivate();

        assertThat(combiner.retries()).isEmpty();
    }

    /// q10: a write that fails after leadership was lost is neither counted nor retried.
    @Test
    void failureAfterDeactivate_isNotCounted() {
        var held = Promise.<List<Object>> promise();

        gate = held;
        combiner.activate();
        commit(new KVCommand.Put<>(OPERATOR, requirement(6, Option.none())));
        combiner.onRequirementPut(OPERATOR);
        assertThat(await(() -> applied.get() >= 1)).as("the write is in flight").isTrue();

        combiner.deactivate();
        held.fail(Causes.cause("apply failed after deactivate (test)"));
        pause(300);

        assertThat(combiner.retries()).as("a deactivated combiner does not retry").isEmpty();
    }

    /// Leads, puts an operator requirement of 6 and fails its write; returns once the failure is counted for the retry.
    private void failingWriteIsRetrying() {
        combiner.activate();
        commit(new KVCommand.Put<>(OPERATOR, requirement(6, Option.none())));
        failApply.set(true);
        combiner.onRequirementPut(OPERATOR);
        assertThat(await(() -> combiner.retries().containsKey(BASE))).as("the failed write is counted for a retry").isTrue();
    }

    /// Activates over a requirement that expires in a minute and returns the timer the combination armed for it.
    private ScheduledFuture<?> armedTimer() {
        commit(new KVCommand.Put<>(SCHEDULED, requirement(6, Option.some(System.currentTimeMillis() + 60_000))));
        combiner.activate();
        assertThat(await(() -> combiner.expiryTimers().containsKey(BASE))).as("an expiry timer is armed").isTrue();

        return combiner.expiryTimers().get(BASE);
    }

    private Promise<List<Object>> apply(List<KVCommand<AetherKey>> batch) {
        applied.incrementAndGet();

        var held = gate;

        if (held != null) {
            return held;
        }

        if (failApply.get()) {
            return Causes.cause("apply failed (test)").promise();
        }

        return Promise.success(store.<Object> process(store.createBatch(batch)));
    }

    private int committedTarget() {
        return store.getTyped(TARGET_KEY, SliceTargetValue.class).map(SliceTargetValue::targetInstances).or(-1);
    }

    private void commit(KVCommand<AetherKey> command) {
        store.process(store.createBatch(List.of(command)));
    }

    private static SliceRequirementValue requirement(int instances, Option<Long> expiresAt) {
        return SliceRequirementValue.sliceRequirementValue(instances, "test", expiresAt, 0L);
    }

    private static SliceTargetValue targetOf(int instances) {
        return SliceTargetValue.sliceTargetValue(V1.version(), instances, 1, Option.some(OWNER), Option.some(20), Option.none(), Option.none());
    }

    private static boolean await(BooleanSupplier condition) {
        var deadline = System.currentTimeMillis() + AWAIT_MS;

        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            pause(10);
        }

        return condition.getAsBoolean();
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
