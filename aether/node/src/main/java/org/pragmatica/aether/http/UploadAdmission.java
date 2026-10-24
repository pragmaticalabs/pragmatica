// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.aether.config.UploadConfig;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;


/// The concurrency caps on uploads in flight (`[app-http.upload] max_concurrent` and `max_concurrent_per_principal`).
///
/// A permit is taken when an upload request is classified, on its HEADERS, and returned when its body has been received
/// or the request ends without it, so the caps bound the bodies being buffered at once. Classification and release run on
/// different event loops: the node count is an atomic and the per-principal counts change only inside the map's atomic
/// `compute`, so neither a grant nor a release is ever lost.
///
/// The node cap reports its own state, once per episode: `onSaturated` runs on the first refusal after the node was below
/// its cap, `onRestored` on the first release after that (a release always leaves the node below its cap, because the
/// episode began with it at the cap). A refusal for one principal's cap reports nothing.
final class UploadAdmission {
    static final Cause NODE_LIMIT = Causes.cause("Too many concurrent uploads on this node");
    static final Cause PRINCIPAL_LIMIT = Causes.cause("Too many concurrent uploads for this principal");

    private final int maxConcurrent;
    private final int maxConcurrentPerPrincipal;
    private final Runnable onSaturated;
    private final Runnable onRestored;
    private final AtomicBoolean saturated = new AtomicBoolean();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final ConcurrentHashMap<String, Integer> byPrincipal = new ConcurrentHashMap<>();

    private UploadAdmission(int maxConcurrent,
                            int maxConcurrentPerPrincipal,
                            Runnable onSaturated,
                            Runnable onRestored) {
        this.maxConcurrent = maxConcurrent;
        this.maxConcurrentPerPrincipal = maxConcurrentPerPrincipal;
        this.onSaturated = onSaturated;
        this.onRestored = onRestored;
    }

    static UploadAdmission uploadAdmission(UploadConfig config) {
        return uploadAdmission(config,
                               () -> {},
                               () -> {});
    }

    static UploadAdmission uploadAdmission(UploadConfig config, Runnable onSaturated, Runnable onRestored) {
        return new UploadAdmission(config.maxConcurrent(), config.maxConcurrentPerPrincipal(), onSaturated, onRestored);
    }

    /// A permit for `principal`'s upload, or the cap that refused it. The permit's `run()` returns it, once.
    Result<Runnable> tryAcquire(String principal) {
        if (!acquireNode()) {
            reportSaturation();

            return NODE_LIMIT.result();
        }

        if (!acquirePrincipal(principal)) {
            inFlight.decrementAndGet();

            return PRINCIPAL_LIMIT.result();
        }

        var released = new AtomicBoolean();

        return Result.success(() -> release(principal, released));
    }

    private void reportSaturation() {
        if (saturated.compareAndSet(false, true)) {
            onSaturated.run();
        }
    }

    private void reportRestoration() {
        if (saturated.compareAndSet(true, false)) {
            onRestored.run();
        }
    }

    int inFlight() {
        return inFlight.get();
    }

    private boolean acquireNode() {
        int current;

        do {
            current = inFlight.get();
            if (current >= maxConcurrent) {
                return false;
            }
        } while (!inFlight.compareAndSet(current, current + 1));

        return true;
    }

    private boolean acquirePrincipal(String principal) {
        var granted = new AtomicBoolean();

        byPrincipal.compute(principal, (_, count) -> grant(count, granted));

        return granted.get();
    }

    private Integer grant(Integer count, AtomicBoolean granted) {
        var current = Option.option(count).or(0);

        if (current >= maxConcurrentPerPrincipal) {
            return count;
        }

        granted.set(true);

        return current + 1;
    }

    private void release(String principal, AtomicBoolean released) {
        if (released.compareAndSet(false, true)) {
            inFlight.decrementAndGet();
            byPrincipal.computeIfPresent(principal,
                                         (_, count) -> count <= 1
                                                       ? null
                                                       : count - 1);
            reportRestoration();
        }
    }
}
