/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */

package org.pragmatica.net.tcp.security;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.SharedScheduler;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/// Covers the immediate-renewal branch of `Healthy.onEntry` (`delay <= 0`) and the `Stopped`
/// drain.
///
/// Note on what can and cannot be asserted here. On the immediate branch the tick is scheduled
/// with a zero delay, so it runs on a scheduler virtual thread and drives
/// `Healthy -> Renewing -> RetryBackoff` before `start()` returns to the caller. `scheduledTask`
/// is therefore legitimately `None` immediately after `start()`: `Healthy.onExit` has already
/// drained it and `Renewing` arms no timer. Measured sequence — `None` at t=0, `Some(...)` from
/// t=50ms, stable thereafter. Any assertion that the holder is `Some` right after `start()`
/// pins a transient that no correct implementation can guarantee.
class CertificateRenewalSchedulerStaleTimerTest {

    /// Stub provider whose `issueCertificate` never returns synchronously (returns a perpetual
    /// failure marker — the scheduler dispatches `RenewalFailed` and goes to `RetryBackoff`).
    /// We never let it complete; the test only inspects the scheduled future.
    static final class CountingProvider implements CertificateProvider {
        final AtomicInteger calls = new AtomicInteger(0);

        @Override
        public Result<CertificateBundle> issueCertificate(String nodeId, String hostname) {
            calls.incrementAndGet();
            return Causes.cause("not implemented in stub").result();
        }

        @Override
        public Result<CertificateBundle> caCertificate() {
            return Causes.cause("not implemented in stub").result();
        }

        @Override
        public Result<GossipKey> currentGossipKey() {
            return Causes.cause("not implemented in stub").result();
        }

        @Override
        public Option<GossipKey> previousGossipKey() {
            return Option.none();
        }
    }

    /// Reflectively reads the private `ctx.scheduledTask` AtomicReference on the scheduler.
    /// Necessary because `Context` is package-private (visible to test) but the field on
    /// `CertificateRenewalScheduler` itself is private.
    @SuppressWarnings("unchecked")
    private static AtomicReference<Option<ScheduledFuture<?>>> readScheduledTask(CertificateRenewalScheduler s) {
        try {
            Field ctxField = CertificateRenewalScheduler.class.getDeclaredField("ctx");
            ctxField.setAccessible(true);
            Object ctx = ctxField.get(s);
            Field taskField = ctx.getClass().getDeclaredField("scheduledTask");
            taskField.setAccessible(true);
            return (AtomicReference<Option<ScheduledFuture<?>>>) taskField.get(ctx);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new AssertionError("reflection failed: " + e.getMessage(), e);
        }
    }

    @Test
    void immediateRenewalBranch_renewsOnceAndRearmsTheTimer() {
        var provider = new CountingProvider();
        // Past `notAfter` forces calculateRenewalDelay -> negative -> immediate-renewal branch.
        var pastInstant = Instant.now().minusSeconds(60);
        var scheduler = CertificateRenewalScheduler.certificateRenewalScheduler(
                provider, "node-immediate", "localhost",
                _ -> {}, pastInstant);

        try {
            scheduler.start();

            // What the immediate branch promises is not a transient holder value (see class doc)
            // but that it renews, and that it leaves the scheduler armed rather than silent.
            awaitCondition(() -> provider.calls.get() == 1,
                           "immediate-renewal branch must attempt renewal exactly once");

            var holder = readScheduledTask(scheduler);

            awaitCondition(() -> holder.get().isPresent(),
                           "after the immediate renewal fails, RetryBackoff.onEntry must re-arm the "
                           + "timer — a scheduler left with no scheduled tick never retries");
        } finally {
            scheduler.stop();
        }
    }

    /// Polls until `condition` holds, failing with `description` if it never does. The FSM
    /// transition being awaited runs on a scheduler virtual thread, so the settled state is
    /// reached asynchronously; the deadline is generous because the assertion is about whether
    /// the state is EVER reached, never about how fast.
    private static void awaitCondition(BooleanSupplier condition, String description) {
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();

        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while awaiting: " + description, e);
            }
        }

        throw new AssertionError("condition never held within 5s: " + description);
    }

    @Test
    void stopAfterImmediateRenewal_clearsScheduledTask() {
        var provider = new CountingProvider();
        var scheduler = CertificateRenewalScheduler.certificateRenewalScheduler(
                provider, "node-stop", "localhost",
                _ -> {}, Instant.now().minusSeconds(60));

        scheduler.start();
        scheduler.stop();

        var holder = readScheduledTask(scheduler);
        assertThat(holder.get().isEmpty())
                .as("Stopped.onEntry → cancelScheduledTask drains the holder to None")
                .isTrue();
    }

    /// Invokes the package-private `ctx.armScheduledTask(...)` reflectively, for the same reason
    /// `readScheduledTask` reads the field that way: `Context` is reachable from this package but
    /// the `ctx` field on the scheduler is private.
    private static void armScheduledTask(CertificateRenewalScheduler s, ScheduledFuture<?> task) {
        try {
            Field ctxField = CertificateRenewalScheduler.class.getDeclaredField("ctx");
            ctxField.setAccessible(true);
            Object ctx = ctxField.get(s);
            var method = ctx.getClass().getDeclaredMethod("armScheduledTask", ScheduledFuture.class);
            method.setAccessible(true);
            method.invoke(ctx, task);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("reflection failed: " + e.getMessage(), e);
        }
    }

    @Test
    void armScheduledTask_afterStop_refusesAndCancelsTheTask() {
        var provider = new CountingProvider();
        // Far-future notAfter: `Healthy` arms a real long-delay timer and no tick races us, so the
        // only concurrency in this test is the one it is about.
        var scheduler = CertificateRenewalScheduler.certificateRenewalScheduler(
                provider, "node-late-arm", "localhost",
                _ -> {}, Instant.now().plusSeconds(3600));

        scheduler.start();
        scheduler.stop();

        var holder = readScheduledTask(scheduler);
        assertThat(holder.get().isEmpty())
                .as("precondition: stop() drains the holder before the late arm is attempted")
                .isTrue();

        // #1191's interleaving, driven rather than awaited: a losing-path `onEntry` arms AFTER
        // `Stopped.onEntry` has drained. The natural race is rare — 200 isolated runs of
        // `stopAfterImmediateRenewal_clearsScheduledTask` reproduced it zero times on an idle
        // machine, while CI hit it once — so waiting for it would be a lottery, not a regression
        // pin. Calling the arming seam directly reproduces the CONSEQUENCE every time.
        var late = SharedScheduler.schedule(() -> {}, TimeSpan.timeSpan(3_600_000L).millis());

        armScheduledTask(scheduler, late);

        assertThat(holder.get().isEmpty())
                .as("#1191: arming after the terminal state must be refused, never stored")
                .isTrue();
        assertThat(late.isCancelled())
                .as("#1191: the refused task must be CANCELLED, not merely dropped — a dropped but "
                    + "live future still fires its tick on a stopped scheduler, which is the defect")
                .isTrue();
    }

    /// Reflectively reads `ctx.terminated`. Same justification as `readScheduledTask`.
    private static boolean readTerminated(CertificateRenewalScheduler s) {
        try {
            Field ctxField = CertificateRenewalScheduler.class.getDeclaredField("ctx");
            ctxField.setAccessible(true);
            Object ctx = ctxField.get(s);
            Field terminatedField = ctx.getClass().getDeclaredField("terminated");
            terminatedField.setAccessible(true);
            return ((AtomicBoolean) terminatedField.get(ctx)).get();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("reflection failed: " + e.getMessage(), e);
        }
    }

    /// A `ScheduledFuture` stub that records what `ctx.terminated` held **at the moment it was
    /// cancelled**. That is the entire instrument: it converts an ORDERING inside `Stopped.onEntry`
    /// into a value a test can assert on, with no concurrency and no timing involved.
    static final class OrderRecordingFuture implements ScheduledFuture<Object> {
        private final Supplier<Boolean> terminatedNow;
        private final AtomicReference<Boolean> terminatedWhenCancelled = new AtomicReference<>(null);
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        OrderRecordingFuture(Supplier<Boolean> terminatedNow) {
            this.terminatedNow = terminatedNow;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            // Only the FIRST cancellation is recorded. The arming guard's post-store re-check can
            // cancel a second time, and it is the first observation that describes the drain.
            terminatedWhenCancelled.compareAndSet(null, terminatedNow.get());
            cancelled.set(true);
            return true;
        }

        Boolean terminatedWhenCancelled() {
            return terminatedWhenCancelled.get();
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public boolean isDone() {
            return cancelled.get();
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return 0L;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }

        @Override
        public Object get() {
            throw new AssertionError("stub future: get() is never called by the code under test");
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            throw new AssertionError("stub future: get(timeout) is never called by the code under test");
        }
    }

    @Test
    void stopEntry_raisesTerminatedBeforeDraining_soNoArmCanSlipBetweenThem() {
        var provider = new CountingProvider();
        var scheduler = CertificateRenewalScheduler.certificateRenewalScheduler(
                provider, "node-order", "localhost",
                _ -> {}, Instant.now().plusSeconds(3600));

        // Deliberately NOT started, and that is the whole design of this test. `transitionTo` runs
        // the FROM state's `onExit` before the target's `onEntry`, and both `Healthy.onExit` and
        // `RetryBackoff.onExit` drain the holder themselves. Entered from either, the probe is
        // cancelled by the EXIT hook while `terminated` is still false, so the test measures a
        // drain it is not about and fails against correct code — which is precisely what the first
        // version of this test did. `Idle` declares no exit hook, so the only drain that can reach
        // the probe is the one inside `Stopped.onEntry`, which is the ordering under test.
        var holder = readScheduledTask(scheduler);
        var probe = new OrderRecordingFuture(() -> readTerminated(scheduler));

        holder.set(Option.<ScheduledFuture<?>>some(probe));

        assertThat(readTerminated(scheduler))
                .as("precondition: the scheduler is not terminal before stop()")
                .isFalse();

        scheduler.stop();

        assertThat(probe.terminatedWhenCancelled())
                .as("precondition: Stopped.onEntry actually drained the holder and cancelled the "
                    + "probe — a null here means the drain never reached it, so the assertion "
                    + "below would be vacuous")
                .isNotNull();
        assertThat(probe.terminatedWhenCancelled())
                .as("#1191: `terminated` must ALREADY be raised when the drain cancels. If the "
                    + "drain ran first, a concurrent arm landing between the drain and the flag "
                    + "would be stored and never cancelled, which is exactly the leaked timer")
                .isTrue();
    }

    @Test
    void futureRenewalBranch_alsoStoresScheduledFuture() {
        // Sanity: verifies the original (long-delay) branch still stores the future (this branch
        // was correct before the L3 fix; included to guarantee no regression in that path).
        var provider = new CountingProvider();
        var farFutureInstant = Instant.now().plusSeconds(3600);
        var scheduler = CertificateRenewalScheduler.certificateRenewalScheduler(
                provider, "node-future", "localhost",
                _ -> {}, farFutureInstant);

        try {
            scheduler.start();

            var holder = readScheduledTask(scheduler);
            assertThat(holder.get().isPresent())
                    .as("long-delay branch stores the future (regression guard)")
                    .isTrue();
        } finally {
            scheduler.stop();
        }
    }
}
