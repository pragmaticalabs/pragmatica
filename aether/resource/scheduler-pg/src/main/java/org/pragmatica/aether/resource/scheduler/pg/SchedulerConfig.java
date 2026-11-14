// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.scheduler.pg;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.Verify.Is;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.vo.NonBlankString;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Settings of one [PgScheduler] instance.
///
/// @param instanceId    written to `claimed_by` for diagnostics only; no decision reads it
/// @param pollInterval  delay between the end of one poll and the start of the next
/// @param leaseDuration how long a claim is held, measured on the DATABASE clock; must exceed the longest handler run,
///                      or the job can be re-claimed and fired concurrently (the token fences the late writes)
/// @param retryDelay    a failed firing is due again this long after the failure (database clock)
/// @param closeGrace    how long [PgScheduler#close()] waits for in-flight firings before releasing their claims
/// @param batchSize     at most this many due jobs are claimed per poll
public record SchedulerConfig(NonBlankString instanceId,
                              TimeSpan pollInterval,
                              TimeSpan leaseDuration,
                              TimeSpan retryDelay,
                              TimeSpan closeGrace,
                              int batchSize) {
    /// Defaults (marked guess, no load data behind them): poll 1 s, lease 30 s, retry 5 s, close grace 5 s, batch 32.
    public static Result<SchedulerConfig> schedulerConfig(String instanceId) {
        return schedulerConfig(instanceId,
                               timeSpan(1).seconds(),
                               timeSpan(30).seconds(),
                               timeSpan(5).seconds(),
                               timeSpan(5).seconds(),
                               32);
    }

    public static Result<SchedulerConfig> schedulerConfig(String instanceId,
                                                          TimeSpan pollInterval,
                                                          TimeSpan leaseDuration,
                                                          TimeSpan retryDelay,
                                                          TimeSpan closeGrace,
                                                          int batchSize) {
        return Result.all(NonBlankString.nonBlankString(instanceId),
                          positiveMillis(pollInterval, "pollInterval"),
                          positiveMillis(leaseDuration, "leaseDuration"),
                          Result.success(retryDelay),
                          Result.success(closeGrace),
                          Verify.ensure(batchSize, Is::positive, Causes.cause("batchSize must be positive")))
                     .map(SchedulerConfig::new);
    }

    private static Result<TimeSpan> positiveMillis(TimeSpan span, String name) {
        return Verify.ensure(span.millis(), Is::positive, Causes.cause(name + " must be at least 1 ms"))
                     .map(_ -> span);
    }
}
