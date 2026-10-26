// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.pragmatica.aether.config.UploadConfig;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;


/// The caps on uploads in flight: how many (`[app-http.upload] max_concurrent` and `max_concurrent_per_principal`) and how
/// many declared bytes (`max_in_flight_size`).
///
/// A permit is taken when an upload request is classified, on its HEADERS, and returned when its body has been received
/// or the request ends without it, so the caps bound the bodies being buffered at once. Classification and release run on
/// different event loops, and one lock covers the counts, the reservation and the episode flags together: a cap check and
/// the flag transition it causes are one step, so a release can never fall between a refusal's check and its report (which
/// would leave a flag set while the node is below its cap and suppress the next episode's report).
///
/// Each cap reports its own state, once per episode: `onSaturated` runs on the first node-cap refusal after the node was
/// below its cap, `onRestored` on the first release after that (a release always leaves the node below its cap, because
/// the episode began with it at the cap). The byte budget does the same through `onBudgetExhausted` and
/// `onBudgetRestored`; its recovery needs the reservation back at or below half of the budget, so a node hovering at the
/// edge does not report an episode per refused request. A refusal for one principal's cap reports nothing. The reports
/// run under the lock, so they reach their sink in the order the transitions happened.
final class UploadAdmission {
    static final Cause NODE_LIMIT = Causes.cause("Too many concurrent uploads on this node");
    static final Cause PRINCIPAL_LIMIT = Causes.cause("Too many concurrent uploads for this principal");
    static final Cause BYTE_BUDGET = Causes.cause("Too many bytes of uploads in flight on this node");

    private final int maxConcurrent;
    private final int maxConcurrentPerPrincipal;
    private final long maxInFlightBytes;
    private final Runnable onSaturated;
    private final Runnable onRestored;
    private final Runnable onBudgetExhausted;
    private final Runnable onBudgetRestored;
    private final Map<String, Integer> byPrincipal = new HashMap<>();
    private boolean saturated;
    private boolean budgetExhausted;
    private int inFlight;
    private long reservedBytes;

    private UploadAdmission(UploadConfig config,
                            Runnable onSaturated,
                            Runnable onRestored,
                            Runnable onBudgetExhausted,
                            Runnable onBudgetRestored) {
        this.maxConcurrent = config.maxConcurrent();
        this.maxConcurrentPerPrincipal = config.maxConcurrentPerPrincipal();
        this.maxInFlightBytes = config.maxInFlightBytes();
        this.onSaturated = onSaturated;
        this.onRestored = onRestored;
        this.onBudgetExhausted = onBudgetExhausted;
        this.onBudgetRestored = onBudgetRestored;
    }

    static UploadAdmission uploadAdmission(UploadConfig config) {
        return uploadAdmission(config,
                               () -> {},
                               () -> {});
    }

    static UploadAdmission uploadAdmission(UploadConfig config, Runnable onSaturated, Runnable onRestored) {
        return uploadAdmission(config,
                               onSaturated,
                               onRestored,
                               () -> {},
                               () -> {});
    }

    static UploadAdmission uploadAdmission(UploadConfig config,
                                           Runnable onSaturated,
                                           Runnable onRestored,
                                           Runnable onBudgetExhausted,
                                           Runnable onBudgetRestored) {
        return new UploadAdmission(config, onSaturated, onRestored, onBudgetExhausted, onBudgetRestored);
    }

    /// A permit for `principal`'s upload that reserves no bytes, or the cap that refused it.
    Result<Runnable> tryAcquire(String principal) {
        return tryAcquire(principal, 0);
    }

    /// A permit for `principal`'s upload of `declaredBytes`, or the cap that refused it, checked in this order: the node's
    /// count, the principal's count, the byte budget. The permit's `run()` returns the count and the bytes, once. A
    /// declaration larger than the whole budget reserves the whole budget, so such an upload runs alone rather than never.
    synchronized Result<Runnable> tryAcquire(String principal, long declaredBytes) {
        var reserved = Math.min(Math.max(0, declaredBytes), maxInFlightBytes);

        if (inFlight >= maxConcurrent) {
            reportSaturation();

            return NODE_LIMIT.result();
        }

        if (principalCount(principal) >= maxConcurrentPerPrincipal) {
            return PRINCIPAL_LIMIT.result();
        }

        if (reserved > maxInFlightBytes - reservedBytes) {
            reportExhaustion();

            return BYTE_BUDGET.result();
        }

        inFlight++;
        reservedBytes += reserved;
        byPrincipal.merge(principal, 1, Integer::sum);
        var released = new AtomicBoolean();

        return Result.success(() -> release(principal, reserved, released));
    }

    private void reportSaturation() {
        if (!saturated) {
            saturated = true;
            onSaturated.run();
        }
    }

    private void reportExhaustion() {
        if (!budgetExhausted) {
            budgetExhausted = true;
            onBudgetExhausted.run();
        }
    }

    private void reportRestoration() {
        if (saturated) {
            saturated = false;
            onRestored.run();
        }

        if (budgetExhausted && reservedBytes <= maxInFlightBytes / 2) {
            budgetExhausted = false;
            onBudgetRestored.run();
        }
    }

    synchronized int inFlight() {
        return inFlight;
    }

    synchronized long reservedBytes() {
        return reservedBytes;
    }

    /// How many principals have an upload in flight: a principal with none has no entry, so this stays at zero once every
    /// permit is back, however many distinct principals came through.
    synchronized int principalEntries() {
        return byPrincipal.size();
    }

    /// Test seam: whether the node cap is in a reported episode.
    synchronized boolean saturated() {
        return saturated;
    }

    /// Test seam: whether the byte budget is in a reported episode.
    synchronized boolean budgetExhausted() {
        return budgetExhausted;
    }

    private int principalCount(String principal) {
        return Option.option(byPrincipal.get(principal)).or(0);
    }

    private synchronized void release(String principal, long reserved, AtomicBoolean released) {
        if (released.compareAndSet(false, true)) {
            inFlight--;
            reservedBytes -= reserved;
            byPrincipal.computeIfPresent(principal,
                                         (_, count) -> count <= 1
                                                       ? null
                                                       : count - 1);
            reportRestoration();
        }
    }
}
