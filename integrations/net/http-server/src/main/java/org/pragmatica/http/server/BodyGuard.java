/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.pragmatica.http.server;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ScheduledFuture;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;


/// The bounds on one request body, shared by the HTTP/1.1 and HTTP/3 pipelines so both apply the same semantics: the body
/// size limit, the idle bound, the minimum rate and the whole-body duration. Confined to the channel's event loop, like
/// [BodyRateGuard], so timers run on the executor's clock.
///
/// The first bound a body breaks is reported once, through `onRefuse`, and the guard then stops. [#stop] ends the guard
/// and releases the permit; it is idempotent.
final class BodyGuard {
    /// Which bound a body broke.
    enum Refusal {
        TOO_LARGE,
        TOO_SLOW,
        IDLE,
        DURATION
    }

    private final EventExecutor executor;
    private final long maxBytes;
    private final TimeSpan idle;
    private final Runnable release;
    private final Consumer<Refusal> onRefuse;
    private final BodyRateGuard rateGuard;
    private long received;
    private boolean stopped;
    private Option<ScheduledFuture<?>> idleTimer = none();
    private Option<ScheduledFuture<?>> durationTimer = none();

    private BodyGuard(EventExecutor executor,
                      long maxBytes,
                      TimeSpan idle,
                      BodyRateLimit rate,
                      Runnable release,
                      Consumer<Refusal> onRefuse) {
        this.executor = executor;
        this.maxBytes = maxBytes;
        this.idle = idle;
        this.release = release;
        this.onRefuse = onRefuse;
        this.rateGuard = BodyRateGuard.start(executor, rate, () -> refuse(Refusal.TOO_SLOW));
    }

    /// Starts guarding a body now. `idle` and `maxDuration` of zero or less mean no such bound.
    static BodyGuard start(EventExecutor executor,
                           long maxBytes,
                           TimeSpan idle,
                           BodyRateLimit rate,
                           TimeSpan maxDuration,
                           Runnable release,
                           Consumer<Refusal> onRefuse) {
        var guard = new BodyGuard(executor, maxBytes, idle, rate, release, onRefuse);

        guard.armIdle();
        guard.durationTimer = maxDuration.nanos() > 0
                              ? some(executor.schedule(() -> guard.refuse(Refusal.DURATION),
                                                       maxDuration.nanos(),
                                                       TimeUnit.NANOSECONDS))
                              : none();

        return guard;
    }

    /// Whether a declared `Content-Length` already exceeds the limit; refuses the request when it does.
    boolean refuseIfDeclaredTooLarge(long contentLength) {
        if (contentLength > maxBytes) {
            refuse(Refusal.TOO_LARGE);

            return true;
        }

        return false;
    }

    /// Counts `count` body bytes; returns false when they take the body past the limit (reported as [Refusal#TOO_LARGE]).
    boolean onBytes(int count) {
        if (stopped) {
            return false;
        }

        received += count;
        rateGuard.onBytes(count);
        armIdle();
        if (received > maxBytes) {
            refuse(Refusal.TOO_LARGE);

            return false;
        }

        return true;
    }

    /// The body is complete or the request is over: no further check, and the permit is returned.
    Unit stop() {
        if (!stopped) {
            stopped = true;
            idleTimer.onPresent(timer -> timer.cancel(false));
            durationTimer.onPresent(timer -> timer.cancel(false));
            idleTimer = none();
            durationTimer = none();
            rateGuard.stop();
            release.run();
        }

        return unit();
    }

    private void armIdle() {
        idleTimer.onPresent(timer -> timer.cancel(false));
        idleTimer = !stopped && idle.nanos() > 0
                    ? some(executor.schedule(() -> refuse(Refusal.IDLE), idle.nanos(), TimeUnit.NANOSECONDS))
                    : none();
    }

    private void refuse(Refusal reason) {
        if (!stopped) {
            stop();
            onRefuse.accept(reason);
        }
    }
}
