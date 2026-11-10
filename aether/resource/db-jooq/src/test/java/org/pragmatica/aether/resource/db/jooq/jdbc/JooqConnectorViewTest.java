// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db.jooq.jdbc;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.db.DatabaseConnectorError;
import org.pragmatica.aether.resource.db.jooq.JooqConnector;
import org.pragmatica.aether.resource.db.jooq.JooqConnectorView;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// T-VIEW for [JooqConnectorView] (#2973 D2.7): it declares every method of [JooqConnector], `stop()` and `close()`
/// included, and after its release it never reaches the pool.
class JooqConnectorViewTest {
    private static final TimeSpan TIMEOUT = timeSpan(5).seconds();

    private static boolean declaredByView(Method method) {
        try {
            return !JooqConnectorView.class.getMethod(method.getName(), method.getParameterTypes())
                                           .getDeclaringClass()
                                           .isInterface();
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    @Test
    void declaresEveryInterfaceMethod() {
        List<String> undeclared = Arrays.stream(JooqConnector.class.getMethods())
                                        .filter(method -> !Modifier.isStatic(method.getModifiers()))
                                        .filter(method -> !declaredByView(method))
                                        .map(Method::getName)
                                        .toList();

        assertThat(undeclared).isEmpty();
    }

    @Test
    void releaseRunsOnce_neverStopsThePool_andLaterCallsFail() {
        var poolCalls = new AtomicInteger();
        var pool = (JooqConnector) Proxy.newProxyInstance(JooqConnector.class.getClassLoader(),
                                                          new Class<?>[]{JooqConnector.class},
                                                          (_, _, _) -> countCall(poolCalls));
        var releases = new AtomicInteger();
        var view = JooqConnectorView.jooqConnectorView(pool, () -> countRelease(releases));

        view.close().await(TIMEOUT);
        view.stop().await(TIMEOUT);

        assertThat(view.isHealthy().await(TIMEOUT).isFailure()).isTrue();
        assertThat(view.execute(null).await(TIMEOUT).isFailure()).isTrue();
        assertThat(releases.get()).isEqualTo(1);
        assertThat(poolCalls.get()).as("nothing reached the pool, stop() included").isZero();
    }

    /// Every async method of [JooqConnector] (fetchOne, fetchOptional, fetch, execute, transactional, isHealthy) fails
    /// with `HandleReleased` after the release, on a pool whose every call succeeds; before it, each reaches the pool.
    @Test
    void afterRelease_everyAsyncMethodFailsWithHandleReleased_onASucceedingPool() throws Exception {
        var reached = new AtomicInteger();
        var stops = new AtomicInteger();
        var pool = (JooqConnector) Proxy.newProxyInstance(JooqConnector.class.getClassLoader(),
                                                          new Class<?>[]{JooqConnector.class},
                                                          (_, method, _) -> succeed(method, reached, stops));
        var view = JooqConnectorView.jooqConnectorView(pool, Promise::unitPromise);
        var methods = Arrays.stream(JooqConnector.class.getMethods())
                            .filter(method -> !Modifier.isStatic(method.getModifiers()))
                            .filter(method -> Promise.class.isAssignableFrom(method.getReturnType()))
                            .filter(method -> !List.of("stop", "close").contains(method.getName()))
                            .toList();

        assertThat(methods).as("the instrument sees the interface's async methods").hasSizeGreaterThanOrEqualTo(6);

        for (var method : methods) {
            assertThat(call(method, view).isSuccess()).as("%s before release", method.getName()).isTrue();
        }

        assertThat(reached.get()).isEqualTo(methods.size());
        view.close().await(TIMEOUT);

        for (var method : methods) {
            var result = call(method, view);

            assertThat(result.isFailure()).as("%s after release", method.getName()).isTrue();
            result.onFailure(cause -> assertThat(cause).isSameAs(DatabaseConnectorError.HandleReleased.INSTANCE));
        }

        assertThat(reached.get()).as("after the release nothing reached the pool").isEqualTo(methods.size());
        assertThat(stops.get()).as("the view never stops the pool").isZero();
    }

    private static org.pragmatica.lang.Result<?> call(Method method, JooqConnector view) throws Exception {
        var args = new Object[method.getParameterCount()];

        for (var i = 0; i < args.length; i++) {
            var type = method.getParameterTypes()[i];

            args[i] = type.isPrimitive()
                      ? Array.get(Array.newInstance(type, 1), 0)
                      : null;
        }

        return ((Promise<?>) method.invoke(view, args)).await(TIMEOUT);
    }

    private static Object succeed(Method method, AtomicInteger reached, AtomicInteger stops) {
        if (List.of("stop", "close").contains(method.getName())) {
            stops.incrementAndGet();

            return Promise.unitPromise();
        }

        if (Promise.class.isAssignableFrom(method.getReturnType())) {
            reached.incrementAndGet();

            return Promise.success(Unit.unit());
        }

        return null;
    }

    /// `dsl()` is a synchronous accessor (G1): it keeps returning the pool's DSLContext after the release. The test pins
    /// that, so a change to guard it is a deliberate one.
    @Test
    void dsl_afterRelease_stillReturnsThePoolsContext() {
        var context = org.jooq.impl.DSL.using(org.jooq.SQLDialect.POSTGRES);
        var pool = (JooqConnector) Proxy.newProxyInstance(JooqConnector.class.getClassLoader(),
                                                          new Class<?>[]{JooqConnector.class},
                                                          (_, method, _) -> method.getName().equals("dsl")
                                                                            ? context
                                                                            : Promise.unitPromise());
        var view = JooqConnectorView.jooqConnectorView(pool, Promise::unitPromise);

        view.close().await(TIMEOUT);

        assertThat(view.dsl()).isSameAs(context);
    }

    private static Object countCall(AtomicInteger calls) {
        calls.incrementAndGet();

        return Promise.success(Unit.unit());
    }

    private static Promise<Unit> countRelease(AtomicInteger releases) {
        releases.incrementAndGet();

        return Promise.unitPromise();
    }
}
