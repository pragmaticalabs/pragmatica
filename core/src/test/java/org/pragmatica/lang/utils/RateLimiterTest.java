package org.pragmatica.lang.utils;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.RateLimiter.RateLimiterError.LimitExceeded;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

class RateLimiterTest {

    private RateLimiter rateLimiter;
    private TestTimeSource timeSource;

    private static class TestTimeSource implements TimeSource {
        private TimeSpan currentTime = timeSpan(0).nanos();

        @Override
        public long nanoTime() {
            return currentTime.nanos();
        }

        public void advanceTime(long millis) {
            currentTime = currentTime.plus(millis, TimeUnit.MILLISECONDS);
        }

        public void advanceNanos(long nanos) {
            currentTime = currentTime.plus(nanos, TimeUnit.NANOSECONDS);
        }
    }

    @BeforeEach
    void setUp() {
        timeSource = new TestTimeSource();

        rateLimiter = RateLimiter.builder()
                .rate(5)
                .period(timeSpan(1).seconds())
                .burst(0)
                .timeSource(timeSource)
                .unwrap();
    }

    @Test
    void shouldExecuteOperationWhenPermitAvailable() {
        var callCount = new AtomicInteger(0);

        rateLimiter.execute(() -> {
                    callCount.incrementAndGet();
                    return Promise.success("Success");
                })
                .await()
                .onFailureRun(Assertions::fail)
                .onSuccess(value -> assertEquals("Success", value));

        assertEquals(1, callCount.get());
    }

    @Test
    void shouldExhaustPermits() {
        for (int i = 0; i < 5; i++) {
            rateLimiter.execute(() -> Promise.success("OK"))
                    .await()
                    .onFailureRun(Assertions::fail);
        }

        // Next request should fail
        rateLimiter.execute(() -> Promise.success("Should not execute"))
                .await()
                .onSuccessRun(Assertions::fail)
                .onFailure(cause -> assertInstanceOf(LimitExceeded.class, cause));
    }

    @Test
    void shouldRejectExecutionWhenNoPermits() {
        // Exhaust all permits
        for (int i = 0; i < 5; i++) {
            rateLimiter.execute(() -> Promise.success("OK")).await();
        }

        var callCount = new AtomicInteger(0);

        rateLimiter.execute(() -> {
                    callCount.incrementAndGet();
                    return Promise.success("Should not execute");
                })
                .await()
                .onSuccessRun(Assertions::fail)
                .onFailure(cause -> assertInstanceOf(LimitExceeded.class, cause));

        assertEquals(0, callCount.get(), "Operation should not be executed when rate limited");
    }

    @Test
    void shouldRefillPermitsAfterPeriod() {
        // Exhaust all permits
        for (int i = 0; i < 5; i++) {
            rateLimiter.execute(() -> Promise.success("OK")).await();
        }

        // Verify exhausted
        rateLimiter.execute(() -> Promise.success("Fail"))
                .await()
                .onSuccessRun(Assertions::fail);

        // Advance time by one period
        timeSource.advanceTime(1000);

        // Should be able to execute again
        rateLimiter.execute(() -> Promise.success("Success after refill"))
                .await()
                .onFailureRun(Assertions::fail)
                .onSuccess(value -> assertEquals("Success after refill", value));
    }

    @Test
    void shouldRefillContinuouslyOneTokenPerQuantum() {
        // rate=5/sec → nanosPerToken = 200ms. Exhaust, then advance 200ms — exactly one token.
        for (int i = 0; i < 5; i++) {
            assertTrue(rateLimiter.tryAcquire());
        }
        assertFalse(rateLimiter.tryAcquire());

        timeSource.advanceTime(200);

        assertTrue(rateLimiter.tryAcquire(), "One token should be available after 200ms");
        assertFalse(rateLimiter.tryAcquire(), "Only one token should be available after 200ms");
    }

    @Test
    void shouldNotRefillBeforeQuantumElapsed() {
        for (int i = 0; i < 5; i++) {
            assertTrue(rateLimiter.tryAcquire());
        }

        timeSource.advanceTime(199); // just below nanosPerToken (200ms)

        assertFalse(rateLimiter.tryAcquire(),
                    "No token should be available before one full quantum elapsed");
    }

    @Test
    void shouldAllowBurstCapacity() {
        var limiterWithBurst = RateLimiter.builder()
                .rate(5)
                .period(timeSpan(1).seconds())
                .burst(3)
                .timeSource(timeSource)
                .unwrap();

        // Should be able to execute rate + burst = 8 times
        for (int i = 0; i < 8; i++) {
            limiterWithBurst.execute(() -> Promise.success("OK"))
                    .await()
                    .onFailureRun(Assertions::fail);
        }

        // 9th should fail
        limiterWithBurst.execute(() -> Promise.success("Fail"))
                .await()
                .onSuccessRun(Assertions::fail)
                .onFailure(cause -> assertInstanceOf(LimitExceeded.class, cause));
    }

    @Test
    void shouldProvideRetryAfterInLimitExceeded() {
        // Exhaust all permits
        for (int i = 0; i < 5; i++) {
            rateLimiter.execute(() -> Promise.success("OK")).await();
        }

        rateLimiter.execute(() -> Promise.success("Fail"))
                .await()
                .onSuccessRun(Assertions::fail)
                .onFailure(cause -> {
                    if (cause instanceof LimitExceeded limited) {
                        assertTrue(limited.retryAfter().nanos() > 0,
                                "Retry after should be positive");
                        assertTrue(limited.retryAfter().millis() <= 1000,
                                "Retry after should not exceed period");
                    } else {
                        Assertions.fail("Unexpected cause type: " + cause.getClass().getName());
                    }
                });
    }

    @Test
    void retryAfterShouldBeBoundedByQuantum() {
        // nanosPerToken at rate=5/sec, period=1s is 200ms.
        for (int i = 0; i < 5; i++) {
            assertTrue(rateLimiter.tryAcquire());
        }
        assertFalse(rateLimiter.tryAcquire());

        var retry = rateLimiter.retryAfter();
        assertTrue(retry.nanos() > 0, "retryAfter must be positive");
        assertTrue(retry.nanos() <= timeSpan(200).millis().nanos(),
                   "retryAfter must not exceed one quantum (200ms), was " + retry);
    }

    @Test
    void shouldHandleConcurrentTryAcquireDeterministically() throws InterruptedException {
        // Frozen time: no refill possible. With capacity K, exactly K of N threads should succeed.
        var limiter = RateLimiter.builder()
                .rate(100)
                .period(timeSpan(1).seconds())
                .burst(0)
                .timeSource(timeSource)
                .unwrap();

        int contenders = 256;
        int capacity = 100;

        var start = new CountDownLatch(1);
        var done = new CountDownLatch(contenders);
        var successCount = new AtomicInteger(0);
        var failureCount = new AtomicInteger(0);

        for (int i = 0; i < contenders; i++) {
            Thread.startVirtualThread(() -> {
                try {
                    start.await();
                    if (limiter.tryAcquire()) {
                        successCount.incrementAndGet();
                    } else {
                        failureCount.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS), "Contenders should finish quickly");

        assertEquals(capacity, successCount.get(),
                     "Exactly capacity permits should be admitted under contention");
        assertEquals(contenders - capacity, failureCount.get(),
                     "All other requests should be rejected");
    }

    @Test
    void shouldComposeWithOtherPromises() {
        rateLimiter.execute(() -> Promise.success(42))
                .map(value -> value * 2)
                .flatMap(value -> Promise.success("Result: " + value))
                .await()
                .onFailureRun(Assertions::fail)
                .onSuccess(value -> assertEquals("Result: 84", value));
    }

    @Test
    void shouldHaveCorrectErrorMessages() {
        // Exhaust permits
        for (int i = 0; i < 5; i++) {
            rateLimiter.execute(() -> Promise.success("OK")).await();
        }

        rateLimiter.execute(() -> Promise.success("Fail"))
                .await()
                .onFailure(cause -> {
                    assertTrue(cause.message().contains("Rate limit exceeded"));
                    assertTrue(cause.message().contains("Retry after"));
                });
    }

    @Test
    void shouldNotExceedMaxTokensAfterLongIdle() {
        var limiterWithBurst = RateLimiter.builder()
                .rate(5)
                .period(timeSpan(1).seconds())
                .burst(3)
                .timeSource(timeSource)
                .unwrap();

        // Advance time by 10 periods without using permits
        timeSource.advanceTime(10_000);

        // Trigger refill by executing
        int successCount = 0;
        for (int i = 0; i < 20; i++) {
            var result = limiterWithBurst.execute(() -> Promise.success("OK")).await();
            if (result.isSuccess()) {
                successCount++;
            }
        }

        // Should not exceed rate + burst = 8
        assertEquals(8, successCount, "Should not exceed max tokens");
    }

    @Test
    void shouldCreateSimpleRateLimiter() {
        // Uses real system clock — verify the wiring works end-to-end on the default time source.
        var simpleLimiter = RateLimiter.rateLimiter(10, timeSpan(1).seconds()).unwrap();

        for (int i = 0; i < 10; i++) {
            simpleLimiter.execute(() -> Promise.success("OK"))
                    .await()
                    .onFailureRun(Assertions::fail);
        }

        simpleLimiter.execute(() -> Promise.success("Fail"))
                .await()
                .onSuccessRun(Assertions::fail)
                .onFailure(cause -> assertInstanceOf(LimitExceeded.class, cause));
    }

    @Test
    void shouldRecoverFullCapacityOverWideTimeAdvance() {
        // Wide advance after burst: tokens cap at maxTokens, not unbounded.
        var limiter = RateLimiter.builder()
                .rate(100)
                .period(timeSpan(1).seconds())
                .burst(50)
                .timeSource(timeSource)
                .unwrap();

        timeSource.advanceTime(60_000); // 60 periods

        int successes = 0;
        for (int i = 0; i < 500; i++) {
            if (limiter.tryAcquire()) {
                successes++;
            }
        }
        assertEquals(150, successes, "Capacity must cap at rate + burst regardless of idle duration");
    }

    /// #1315 — a caller that samples the clock, is preempted while another caller refills and exhausts the
    /// bucket, and then resumes with its older sample, must not mint permits. The masked difference
    /// `(now - lastRefill) & TIME_MASK` turns that small negative interval into ~2^48 ns, i.e. a full
    /// refill. The schedule is deterministic: the other caller runs INSIDE the first caller's clock read,
    /// after the value it will return is fixed — exactly a preemption between sample and use. The first
    /// caller's CAS then loses, so this also covers the retry path.
    ///
    /// Mutations that redden it: sample `now` once before the loop (the pre-fix code), or sample it once
    /// and reuse it across a lost CAS.
    @Test
    void staleTimeSample_afterAnotherCallerExhaustsTheBucket_mintsNoPermit() {
        var clock = new java.util.concurrent.atomic.AtomicLong();
        var interleave = new java.util.concurrent.atomic.AtomicReference<Runnable>();
        TimeSource source = () -> {
            var sampled = clock.get();
            var other = interleave.getAndSet(null);

            if (other != null) {
                other.run();
            }
            return sampled;
        };
        var limiter = RateLimiter.builder()
                .rate(2)
                .period(timeSpan(1).seconds())
                .burst(0)
                .timeSource(source)
                .unwrap();
        var otherGranted = new AtomicInteger();

        clock.set(TimeUnit.MILLISECONDS.toNanos(9_900));
        interleave.set(() -> {
            clock.set(TimeUnit.MILLISECONDS.toNanos(10_000));
            for (int i = 0; i < 3; i++) {
                if (limiter.tryAcquire()) {
                    otherGranted.incrementAndGet();
                }
            }
        });

        var staleGranted = limiter.tryAcquire();

        assertNull(interleave.get(), "control: the other caller really ran inside the first caller's clock read");
        assertEquals(2, otherGranted.get(), "control: the other caller took the whole bucket (rate 2, burst 0)");
        assertFalse(staleGranted, "#1315: a stale time sample must not refill an exhausted bucket");
        assertFalse(limiter.tryAcquire(), "no permit is due 0 ms after the bucket emptied");
    }

    /// #1316 — construction refuses, with a typed cause, every configuration the packed
    /// `[tokens:16 | lastRefill:48]` state or the refill arithmetic cannot represent. Before the fix each of
    /// these either truncated silently, divided by zero on first use, or built a limiter that never refills.
    @Test
    void unrepresentableConfigurations_areRefusedWithATypedCause() {
        assertInvalid(0, timeSpan(1).seconds(), 0, "zero rate");
        assertInvalid(-5, timeSpan(1).seconds(), 0, "negative rate");
        assertInvalid(10, timeSpan(1).seconds(), -1, "negative burst");
        assertInvalid(65_536, timeSpan(1).seconds(), 0, "capacity 65536 by rate");
        assertInvalid(65_000, timeSpan(1).seconds(), 536, "capacity 65536 by rate + burst");
        assertInvalid(10, null, 0, "missing period");
        assertInvalid(10, timeSpan(0).nanos(), 0, "zero period");
        assertInvalid(10, timeSpan(9).nanos(), 0, "sub-nanosecond per permit (period / rate == 0)");
        assertInvalid(1, timeSpan(4).days(), 0, "one permit per 2^48 ns or more (never refills)");
        assertTrue(RateLimiter.builder().rate(1).period(timeSpan(1).seconds()).burst(0).timeSource(null).isFailure(),
                   "missing time source");
    }

    /// #1316 boundary — the largest representable capacity (65535) is accepted and grants exactly that many
    /// permits, and refills correctly: nothing is lost to truncation at the top of the 16-bit field.
    @Test
    void maximumRepresentableCapacity_grantsExactlyItsPermits_andRefills() {
        var limiter = RateLimiter.builder()
                .rate(62_500)
                .period(timeSpan(1).seconds())
                .burst(3_035)
                .timeSource(timeSource)
                .unwrap();

        assertEquals(65_535, drain(limiter), "capacity rate + burst = 65535 must be granted in full");
        timeSource.advanceTime(1_000);
        assertEquals(62_500, drain(limiter), "one period refills `rate` permits (16 us each, exact)");
    }

    private void assertInvalid(int rate, TimeSpan period, int burst, String what) {
        var result = RateLimiter.builder().rate(rate).period(period).burst(burst).timeSource(timeSource);

        assertTrue(result.isFailure(), what + ": must be refused, got " + result);
        result.onFailure(cause -> assertInstanceOf(RateLimiter.RateLimiterError.InvalidConfiguration.class, cause, what));
    }

    private static int drain(RateLimiter limiter) {
        int granted = 0;

        while (limiter.tryAcquire()) {
            granted++;
        }
        return granted;
    }
}
