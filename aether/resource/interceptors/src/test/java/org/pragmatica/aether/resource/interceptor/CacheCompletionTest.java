// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

class CacheCompletionTest {
    private record DelayedBackend(Promise<Unit> maintenance, CountDownLatch entered) implements CacheBackend {
        public Promise<Option<Object>> get(Object key) { return Promise.success(Option.none()); }
        public Promise<Unit> put(Object key, Object value) { entered.countDown(); return maintenance; }
        public Promise<Unit> remove(Object key) { entered.countDown(); return maintenance; }
    }

    @Test void cacheAside_waitsForPopulation_beforeNextSequentialInvalidation() throws Exception {
        proveMaintenanceCompletesFirst(CacheStrategy.CACHE_ASIDE);
    }

    @Test void writeAround_waitsForInvalidation_beforeNextSequentialRead() throws Exception {
        proveMaintenanceCompletesFirst(CacheStrategy.WRITE_AROUND);
    }

    private void proveMaintenanceCompletesFirst(CacheStrategy strategy) throws Exception {
        var maintenance = Promise.<Unit>promise();
        var entered = new CountDownLatch(1);
        var completed = new AtomicBoolean();
        var intercepted = new CacheMethodInterceptor(new DelayedBackend(maintenance, entered), strategy, Fn1.id())
            .intercept((String key) -> Promise.success("value"));
        var result = intercepted.apply("key").withResult(_ -> completed.set(true));
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(completed).isFalse();
        maintenance.succeed(Unit.unit());
        assertThat(result.await(timeSpan(2).seconds()).unwrap()).isEqualTo("value");
    }
}
