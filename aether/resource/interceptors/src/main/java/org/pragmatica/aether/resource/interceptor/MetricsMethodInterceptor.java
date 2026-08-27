// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.aether.slice.MethodInterceptor;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;


// #278: registry is resolved by MetricsInterceptorFactory from ProvisioningContext, not carried on
// MetricsConfig — see MetricsConfig's header comment. `tags` is parsed once by
// MetricsInterceptorFactory#parseTags at provisioning time — see #tagOf for why a malformed tag
// fails provisioning rather than every request.
public record MetricsMethodInterceptor(MetricsConfig config, MeterRegistry registry, Tags tags) implements MethodInterceptor {
    @Override
    public <R, T> Fn1<Promise<R>, T> intercept(Fn1<Promise<R>, T> method) {
        return request -> invokeWithMetrics(method, request);
    }

    private <R, T> Promise<R> invokeWithMetrics(Fn1<Promise<R>, T> method, T request) {
        var sample = Timer.start(registry);

        return method.apply(request)
                     .onResult(result -> recordMetrics(sample, result));
    }

    @Contract
    private <R> void recordMetrics(Timer.Sample sample, Result<R> result) {
        var suffix = result.isSuccess()
                     ? ".success"
                     : ".failure";

        sample.stop(registry.timer(config.name() + suffix, tags));
    }

    // MetricsConfig#tags() is a flat List<String> of "key=value" tokens (the comma-joined-scalar
    // TOML binding — see MetricsConfig's header comment). Micrometer's MeterRegistry#timer has no
    // overload accepting that shape directly: the varargs form wants an already-flattened
    // alternating key/value String... array, so a bare tag name (or an odd-length flattening)
    // throws Micrometer's generic "size must be even" IllegalArgumentException with no indication
    // of which tag caused it. A malformed entry is a config-authoring mistake, not a per-request
    // condition, so this is total (Result, not throw) and consumed by
    // MetricsInterceptorFactory#parseTags at provisioning time — failing the whole interceptor's
    // provisioning once, with the offending value named, instead of every call after the first.
    static Result<Tag> tagOf(String rawTag) {
        var separator = rawTag.indexOf('=');

        return separator < 0
               ? Result.failure(malformedTag(rawTag))
               : Result.success(Tag.of(rawTag.substring(0, separator), rawTag.substring(separator + 1)));
    }

    private static Cause malformedTag(String rawTag) {
        return Causes.cause("Metrics tag \"" + rawTag + "\" is not in \"key=value\" form");
    }
}
