// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db.jooq.jdbc;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
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

    private static Object countCall(AtomicInteger calls) {
        calls.incrementAndGet();

        return Promise.success(Unit.unit());
    }

    private static Promise<Unit> countRelease(AtomicInteger releases) {
        releases.incrementAndGet();

        return Promise.unitPromise();
    }
}
