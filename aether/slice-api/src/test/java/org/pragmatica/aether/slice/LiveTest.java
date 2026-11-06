// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LiveTest {
    /// A Watched the test drives by hand: the registration value, then whatever `push` delivers.
    private static final class Manual<T> implements Watched<T> {
        private final T first;
        private final AtomicReference<Fn1<Promise<Unit>, T>> callback = new AtomicReference<>();

        Manual(T first) {
            this.first = first;
        }

        @Override
        public Result<Subscription> watch(Fn1<Promise<Unit>, T> cb) {
            callback.set(cb);
            return cb.apply(first).await().map(_ -> Subscription.noop());
        }

        void push(T value) {
            callback.get().apply(value);
        }
    }

    @Test
    void live_get_isNeverEmpty_afterRegistration() {
        var live = Live.live(Watched.constant("v"));

        assertThat(live.isSuccess()).isTrue();
        live.onFailure(cause -> Assertions.fail(cause.message()))
            .onSuccess(value -> assertThat(value.get()).isEqualTo("v"));
    }

    @Test
    void live_get_seesTheNewValue_afterADelivery() {
        var manual = new Manual<>("first");
        var live = Live.live(manual).unwrap();

        manual.push("second");

        assertThat(live.get()).isEqualTo("second");
    }

    @Test
    void live_fails_whenTheWatchedDeliversNothingAtRegistration() {
        Watched<String> silent = _ -> Result.success(Watched.Subscription.noop());

        var result = Live.live(silent);

        assertThat(result.isFailure()).isTrue();
        result.onSuccess(_ -> Assertions.fail("a Live with nothing to show must not exist"))
              .onFailure(cause -> assertThat(cause).isSameAs(Live.NOTHING_DELIVERED));
    }

    @Test
    void live_fails_whenTheWatchedFails() {
        Watched<String> refusing = _ -> Causes.cause("no value").result();

        assertThat(Live.live(refusing).isFailure()).isTrue();
    }

    @Test
    void live_get_neverGoesBackwards_underConcurrentReaders() throws Exception {
        var manual = new Manual<>(0);
        var live = Live.live(manual).unwrap();
        var stop = new AtomicBoolean();
        var observed = new CopyOnWriteArrayList<List<Integer>>();
        var readers = new ArrayList<Thread>();

        for (int r = 0; r < 4; r++) {
            readers.add(Thread.ofPlatform().start(() -> observed.add(read(live, stop))));
        }
        for (int i = 1; i <= 200; i++) {
            manual.push(i);
        }
        stop.set(true);
        for (var reader : readers) {
            reader.join(5_000);
        }

        assertThat(observed).hasSize(4);
        assertThat(observed).allSatisfy(values -> assertThat(values).isSorted());
        assertThat(live.get()).isEqualTo(200);
    }

    private static List<Integer> read(Live<Integer> live, AtomicBoolean stop) {
        var values = new ArrayList<Integer>();

        while (!stop.get()) {
            values.add(live.get());
        }
        return values;
    }

    @Test
    void constant_deliversOnce_andFailsWhenTheRegistrationCallbackThrowsOrFails() {
        var delivered = new ArrayList<String>();

        var ok = Watched.constant("v").watch(value -> {
            delivered.add(value);
            return Promise.unitPromise();
        });
        var thrown = Watched.constant("v").watch(_ -> {
            throw new IllegalStateException("callback failed");
        });
        var failed = Watched.constant("v").watch(_ -> Causes.cause("refused").promise());

        assertThat(ok.isSuccess()).isTrue();
        assertThat(delivered).containsExactly("v");
        assertThat(thrown.isFailure()).isTrue();
        assertThat(failed.isFailure()).isTrue();
    }
}
