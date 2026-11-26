// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.pragmatica.aether.resource.ResourceSection;
import org.pragmatica.aether.slice.MethodInterceptor;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.RateLimiter;
import org.pragmatica.lang.utils.RateLimiter.RateLimiterError.LimitExceeded;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;


/// A rate limiter around one section's calls, with optional meters (#280 R27).
///
/// `meters` is present only when provisioning found a `MeterRegistry`, the slice id and the [ResourceSection] in its
/// context; otherwise the limiter runs unmetered, exactly as before. The limiter instance is per `(slice, section)` on
/// one node, so each node reports its own bucket.
public record RateLimitMethodInterceptor(RateLimiter limiter, Option<Meters> meters) implements MethodInterceptor {
    public static RateLimitMethodInterceptor rateLimitMethodInterceptor(RateLimiter limiter) {
        return new RateLimitMethodInterceptor(limiter, Option.none());
    }

    public static RateLimitMethodInterceptor rateLimitMethodInterceptor(RateLimiter limiter,
                                                                        ProvisioningContext context) {
        return new RateLimitMethodInterceptor(limiter, Meters.bind(context));
    }

    @Override
    public <R, T> Fn1<Promise<R>, T> intercept(Fn1<Promise<R>, T> method) {
        return request -> meters.fold(() -> limiter.execute(() -> method.apply(request)),
                                      bound -> metered(bound, method, request));
    }

    /// Removes this limiter's meters from the registry; the factory calls it when the provider unloads the resource.
    public Unit release() {
        return meters.map(Meters::release)
                     .or(Unit.unit());
    }

    private <R, T> Promise<R> metered(Meters bound, Fn1<Promise<R>, T> method, T request) {
        var executed = new AtomicBoolean();

        return limiter.execute(() -> method.apply(bound.admitting(executed, request)))
                      .withResult(result -> bound.settle(executed.get(),
                                                         result));
    }

    /// The limiter's meters: calls admitted (the method ran) and calls rejected (the limiter refused them).
    record Meters(MeterRegistry registry, List<Meter> owned, Counter admitted, Counter rejected) {
        private static final String PREFIX = "aether.interceptor.rate_limit.";

        static Option<Meters> bind(ProvisioningContext context) {
            return Result.all(context.extension(MeterRegistry.class),
                              context.extension(String.class),
                              context.extension(ResourceSection.class))
                         .map((registry, slice, section) -> register(registry,
                                                                     Tags.of("slice",
                                                                             slice,
                                                                             "section",
                                                                             section.name())))
                         .option();
        }

        private static Meters register(MeterRegistry registry, Tags tags) {
            var admitted = counter(registry, PREFIX + "admitted", tags);
            var rejected = counter(registry, PREFIX + "rejected", tags);

            return new Meters(registry, List.of(admitted, rejected), admitted, rejected);
        }

        private static Counter counter(MeterRegistry registry, String name, Tags tags) {
            return MeterClaims.claim(registry, name, tags, () -> registry.counter(name, tags));
        }

        /// Counts and marks the call as admitted; evaluated only inside the limiter's supplier.
        <T> T admitting(AtomicBoolean executed, T request) {
            executed.set(true);
            admitted.increment();

            return request;
        }

        /// A rejection is the limiter's own refusal of a call whose method never ran: the same cause coming back from
        /// inside the method (a downstream limiter) is not this limiter's rejection.
        @Contract
        void settle(boolean executed, Result<?> result) {
            if (!executed && result.fold(cause -> cause instanceof LimitExceeded, _ -> false)) {
                rejected.increment();
            }
        }

        Unit release() {
            return MeterClaims.release(registry, owned);
        }
    }
}
