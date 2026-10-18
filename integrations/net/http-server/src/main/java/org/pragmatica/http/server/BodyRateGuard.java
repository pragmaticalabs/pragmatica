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

import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ScheduledFuture;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// One request body's [BodyRateLimit] check, shared by the HTTP/1.1 and HTTP/3 pipelines so both apply the same
/// semantics. Confined to the channel's event loop: bytes are counted there and the checks are scheduled there, so
/// the check sees the executor's clock (which a test can advance) rather than wall time.
///
/// The first check runs `grace` after [#start] and averages over all of it; each later check averages over the
/// `window` since the previous one. Below the minimum, `onTooSlow` runs once and the guard stops.
final class BodyRateGuard {
    private final EventExecutor executor;
    private final BodyRateLimit limit;
    private final Runnable onTooSlow;
    private long received;
    private long receivedAtLastCheck;
    private Option<ScheduledFuture<?>> nextCheck = none();

    private BodyRateGuard(EventExecutor executor, BodyRateLimit limit, Runnable onTooSlow) {
        this.executor = executor;
        this.limit = limit;
        this.onTooSlow = onTooSlow;
    }

    /// Starts checking a body now. A disabled limit yields a guard that only counts.
    static BodyRateGuard start(EventExecutor executor, BodyRateLimit limit, Runnable onTooSlow) {
        var guard = new BodyRateGuard(executor, limit, onTooSlow);

        if (limit.enabled()) {
            guard.scheduleCheck(limit.grace());
        }

        return guard;
    }

    void onBytes(int count) {
        received += count;
    }

    /// The body is complete (or the request is over): no further check.
    void stop() {
        nextCheck.onPresent(check -> check.cancel(false));
        nextCheck = none();
    }

    private void scheduleCheck(TimeSpan after) {
        nextCheck = some(executor.schedule(() -> check(after), after.nanos(), TimeUnit.NANOSECONDS));
    }

    private void check(TimeSpan elapsed) {
        var delta = received - receivedAtLastCheck;

        receivedAtLastCheck = received;
        if (belowMinimum(delta, elapsed)) {
            nextCheck = none();
            onTooSlow.run();
        } else {
            scheduleCheck(limit.window());
        }
    }

    /// `delta / elapsed < bytesPerSecond`, compared as `delta * 1e9 < bytesPerSecond * elapsedNanos` in doubles, which
    /// cannot overflow and are exact well past any body size this server accepts.
    private boolean belowMinimum(long delta, TimeSpan elapsed) {
        return (double) delta * 1e9 < (double) limit.bytesPerSecond() * elapsed.nanos();
    }
}
