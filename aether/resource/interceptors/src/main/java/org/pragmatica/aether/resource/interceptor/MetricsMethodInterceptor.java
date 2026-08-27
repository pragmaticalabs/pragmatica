// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.aether.slice.MethodInterceptor;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;


// #278: registry is resolved by MetricsInterceptorFactory from ProvisioningContext, not carried on
// MetricsConfig — see MetricsConfig's header comment.
public record MetricsMethodInterceptor(MetricsConfig config, MeterRegistry registry) implements MethodInterceptor {
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
        var tagsArray = config.tags().toArray(new String[0]);

        sample.stop(registry.timer(config.name() + suffix, tagsArray));
    }
}
