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
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/// Verifies the L3 fix: `Healthy.onEntry` always stores the scheduled `Tick` future on the
/// `Context`, including the immediate-renewal branch (`delay <= 0`). Prior bug: the immediate
/// branch did `SharedScheduler.schedule(...)` then early-returned, so `scheduledTask` was never
/// populated and the tick could fire after a transition out of `Healthy`.
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
    void immediateRenewalBranch_storesScheduledFutureForCancellation() {
        var provider = new CountingProvider();
        // Past `notAfter` forces calculateRenewalDelay -> negative -> immediate-renewal branch.
        var pastInstant = Instant.now().minusSeconds(60);
        var scheduler = CertificateRenewalScheduler.certificateRenewalScheduler(
                provider, "node-immediate", "localhost",
                _ -> {}, pastInstant);

        try {
            scheduler.start();

            var holder = readScheduledTask(scheduler);
            // The future MUST be stored regardless of which Healthy.onEntry branch ran.
            assertThat(holder.get())
                    .as("Healthy.onEntry must store the scheduled tick future even on the immediate-renewal branch")
                    .isNotNull();
            assertThat(holder.get().isPresent())
                    .as("scheduledTask must hold Some(future), not None")
                    .isTrue();
        } finally {
            scheduler.stop();
        }
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
