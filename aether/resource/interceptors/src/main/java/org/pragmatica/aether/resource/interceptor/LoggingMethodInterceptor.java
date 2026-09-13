// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import java.util.Map;

import org.pragmatica.aether.slice.MethodInterceptor;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;


/// One logger per injection point, named by `LogConfig.name()` — a single class-named logger
/// shared by every intercepted method gave operators nothing to tune per method (#280 R28).
public record LoggingMethodInterceptor(LogConfig config, Logger log) implements MethodInterceptor {
    public LoggingMethodInterceptor(LogConfig config) {
        this(config,
             LoggerFactory.getLogger(config.name()));
    }

    @Override
    public <R, T> Fn1<Promise<R>, T> intercept(Fn1<Promise<R>, T> method) {
        return request -> invokeWithLogging(method, request);
    }

    /// The MDC (request-id and whatever else the invocation put there) is captured at entry and
    /// re-applied around the exit line: the promise usually resolves on another thread, where the
    /// MDC is empty, so the exit line lost its `requestId` while the entry line kept it (review of
    /// #1088, S2). The exit thread's own MDC is restored afterwards.
    private <R, T> Promise<R> invokeWithLogging(Fn1<Promise<R>, T> method, T request) {
        var context = Option.option(MDC.getCopyOfContextMap());

        logEntry(request);
        var startNanos = System.nanoTime();

        return method.apply(request)
                     .onResult(result -> withContext(context,
                                                     () -> logExit(result, startNanos)));
    }

    @Contract
    private static void withContext(Option<Map<String, String>> context, Runnable logging) {
        var own = Option.option(MDC.getCopyOfContextMap());

        context.onPresent(MDC::setContextMap);
        try {
            logging.run();
        } finally {
            own.fold(() -> {
                         MDC.clear();

                         return Unit.unit();
                     },
                     map -> {
                         MDC.setContextMap(map);

                         return Unit.unit();
                     });
        }
    }

    @Contract
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

    @Contract
    private <R> void logExit(Result<R> result, long startNanos) {
        var durationMs = (System.nanoTime() - startNanos) / 1_000_000.0;
        var formattedDuration = String.format("%.2f", durationMs);

        logExitDetails(result, formattedDuration);
    }

    /// The exit line always carries the OUTCOME — `ok`, or `failed <CauseType>` — so that under the
    /// privacy-safe defaults a failure is still distinguishable from a success (review of #1088,
    /// S3). The cause's TYPE only, never its message: the message is where the personal data lives.
    @Contract
    private <R> void logExitDetails(Result<R> result, String formattedDuration) {
        var outcome = outcome(result);

        if (config.logResult() && config.logDuration()) {
            log("<- {} {} result={} ({}ms)", config.name(), outcome, summarize(result), formattedDuration);
        } else if (config.logDuration()) {
            log("<- {} {} ({}ms)", config.name(), outcome, formattedDuration);
        } else if (config.logResult()) {
            log("<- {} {} result={}", config.name(), outcome, summarize(result));
        } else {
            log("<- {} {}", config.name(), outcome);
        }
    }

    private static <R> String outcome(Result<R> result) {
        return result.fold(cause -> "failed " + cause.getClass()
                                                     .getSimpleName(),
                           _ -> "ok");
    }

    @Contract
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
