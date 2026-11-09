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
import org.pragmatica.postgres.PgConnectionPool;
import org.pragmatica.postgres.ProtocolStream;
import org.pragmatica.postgres.message.Message;
import org.pragmatica.postgres.message.backend.Authentication;
import org.pragmatica.postgres.net.ConnectibleBuilder;
import org.pragmatica.postgres.net.Connection;
import org.pragmatica.postgres.net.netty.NettyConnectibleBuilder;

import static org.assertj.core.api.Assertions.assertThat;

/// #2948 / B1: the warm-up against the REAL `PgConnectionPool` (its pending-waiter queue is the mechanism under test), with a
/// scripted protocol stream whose first `hung` connection attempts never answer and whose later ones succeed.
///
/// The pool hands every newly opened connection to its oldest waiter. A warm-up whose own sessions hang must not turn
/// those waiters into holders that keep later connections: after the warm-up bound a real request has to be served.
class PgPoolWarmUpPoolTest {
    private static final Duration BOUND = Duration.ofMillis(200);
    // await() is uninterruptible: a request that is never served must fail the test, not hang the build.
    private static final TimeSpan GUARD = TimeSpan.fromDuration(Duration.ofSeconds(5));

    private final AtomicInteger connects = new AtomicInteger();
    private final List<Promise<Message>> hungAnswers = new ArrayList<>();

    private ProtocolStream stream(int hung) {
        return (ProtocolStream) Proxy.newProxyInstance(ProtocolStream.class.getClassLoader(),
                                                       new Class<?>[]{ProtocolStream.class},
                                                       (_, method, _) -> answer(method.getName(), hung));
    }

    private Object answer(String method, int hung) {
        return switch (method) {
            case "connect" -> connectAnswer(hung);
            case "isConnected" -> Boolean.TRUE;
            case "close" -> Promise.success(Unit.unit());
            default -> null;
        };
    }

    private Promise<Message> connectAnswer(int hung) {
        return connects.getAndIncrement() < hung
               ? hang()
               : Promise.success(Authentication.OK);
    }

    private Promise<Message> hang() {
        Promise<Message> never = Promise.promise();

        hungAnswers.add(never);

        return never;
    }

    private PgConnectionPool pool(int hung, int max) {
        var builder = new BuilderWithProperties();

        builder.maxConnections(max);

        return new PgConnectionPool(builder.configuration(), () -> Promise.success(stream(hung)));
    }

    @Test
    void warmUp_doesNotCaptureLaterConnections_whenItsOwnOpensHang() {
        var pool = pool(3, 5);

        assertThat(PgPoolSettings.warmUp(pool, 3, BOUND).await(GUARD).isSuccess()).isTrue();

        var first = pool.getConnection().await(GUARD);
        var second = pool.getConnection().await(GUARD);

        assertThat(first.isSuccess()).as("a request after the warm-up bound is served (slot 1)").isTrue();
        assertThat(second.isSuccess()).as("a request after the warm-up bound is served (slot 2)").isTrue();
        first.onSuccess(Connection::close);
        second.onSuccess(Connection::close);
        assertThat(pool.getConnection().await(GUARD).isSuccess()).as("a released connection is reusable").isTrue();
    }

    // Control for the first test: min 0 warms nothing, the first three real requests ride the hung opens (and release what
    // they get), and the fourth is served. B1 is the warm-up's doing, not the pool's.
    @Test
    void warmUp_withMinZero_leavesThePoolUsable_afterHungOpens() {
        var pool = pool(3, 5);

        assertThat(PgPoolSettings.warmUp(pool, 0, BOUND).await(GUARD).isSuccess()).isTrue();
        for (var i = 0; i < 3; i++) {
            pool.getConnection().onSuccess(Connection::close);
        }

        assertThat(pool.getConnection().await(GUARD).isSuccess()).isTrue();
        assertThat(connects.get()).isEqualTo(4);
    }

    // max == hung opens: no slot is free to open a session, so the request can only be served by a late connection coming
    // back through the pool (a waiter left over from the warm-up must pass it on or park it idle, not keep it).
    @Test
    void warmUp_passesALateConnectionOn_toTheRealRequest() {
        var pool = pool(2, 2);

        assertThat(PgPoolSettings.warmUp(pool, 2, BOUND).await(GUARD).isSuccess()).isTrue();

        var request = pool.getConnection();

        hungAnswers.forEach(answer -> answer.succeed(Authentication.OK));

        assertThat(request.await(GUARD).isSuccess()).isTrue();
        assertThat(connects.get()).as("no session beyond the two hung opens was possible").isEqualTo(2);
    }

    // The Conduit bench shape: a fixed pool, min_connections == max_connections == 32, with the first 8 opens hanging.
    // After the warm-up bound requests are served from the 24 sessions that opened; once the hung opens answer the pool
    // holds exactly 32 sessions (all 32 can be checked out at once), never more, and the 33rd request waits.
    @Test
    void warmUp_atFixedPool32_servesRequests_andReachesAllWarmSessions_withoutExceedingMax() {
        var pool = pool(8, 32);

        assertThat(PgPoolSettings.warmUp(pool, 32, BOUND).await(GUARD).isSuccess()).isTrue();

        var early = pool.getConnection().await(GUARD);

        assertThat(early.isSuccess()).as("a request after the warm-up bound is served with 8 opens still hung").isTrue();
        early.onSuccess(Connection::close);
        hungAnswers.forEach(answer -> answer.succeed(Authentication.OK));

        var held = new ArrayList<Promise<Connection>>();

        for (var i = 0; i < 32; i++) {
            held.add(pool.getConnection());
        }
        for (var request : held) {
            assertThat(request.await(GUARD).isSuccess()).as("all 32 sessions are available").isTrue();
        }

        var beyondMax = pool.getConnection();

        assertThat(beyondMax.isResolved()).as("the 33rd request waits: max is not exceeded").isFalse();
        assertThat(connects.get()).as("exactly max sessions were ever opened").isEqualTo(32);
        held.getFirst().await(GUARD).onSuccess(Connection::close);
        assertThat(beyondMax.await(GUARD).isSuccess()).as("a released session serves the waiting request").isTrue();
    }

    private static final class BuilderWithProperties extends NettyConnectibleBuilder {
        ConnectibleBuilder.ConnectibleConfiguration configuration() {
            return properties;
        }
    }
}
