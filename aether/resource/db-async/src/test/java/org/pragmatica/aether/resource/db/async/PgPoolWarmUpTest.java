// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db.async;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.postgres.net.Connectible;
import org.pragmatica.postgres.net.Connection;

import static org.assertj.core.api.Assertions.assertThat;

/// #2948: the warm-up contract against a scripted pool: it asks for exactly `min_connections` sessions, hands each one
/// back, never fails provisioning, and is bounded.
class PgPoolWarmUpTest {
    private static final Duration BOUND = Duration.ofMillis(200);
    // A hung warm-up must fail the test, not hang the build: await() is uninterruptible.
    private static final TimeSpan GUARD = TimeSpan.fromDuration(Duration.ofSeconds(5));

    private final AtomicInteger asked = new AtomicInteger();
    private final AtomicInteger closed = new AtomicInteger();

    private Connection connection() {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                                                   new Class<?>[]{Connection.class},
                                                   (_, method, _) -> answerConnection(method.getName()));
    }

    private Object answerConnection(String method) {
        return "close".equals(method)
               ? closeCounted()
               : null;
    }

    private Promise<Unit> closeCounted() {
        closed.incrementAndGet();

        return Promise.success(Unit.unit());
    }

    private Connectible pool(List<Promise<Connection>> answers) {
        return (Connectible) Proxy.newProxyInstance(Connectible.class.getClassLoader(),
                                                    new Class<?>[]{Connectible.class},
                                                    (_, method, _) -> answerPool(method.getName(), answers));
    }

    private Object answerPool(String method, List<Promise<Connection>> answers) {
        return "getConnection".equals(method)
               ? answers.get(asked.getAndIncrement())
               : null;
    }

    @Test
    void warmUp_opensExactlyMinConnections_andReturnsEachToThePool() {
        var answers = List.of(Promise.success(connection()), Promise.success(connection()), Promise.success(connection()));

        var result = PgPoolSettings.warmUp(pool(answers), 3, BOUND).await();

        assertThat(result.isSuccess()).isTrue();
        assertThat(asked.get()).isEqualTo(3);
        assertThat(closed.get()).isEqualTo(3);
    }

    @Test
    void warmUp_withMinZero_opensNothing() {
        var result = PgPoolSettings.warmUp(pool(List.of()), 0, BOUND).await();

        assertThat(result.isSuccess()).isTrue();
        assertThat(asked.get()).isZero();
    }

    @Test
    void warmUp_succeedsWithoutClosing_whenEveryOpenFails() {
        var failing = new ArrayList<Promise<Connection>>();

        failing.add(Causes.cause("refused").promise());
        failing.add(Causes.cause("refused").promise());

        var result = PgPoolSettings.warmUp(pool(failing), 2, BOUND).await();

        assertThat(result.isSuccess()).isTrue();
        assertThat(closed.get()).isZero();
    }

    @Test
    void warmUp_returnsAfterTheBound_whenAnOpenNeverCompletes() {
        var started = System.nanoTime();
        var result = PgPoolSettings.warmUp(pool(List.of(Promise.promise())), 1, BOUND).await(GUARD);

        assertThat(result.isSuccess()).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(BOUND).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void warmUp_stillReturnsASessionThatOpensAfterTheBound() throws InterruptedException {
        Promise<Connection> late = Promise.promise();

        PgPoolSettings.warmUp(pool(List.of(late)), 1, BOUND).await(GUARD);
        late.succeed(connection());
        awaitClosed(1);

        assertThat(closed.get()).isEqualTo(1);
    }

    private void awaitClosed(int expected) throws InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();

        while (closed.get() < expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }
}
