package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.lang.Verify.ensure;


/// Configuration for logging interceptor.
///
/// @param name        Logger name prefix
/// @param level       Log level to use
/// @param logArgs     Whether to log method arguments
/// @param logResult   Whether to log method results
/// @param logDuration Whether to log method execution duration
public record LogConfig(String name, LogLevel level, boolean logArgs, boolean logResult, boolean logDuration) {
    public static Result<LogConfig> logConfig(String name) {
        return ensure(name, Verify.Is::notBlank).map(n -> new LogConfig(n, LogLevel.INFO, true, true, true));
    }

    public static Result<LogConfig> logConfig(String name, LogLevel level) {
        return ensure(name, Verify.Is::notBlank).map(n -> new LogConfig(n, level, true, true, true));
    }

    @SuppressWarnings("JBCT-VO-02") public LogConfig withLevel(LogLevel level) {
        return new LogConfig(name, level, logArgs, logResult, logDuration);
    }

    @SuppressWarnings("JBCT-VO-02") public LogConfig withLogArgs(boolean logArgs) {
        return new LogConfig(name, level, logArgs, logResult, logDuration);
    }

    @SuppressWarnings("JBCT-VO-02") public LogConfig withLogResult(boolean logResult) {
        return new LogConfig(name, level, logArgs, logResult, logDuration);
    }

    @SuppressWarnings("JBCT-VO-02") public LogConfig withLogDuration(boolean logDuration) {
        return new LogConfig(name, level, logArgs, logResult, logDuration);
    }
}
