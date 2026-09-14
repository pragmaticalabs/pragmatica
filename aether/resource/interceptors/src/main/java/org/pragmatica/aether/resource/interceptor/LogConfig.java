// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.lang.Verify.ensure;


/// `name` is also the LOGGER name each injection point logs through, so one method's level can be
/// tuned in the logging config without touching the others. Content logging (`logArgs`,
/// `logResult`) is opt-in: a request or a result is personal data until proven otherwise, and the
/// programmatic defaults used to switch both on (#280 R28). The TOML binder treats every field as
/// mandatory regardless, so these defaults reach only callers of the factories below.
public record LogConfig(String name, LogLevel level, boolean logArgs, boolean logResult, boolean logDuration) {
    public static Result<LogConfig> logConfig(String name) {
        return ensure(name, Verify.Is::notBlank).map(n -> new LogConfig(n, LogLevel.INFO, false, false, true));
    }

    public static Result<LogConfig> logConfig(String name, LogLevel level) {
        return ensure(name, Verify.Is::notBlank).map(n -> new LogConfig(n, level, false, false, true));
    }

    public LogConfig withLevel(LogLevel level) {
        return new LogConfig(name, level, logArgs, logResult, logDuration);
    }

    public LogConfig withLogArgs(boolean logArgs) {
        return new LogConfig(name, level, logArgs, logResult, logDuration);
    }

    public LogConfig withLogResult(boolean logResult) {
        return new LogConfig(name, level, logArgs, logResult, logDuration);
    }

    public LogConfig withLogDuration(boolean logDuration) {
        return new LogConfig(name, level, logArgs, logResult, logDuration);
    }
}
