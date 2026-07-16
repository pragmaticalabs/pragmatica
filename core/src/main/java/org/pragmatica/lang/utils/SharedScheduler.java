/*
 *  Copyright (c) 2025 Sergiy Yevtushenko.
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
 *
 */
package org.pragmatica.lang.utils;

import java.util.concurrent.ScheduledFuture;

import org.pragmatica.lang.io.TimeSpan;


/// Common scheduler for periodic and one-shot work across the runtime.
///
/// Backed by a single process-wide [VirtualThreadScheduler]: one platform timer thread owns a
/// deadline-ordered queue and dispatches every task body to a virtual-thread-per-task executor.
/// This decouples timekeeping from execution, so a slow or blocking task body can never starve
/// the timing of other scheduled work — the failure mode of the previous fixed-size
/// `ScheduledThreadPoolExecutor`, where a handful of blocked tasks delayed every periodic tick.
public final class SharedScheduler {
    private SharedScheduler() {}

    private static final VirtualThreadScheduler SCHEDULER = new VirtualThreadScheduler();

    /// Schedule one-time invocation
    ///
    /// @return instance of [ScheduledFuture] that can be used to cancel scheduled task
    public static ScheduledFuture<?> schedule(Runnable runnable, TimeSpan interval) {
        return SCHEDULER.schedule(runnable, interval);
    }

    /// Schedule periodic invocation
    ///
    /// @return instance of [ScheduledFuture] that can be used to cancel scheduled task
    public static ScheduledFuture<?> scheduleAtFixedRate(Runnable runnable, TimeSpan interval) {
        return SCHEDULER.scheduleAtFixedRate(runnable, interval);
    }

    /// Schedule periodic invocation with custom initial delay
    ///
    /// @return instance of [ScheduledFuture] that can be used to cancel scheduled task
    public static ScheduledFuture<?> scheduleAtFixedRate(Runnable runnable, TimeSpan initialDelay, TimeSpan interval) {
        return SCHEDULER.scheduleAtFixedRate(runnable, initialDelay, interval);
    }
}
