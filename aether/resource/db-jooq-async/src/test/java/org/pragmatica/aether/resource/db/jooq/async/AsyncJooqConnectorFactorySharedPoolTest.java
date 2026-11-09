// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db.jooq.async;

import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.db.jooq.JooqConnector;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2973 D2.7 for AsyncJooqConnectorFactory: the view it hands a holder is not the pool, its stop() releases that holder
/// and never stops the pool.
///
/// GAP (D2.8, recorded here on purpose): `provision` is wrapped in the factory's class loader like the other six, but
/// this connector builds its pg-async pool without a warm-up, and the pool's event-loop threads start on first use, not
/// in `provision`. No thread exists after `provision` to inspect, so the wrap has no observable effect today and a test
/// of it would pass vacuously; it is pinned only by the code and by this note.
class AsyncJooqConnectorFactorySharedPoolTest {
    private static final TimeSpan TIMEOUT = timeSpan(10).seconds();

    private final AsyncJooqConnectorFactory factory = new AsyncJooqConnectorFactory();

    @Test
    void view_isNotThePool_stopReleases_andNeverStopsThePool() {
        var poolStops = new AtomicInteger();
        var releases = new AtomicInteger();
        var pool = (JooqConnector) Proxy.newProxyInstance(JooqConnector.class.getClassLoader(),
                                                   new Class<?>[]{JooqConnector.class},
                                                   (_, method, _) -> answer(method.getName(), poolStops));
        var view = factory.view(pool, () -> counted(releases));

        assertThat(view).isNotSameAs(pool);

        view.stop().await(TIMEOUT);
        view.stop().await(TIMEOUT);

        assertThat(releases.get()).as("the first stop() released this holder, once").isEqualTo(1);
        assertThat(poolStops.get()).as("the pool's own stop() is never called by a view").isZero();
    }

    private static Object answer(String method, AtomicInteger poolStops) {
        if (Set.of("stop", "close").contains(method)) {
            poolStops.incrementAndGet();
        }

        return Promise.unitPromise();
    }

    private static Promise<Unit> counted(AtomicInteger releases) {
        releases.incrementAndGet();

        return Promise.unitPromise();
    }
}
