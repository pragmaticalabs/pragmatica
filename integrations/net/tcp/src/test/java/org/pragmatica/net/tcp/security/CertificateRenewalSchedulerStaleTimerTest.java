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
import org.pragmatica.lang.utils.Causes;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

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
