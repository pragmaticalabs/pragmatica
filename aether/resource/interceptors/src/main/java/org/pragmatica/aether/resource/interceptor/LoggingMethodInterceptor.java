package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.aether.slice.MethodInterceptor;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Method interceptor that logs entry, exit, and duration of method invocations.
public record LoggingMethodInterceptor(LogConfig config) implements MethodInterceptor {
    private static final Logger log = LoggerFactory.getLogger(LoggingMethodInterceptor.class);

    @Override
    public <R, T> Fn1<Promise<R>, T> intercept(Fn1<Promise<R>, T> method) {
        return request -> invokeWithLogging(method, request);
    }

    private <R, T> Promise<R> invokeWithLogging(Fn1<Promise<R>, T> method, T request) {
        logEntry(request);
        var startNanos = System.nanoTime();
        return method.apply(request)
                     .onResult(result -> logExit(result, startNanos));
    }

    private <T> void logEntry(T request) {
        if (config.logArgs()) {
            log("-> {} args={}",
                config.name(),
                request);
        } else {
            log("-> {}",
                config.name());
        }
    }

    private <R> void logExit(Result<R> result, long startNanos) {
        var durationMs = (System.nanoTime() - startNanos) / 1_000_000.0;
        var formattedDuration = String.format("%.2f", durationMs);
        logExitDetails(result, formattedDuration);
    }

    private <R> void logExitDetails(Result<R> result, String formattedDuration) {
        if (config.logResult() && config.logDuration()) {
            log("<- {} result={} ({}ms)", config.name(), summarize(result), formattedDuration);
        } else if (config.logDuration()) {
            log("<- {} ({}ms)", config.name(), formattedDuration);
        } else if (config.logResult()) {
            log("<- {} result={}", config.name(), summarize(result));
        } else {
            log("<- {}", config.name());
        }
    }

    @SuppressWarnings("JBCT-SEQ-01")
    private void log(String format, Object... args) {
        switch (config.level()) {
            case TRACE -> log.trace(format, args);
            case DEBUG -> log.debug(format, args);
            case INFO -> log.info(format, args);
            case WARN -> log.warn(format, args);
            case ERROR -> log.error(format, args);
        }
    }

    private <R> String summarize(Result<R> result) {
        var str = result.toString();
        return str.length() > 100
               ? str.substring(0, 100) + "..."
               : str;
    }
}
