// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment;

import java.util.concurrent.locks.LockSupport;

import org.pragmatica.lang.Promise;

/// A promise that resolves LATER, on another thread. A fake that returns `Promise.success(...)` hides every ordering
/// defect between a promise and its `on*` handlers (the handler then runs inline on the caller), so a probe of such a
/// defect resolves its fake through this.
public final class LateResolution {
    private static final long DELAY_NANOS = 2_000_000L;

    private LateResolution() {}

    public static <T> Promise<T> later(T value) {
        var promise = Promise.<T> promise();

        Thread.ofVirtual().start(() -> resolveAfterDelay(promise, value));

        return promise;
    }

    private static <T> void resolveAfterDelay(Promise<T> promise, T value) {
        LockSupport.parkNanos(DELAY_NANOS);
        promise.succeed(value);
    }
}
