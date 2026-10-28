package org.pragmatica.config;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.config.DynamicConfigurationProvider.dynamicConfigurationProvider;

/// W-5b: the read protocol a binder uses. A read is accepted only if no overlay write started or finished while it
/// ran; otherwise it is retried, and after the bound it FAILS - it never returns the torn state.
class DynamicConfigurationProviderAtomicallyTest {
    private static final int ATTEMPTS = 8;
    private static final int KEYS = 200;

    private final DynamicConfigurationProvider provider = dynamicConfigurationProvider(ConfigurationProvider.builder()
                                                                                                           .build());

    @Test
    void atomically_returnsTheValue_onTheFirstAttempt_whenNothingWrites() {
        provider.put("a", "1");
        var calls = new AtomicInteger();

        var result = provider.atomically(() -> readCounting(calls));

        assertThat(result.isSuccess()).isTrue();
        result.onSuccess(value -> assertThat(value).isEqualTo("1"));
        assertThat(calls).hasValue(1);
    }

    @Test
    void atomically_retriesPastAWrite_thatLandsDuringTheRead() {
        provider.put("a", "1");
        var calls = new AtomicInteger();

        var result = provider.atomically(() -> readWhileWriting(calls, 1));

        result.onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()))
              .onSuccess(value -> assertThat(value).as("the accepted read saw the post-write state").isEqualTo("2"));
        assertThat(calls).as("first attempt rejected, second accepted").hasValue(2);
    }

    @Test
    void atomically_retriesPastARemove_thatLandsDuringTheRead() {
        provider.put("a", "1");
        var calls = new AtomicInteger();

        var result = provider.atomically(() -> readWhileRemoving(calls));

        result.onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()))
              .onSuccess(value -> assertThat(value).as("the accepted read saw the post-remove state").isEqualTo("none"));
        assertThat(calls).as("first attempt rejected, second accepted").hasValue(2);
    }

    @Test
    void atomically_fails_whenEveryAttemptIsOverwritten() {
        var calls = new AtomicInteger();

        var result = provider.atomically(() -> readWhileWriting(calls, Integer.MAX_VALUE));

        assertThat(result.isFailure()).as("a torn state is never returned").isTrue();
        result.onSuccess(value -> org.junit.jupiter.api.Assertions.fail("returned " + value))
              .onFailure(cause -> assertThat(cause).isSameAs(DynamicConfigurationProvider.OVERLAY_KEPT_CHANGING));
        assertThat(calls).as("bounded retries").hasValue(ATTEMPTS);
    }

    @Test
    void atomically_doesNotRunTheRead_whileAWriteWindowIsOpen() throws Exception {
        var inside = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var writer = Thread.ofPlatform().daemon().start(() -> provider.inWriteWindow(() -> holdOpen(inside, release)));
        var calls = new AtomicInteger();

        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
        var result = provider.atomically(() -> readCounting(calls));
        release.countDown();
        writer.join(5_000);

        assertThat(result.isFailure()).as("an odd version is never accepted").isTrue();
        assertThat(calls).as("the read is not even started while a write runs").hasValue(0);
    }

    @Test
    void atomically_succeeds_afterAWriteWhoseMutationThrew() {
        try {
            provider.inWriteWindow(() -> {
                throw new IllegalStateException("mutation failed");
            });
        } catch (IllegalStateException expected) {
            // the window must still be closed: an odd version left behind would fail every later read
        }

        var result = provider.atomically(() -> provider.getString("a").or("none"));

        assertThat(result.isSuccess()).as("a throwing write does not leave the window open").isTrue();
    }

    @Test
    void atomically_seesWritesMadeThroughAReloadedProvider() {
        provider.put("a", "1");
        var reloaded = provider.reload().unwrap();
        var calls = new AtomicInteger();

        var result = ((DynamicConfigurationProvider) reloaded).atomically(() -> readWhileWriting(calls, 1));

        result.onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()))
              .onSuccess(value -> assertThat(value).isEqualTo("2"));
        assertThat(calls).as("the write bracketed on the original is seen by the reloaded instance").hasValue(2);
    }

    @Test
    void atomically_neverReturnsATornRecord_whileTheOverlayIsRewritten() {
        var stop = new AtomicBoolean();
        var torn = new AtomicInteger();
        var accepted = new AtomicInteger();
        var writer = Thread.ofPlatform().daemon().start(() -> rewrite(stop));
        var deadline = System.nanoTime() + 3_000_000_000L;

        while (System.nanoTime() < deadline) {
            provider.atomically(this::readAllKeys)
                    .onSuccess(values -> record(values, accepted, torn));
        }
        stop.set(true);
        join(writer);

        assertThat(accepted.get()).as("some reads were accepted").isPositive();
        assertThat(torn.get()).as("torn records accepted").isZero();
    }

    private String readCounting(AtomicInteger calls) {
        calls.incrementAndGet();
        return provider.getString("a").or("");
    }

    /// Writes "2" over "a" during the first `writes` attempts: the read straddles a write.
    private String readWhileWriting(AtomicInteger calls, int writes) {
        provider.getString("a");
        if (calls.incrementAndGet() <= writes) {
            provider.put("a", "2");
        }
        return provider.getString("a").or("");
    }

    private String readWhileRemoving(AtomicInteger calls) {
        provider.getString("a");
        if (calls.incrementAndGet() == 1) {
            provider.remove("a");
        }
        return provider.getString("a").or("none");
    }

    private static Object holdOpen(CountDownLatch inside, CountDownLatch release) {
        inside.countDown();
        try {
            release.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    private ArrayList<Integer> readAllKeys() {
        var values = new ArrayList<Integer>();

        for (int i = 0; i < KEYS; i++) {
            values.add(provider.getString(key(i)).map(Integer::parseInt).or(0));
        }
        return values;
    }

    private static void record(ArrayList<Integer> values, AtomicInteger accepted, AtomicInteger torn) {
        accepted.incrementAndGet();
        if (!producedState(values)) {
            torn.incrementAndGet();
        }
    }

    /// Absent keys read as 0; round numbers start at 1, so round 1 is a prefix of 1s then 0s - still non-increasing.
    private static boolean producedState(ArrayList<Integer> values) {
        for (int i = 1; i < values.size(); i++) {
            if (values.get(i) > values.get(i - 1)) {
                return false;
            }
        }
        return values.getFirst() - values.getLast() <= 1;
    }

    private void rewrite(AtomicBoolean stop) {
        for (int round = 1; !stop.get(); round++) {
            for (int i = 0; i < KEYS; i++) {
                provider.put(key(i), Integer.toString(round));
            }
            LockSupport.parkNanos(300_000L);
        }
    }

    private static String key(int i) {
        return "k" + String.format("%03d", i);
    }

    private static void join(Thread writer) {
        try {
            writer.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
