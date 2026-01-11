package org.pragmatica.aether.infra.aspect;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.lang.Verify.ensure;

/**
 * Configuration for logging aspect.
 */
public record LogConfig(String name,
                        LogLevel level,
                        boolean logArgs,
                        boolean logResult,
                        boolean logDuration) {
    public static Result<LogConfig> logConfig(String name) {
        return ensure(name, Verify.Is::notBlank)
                     .map(n -> new LogConfig(n, LogLevel.INFO, true, true, true));
    }

    public static Result<LogConfig> logConfig(String name, LogLevel level) {
        return ensure(name, Verify.Is::notBlank)
                     .map(n -> new LogConfig(n, level, true, true, true));
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
