// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.Test;

import org.slf4j.Logger;
import org.slf4j.MDC;
import org.slf4j.helpers.MessageFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #280 (review of #1088, S2/S3): the exit line lost the request-id the invocation put in the MDC
/// whenever the promise resolved on another thread — the normal case for a resource-backed method
/// — and under the privacy-safe defaults it said nothing about the outcome. Both are pinned with a
/// recording logger injected through the `(LogConfig, Logger)` constructor: each recorded line is
/// paired with the MDC `requestId` the logging thread saw.
class LoggingInterceptorExitLineTest {
    private static final TimeSpan TIMEOUT = TimeSpan.timeSpan(5).seconds();

    private record Line(String message, String requestId) {}

    private record Declined(String message) implements Cause {}

    @Test
    void exitLine_resolvedOnAnotherThread_carriesTheEntryRequestId() {
        var lines = new CopyOnWriteArrayList<Line>();
        var interceptor = new LoggingMethodInterceptor(config(), recordingLogger(lines));
        Fn1<Promise<String>, String> elsewhere = _ -> resolvedOnAnotherThread("ok");

        MDC.put("requestId", "rid-42");

        try {
            interceptor.intercept(elsewhere).apply("x").await(TIMEOUT).onFailure(cause -> fail(cause.message()));
        } finally {
            MDC.clear();
        }

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).requestId()).as("entry line").isEqualTo("rid-42");
        assertThat(lines.get(1).requestId()).as("exit line, logged on the resolving thread").isEqualTo("rid-42");
    }

    @Test
    void exitLine_underPrivacyDefaults_namesTheOutcome_withoutTheMessage() {
        var lines = new CopyOnWriteArrayList<Line>();
        var interceptor = new LoggingMethodInterceptor(config(), recordingLogger(lines));
        Fn1<Promise<String>, String> failing = _ -> new Declined("card 4111 1111 1111 1111 declined").<String> promise();

        interceptor.intercept(failing).apply("x").await(TIMEOUT).onSuccess(_ -> fail("must fail"));

        var exit = lines.get(1).message();

        assertThat(exit).startsWith("<- payment.flow failed Declined");
        assertThat(exit).as("the cause TYPE, never its message — that is where the personal data lives").doesNotContain("4111");
    }

    @Test
    void exitLine_underPrivacyDefaults_saysOk_onSuccess() {
        var lines = new CopyOnWriteArrayList<Line>();
        var interceptor = new LoggingMethodInterceptor(config(), recordingLogger(lines));

        interceptor.intercept((String _) -> Promise.success("secret")).apply("x").await(TIMEOUT);

        assertThat(lines.get(1).message()).startsWith("<- payment.flow ok").doesNotContain("secret");
    }

    private static LogConfig config() {
        return LogConfig.logConfig("payment.flow").fold(cause -> fail(cause.message()), c -> c);
    }

    /// Resolved on another thread AFTER the interceptor has registered its exit callback — the
    /// callback therefore runs on that thread, whose MDC is empty. (Resolving before returning would
    /// run the callback on the caller's thread and pin nothing.)
    @SuppressWarnings("JBCT-EX-01")
    private static Promise<String> resolvedOnAnotherThread(String value) {
        var promise = Promise.<String> promise();

        Thread.ofPlatform().daemon().start(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }

            promise.succeed(value);
        });

        return promise;
    }

    /// Records the formatted message and the MDC as seen by the thread that logged it. A JDK proxy
    /// over `org.slf4j.Logger`: every level method records, every `is*Enabled` answers true.
    private static Logger recordingLogger(List<Line> lines) {
        return (Logger) Proxy.newProxyInstance(Logger.class.getClassLoader(),
                                               new Class<?>[]{Logger.class},
                                               (_, method, args) -> record(lines, method, args));
    }

    private static Object record(List<Line> lines, Method method, Object[] args) {
        var name = method.getName();

        if (name.startsWith("is") && name.endsWith("Enabled")) {
            return true;
        }

        if (LEVELS.contains(name) && args != null && args[0] instanceof String format) {
            var rest = Arrays.copyOfRange(args, 1, args.length);
            var formatted = rest.length == 1 && rest[0] instanceof Object[] array
                            ? MessageFormatter.arrayFormat(format, array).getMessage()
                            : MessageFormatter.arrayFormat(format, rest).getMessage();

            lines.add(new Line(formatted, MDC.get("requestId")));
        }

        return null;
    }

    private static final Set<String> LEVELS = Set.of("trace", "debug", "info", "warn", "error");
}
