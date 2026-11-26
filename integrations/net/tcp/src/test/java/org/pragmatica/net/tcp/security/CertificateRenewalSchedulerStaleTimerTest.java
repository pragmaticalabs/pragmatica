/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */

package org.pragmatica.net.tcp.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.net.tcp.security.CertificateRenewalScheduler.RenewalEvent;
import org.pragmatica.net.tcp.security.CertificateRenewalScheduler.SchedulerState;
import org.pragmatica.statemachine.Fsm;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/// Every timer of the scheduler is armed through the lifetime of the state entry that armed it (#1191), so no timer
/// outlives its state, whatever order the hooks of two successive winners run in.
///
/// The observable is the entry's own lifetime (`Fsm.entered().lifetime()`): `size()` counts what the entry has live, and a
/// registration made after the entry ended is refused and is never counted. The race tests drive the overtake rather than
/// await it: the first winner is parked in the transition action (`renewalCallback`), which the Fsm runs after the CAS and
/// before the successor's `onEntry`, exactly where the reported interleaving has it.
///
/// What each race test pins:
/// - `healthyEntryOvertakenByTick`: the sibling of the reported shape, and the one a flag set by `Stopped` could not cover.
///   A stale `Healthy` arm used to look fresh (it drew the newest epoch) and displaced the live retry. Its consequence is
///   behavioural: a stray tick re-enters `Renewing` and the provider is called a third time.
/// - `healthyEntryOvertakenByStop`: the reported shape. Nothing is armed on the stopped scheduler.
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class CertificateRenewalSchedulerStaleTimerTest {
    /// How long a leaked `Healthy` tick (armed ~600 ms ahead) would need to fire, with margin.
    private static final long LEAK_WINDOW_MILLIS = 1_800;

    /// Call 1 succeeds with a bundle valid for 1.5 s (so a `Healthy` entered on it ticks after 600 ms); every later call
    /// fails and drives the scheduler to `RetryBackoff`.
    static final class SequencedProvider implements CertificateProvider {
        final AtomicInteger calls = new AtomicInteger(0);

        @Override
        public Result<CertificateBundle> issueCertificate(String nodeId, String hostname) {
            return calls.incrementAndGet() == 1
                   ? Result.success(shortLivedBundle())
                   : Causes.cause("renewal failed in stub").result();
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

        private static CertificateBundle shortLivedBundle() {
            return CertificateBundle.certificateBundle(new byte[0],
                                                       new byte[0],
                                                       new byte[0],
                                                       Instant.now().plusMillis(1_500));
        }
    }

    /// Never succeeds: used where the renewal path is not what is under test.
    static final class FailingProvider implements CertificateProvider {
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

    /// A renewal callback that parks the thread that runs the `Renewing -> Healthy` transition action: after that
    /// transition's CAS, before `Healthy.onEntry`.
    static final class Parker {
        final CountDownLatch parked = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        void park(CertificateBundle bundle) {
            parked.countDown();
            await(release, "release of the parked renewal callback");
        }
    }

    /// Reflectively reads the scheduler's Fsm through its private `ctx` (`Context` is package-private and holds the Fsm).
    @SuppressWarnings("unchecked")
    private static Fsm<SchedulerState, RenewalEvent> fsmOf(CertificateRenewalScheduler s) {
        try {
            Field ctxField = CertificateRenewalScheduler.class.getDeclaredField("ctx");
            ctxField.setAccessible(true);
            Object ctx = ctxField.get(s);
            Field fsmField = ctx.getClass().getDeclaredField("fsm");
            fsmField.setAccessible(true);
            return (Fsm<SchedulerState, RenewalEvent>) fsmField.get(ctx);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("reflection failed: " + e.getMessage(), e);
        }
    }

    @Test
    void immediateRenewalBranch_renewsOnceAndRearmsTheTimer() {
        var provider = new FailingProvider();
        // Past `notAfter` forces calculateRenewalDelay -> negative -> immediate-renewal branch.
        var scheduler = CertificateRenewalScheduler.certificateRenewalScheduler(
                provider, "node-immediate", "localhost",
                _ -> {}, Instant.now().minusSeconds(60));
        var fsm = fsmOf(scheduler);

        try {
            scheduler.start();

            // The immediate branch ticks at once and drives Healthy -> Renewing -> RetryBackoff on a scheduler thread. What
            // it promises is not a transient value but that it renews, and that it leaves the scheduler armed.
            awaitCondition(() -> provider.calls.get() == 1,
                           "immediate-renewal branch must attempt renewal exactly once");
            awaitCondition(() -> fsm.current() instanceof CertificateRenewalScheduler.RetryBackoff
                                 && fsm.entered().lifetime().size() == 1,
                           "after the immediate renewal fails, RetryBackoff.onEntry must arm its retry through the "
                           + "entry's lifetime — a scheduler with nothing armed never retries");
        } finally {
            scheduler.stop();
        }
    }

    @Test
    void futureRenewalBranch_armsATickThroughTheEntryLifetime() {
        var scheduler = CertificateRenewalScheduler.certificateRenewalScheduler(
                new FailingProvider(), "node-future", "localhost",
                _ -> {}, Instant.now().plusSeconds(3600));
        var fsm = fsmOf(scheduler);

        try {
            scheduler.start();

            assertThat(fsm.current()).isInstanceOf(CertificateRenewalScheduler.Healthy.class);
            assertThat(fsm.entered().lifetime().size())
                    .as("the long-delay branch arms exactly one tick, through the Healthy entry's lifetime")
                    .isEqualTo(1);
        } finally {
            scheduler.stop();
        }
    }

    @Test
    void stop_endsTheEntryLifetime_andLeavesNothingArmed() {
        var scheduler = CertificateRenewalScheduler.certificateRenewalScheduler(
                new FailingProvider(), "node-stop", "localhost",
                _ -> {}, Instant.now().plusSeconds(3600));
        var fsm = fsmOf(scheduler);

        scheduler.start();
        var healthy = fsm.entered();

        assertThat(healthy.lifetime().size()).as("precondition: Healthy armed one tick").isEqualTo(1);

        scheduler.stop();

        assertThat(healthy.lifetime().isEnded()).as("leaving Healthy ends its lifetime").isTrue();
        assertThat(healthy.lifetime().size()).as("the armed tick is cancelled with it").isZero();
        assertThat(fsm.entered().lifetime().size()).as("Stopped arms nothing").isZero();
    }

    /// #1191, sibling shape. `Healthy` (entered on a renewal success) is parked between its CAS and its `onEntry`; a tick
    /// wins `Healthy -> Renewing`, the renewal fails, and `RetryBackoff` is entered and armed. Only then does the parked
    /// `Healthy.onEntry` arm — it must be refused and must leave the live retry alone.
    @Test
    void healthyEntryOvertakenByTick_isRefused_andLeavesTheLiveRetryArmed() throws Exception {
        var provider = new SequencedProvider();
        var parker = new Parker();
        var scheduler = CertificateRenewalScheduler.certificateRenewalScheduler(
                provider, "node-overtake-tick", "localhost",
                parker::park, Instant.now().minusSeconds(60));
        var fsm = fsmOf(scheduler);

        try {
            scheduler.start();
            await(parker.parked, "the renewal callback to park");
            var healthy = fsm.entered();

            assertThat(healthy.state()).as("precondition: the parked winner's CAS has landed").isInstanceOf(CertificateRenewalScheduler.Healthy.class);
            assertThat(healthy.lifetime().size()).as("precondition: its onEntry has not armed yet").isZero();

            fsm.dispatch(new RenewalEvent.Tick());
            var retry = fsm.entered();

            assertThat(retry.state()).as("precondition: the second winner has completed").isInstanceOf(CertificateRenewalScheduler.RetryBackoff.class);
            assertThat(provider.calls.get()).as("precondition: the second renewal ran").isEqualTo(2);
            assertThat(retry.lifetime().size()).as("precondition: RetryBackoff armed its retry").isEqualTo(1);

            parker.release.countDown();
            Thread.sleep(LEAK_WINDOW_MILLIS);

            assertThat(provider.calls.get())
                    .as("#1191: a stale Healthy arm is refused; had it been kept, its tick (600 ms) would have re-entered Renewing")
                    .isEqualTo(2);
            assertThat(healthy.lifetime().size()).as("nothing armed by the exited Healthy entry is live").isZero();
            assertThat(fsm.entered()).as("the live retry was left alone").isSameAs(retry);
            assertThat(retry.lifetime().size()).as("the live retry is still armed").isEqualTo(1);
        } finally {
            parker.release.countDown();
            scheduler.stop();
        }
    }

    /// #1191, the reported shape. `Healthy` is parked between its CAS and its `onEntry`; `stop()` wins and completes
    /// (exit of `Healthy`, entry of `Stopped`). Only then does the parked `Healthy.onEntry` arm: it is refused.
    @Test
    void healthyEntryOvertakenByStop_isRefused_andNothingIsArmedOnTheStoppedScheduler() throws Exception {
        var provider = new SequencedProvider();
        var parker = new Parker();
        var scheduler = CertificateRenewalScheduler.certificateRenewalScheduler(
                provider, "node-overtake-stop", "localhost",
                parker::park, Instant.now().minusSeconds(60));
        var fsm = fsmOf(scheduler);

        try {
            scheduler.start();
            await(parker.parked, "the renewal callback to park");
            var healthy = fsm.entered();

            scheduler.stop();
            var stopped = fsm.entered();

            assertThat(stopped.state()).as("precondition: the second winner has completed").isInstanceOf(CertificateRenewalScheduler.Stopped.class);
            assertThat(healthy.lifetime().size()).as("precondition: the parked onEntry has not armed yet").isZero();

            parker.release.countDown();
            Thread.sleep(LEAK_WINDOW_MILLIS);

            assertThat(healthy.lifetime().size()).as("#1191: a registration after the state's exit is refused").isZero();
            assertThat(fsm.entered().lifetime().size()).as("#1191: nothing is armed on the stopped scheduler").isZero();
            assertThat(provider.calls.get()).as("no further renewal ran").isEqualTo(1);
        } finally {
            parker.release.countDown();
        }
    }

    @Test
    void configureShortValidity_inHealthy_reEntersHealthyOnAFreshLifetime() {
        var scheduler = CertificateRenewalScheduler.certificateRenewalScheduler(
                new FailingProvider(), "node-reconfigure", "localhost",
                _ -> {}, Instant.now().plusSeconds(3600));
        var fsm = fsmOf(scheduler);

        try {
            scheduler.start();
            var before = fsm.entered();

            assertThat(before.lifetime().size()).as("precondition: Healthy armed one tick").isEqualTo(1);

            scheduler.configureShortValidity(7200);
            var after = fsm.entered();

            assertThat(before.lifetime().isEnded()).as("the old entry's lifetime ended: its tick is cancelled").isTrue();
            assertThat(before.lifetime().size()).isZero();
            assertThat(after.lifetime()).as("a fresh entry, a fresh lifetime").isNotSameAs(before.lifetime());
            assertThat(after.state()).isInstanceOf(CertificateRenewalScheduler.Healthy.class);
            assertThat(after.lifetime().size()).as("the new entry armed exactly one tick").isEqualTo(1);
        } finally {
            scheduler.stop();
        }
    }

    @Test
    void configureShortValidity_whenNotHealthy_touchesNoTimer() {
        var scheduler = CertificateRenewalScheduler.certificateRenewalScheduler(
                new FailingProvider(), "node-reconfigure-idle", "localhost",
                _ -> {}, Instant.now().plusSeconds(3600));
        var fsm = fsmOf(scheduler);
        var before = fsm.entered();

        scheduler.configureShortValidity(60);

        assertThat(fsm.entered()).as("Idle is left as it was: no re-entry, no timer").isSameAs(before);
        assertThat(before.lifetime().size()).isZero();
    }

    private static void await(CountDownLatch latch, String what) {
        try {
            assertThat(latch.await(20, TimeUnit.SECONDS)).as(what).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted awaiting " + what, e);
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
}
