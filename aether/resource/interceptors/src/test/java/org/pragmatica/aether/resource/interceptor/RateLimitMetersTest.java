// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.resource.ResourceSection;
import org.pragmatica.aether.resource.SpiResourceProvider;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.RateLimiter.RateLimiterError.LimitExceeded;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #280 R27: the rate limiter's admitted and rejected calls are metered, per slice and section, and the meters follow
/// the provisioning: absent without a registry, one set per live instance across a redeploy.
class RateLimitMetersTest {
    private static final String ADMITTED = "aether.interceptor.rate_limit.admitted";
    private static final String REJECTED = "aether.interceptor.rate_limit.rejected";
    private static final String SLICE = "org.example:orders:1.0.0";
    private static final String SECTION_A = "rate-limit.api";
    private static final String SECTION_B = "rate-limit.batch";

    private static final Fn1<Promise<String>, String> SUCCEEDING = request -> Promise.success("ok-" + request);

    /// Two calls per hour and no burst: the first two calls are admitted, the third is refused.
    private static RateLimitConfig config() {
        return RateLimitConfig.rateLimitConfig(2, timeSpan(1).hours(), 0).unwrap();
    }

    private static ProvisioningContext context(MeterRegistry registry, String section) {
        return ProvisioningContext.provisioningContext()
                                  .withExtension(MeterRegistry.class, registry)
                                  .withExtension(String.class, SLICE)
                                  .withExtension(ResourceSection.class, ResourceSection.resourceSection(section));
    }

    private static RateLimitMethodInterceptor provision(ProvisioningContext context) {
        return new RateLimitInterceptorFactory().provision(config(), context).await().unwrap();
    }

    private static Result<String> call(RateLimitMethodInterceptor interceptor, Fn1<Promise<String>, String> method) {
        return interceptor.intercept(method).apply("x").await();
    }

    private static double count(MeterRegistry registry, String name, String section) {
        var counter = registry.find(name).tags("slice", SLICE, "section", section).counter();

        return counter == null ? -1 : counter.count();
    }

    @Test
    void provisioned_registersBothCounters_taggedBySliceAndSection_atZero() {
        var registry = new SimpleMeterRegistry();

        provision(context(registry, SECTION_A));

        assertThat(count(registry, ADMITTED, SECTION_A)).isEqualTo(0.0);
        assertThat(count(registry, REJECTED, SECTION_A)).isEqualTo(0.0);
    }

    @Test
    void calls_areCountedAsAdmitted_untilTheBucketIsEmpty_thenAsRejected() {
        var registry = new SimpleMeterRegistry();
        var interceptor = provision(context(registry, SECTION_A));
        var runs = new AtomicInteger();

        call(interceptor, request -> countedRun(runs, request));
        call(interceptor, request -> countedRun(runs, request));
        var refused = call(interceptor, request -> countedRun(runs, request));

        assertThat(refused.isFailure()).isTrue();
        assertThat(runs.get()).as("the refused call never ran").isEqualTo(2);
        assertThat(count(registry, ADMITTED, SECTION_A)).isEqualTo(2.0);
        assertThat(count(registry, REJECTED, SECTION_A)).isEqualTo(1.0);
    }

    @Test
    void limitExceededFromInsideTheMethod_isNotThisLimitersRejection() {
        var registry = new SimpleMeterRegistry();
        var interceptor = provision(context(registry, SECTION_A));
        Fn1<Promise<String>, String> downstreamLimited = _ -> new LimitExceeded(timeSpan(1).seconds()).promise();

        call(interceptor, downstreamLimited);

        assertThat(count(registry, ADMITTED, SECTION_A)).isEqualTo(1.0);
        assertThat(count(registry, REJECTED, SECTION_A)).isEqualTo(0.0);
    }

    @Test
    void missingRegistry_orSliceOrSection_provisionsWithoutMeters() {
        var registry = new SimpleMeterRegistry();
        var section = ResourceSection.resourceSection(SECTION_A);
        var withoutRegistry = ProvisioningContext.provisioningContext()
                                                 .withExtension(String.class, SLICE)
                                                 .withExtension(ResourceSection.class, section);
        var withoutSlice = ProvisioningContext.provisioningContext()
                                              .withExtension(MeterRegistry.class, registry)
                                              .withExtension(ResourceSection.class, section);
        var withoutSection = ProvisioningContext.provisioningContext()
                                                .withExtension(MeterRegistry.class, registry)
                                                .withExtension(String.class, SLICE);

        for (var context : List.of(withoutRegistry, withoutSlice, withoutSection)) {
            var interceptor = provision(context);

            assertThat(call(interceptor, SUCCEEDING).isSuccess()).isTrue();
        }

        var plain = new RateLimitInterceptorFactory().provision(config()).await().unwrap();

        assertThat(call(plain, SUCCEEDING).isSuccess()).isTrue();
        assertThat(registry.getMeters()).as("no extension, no meters").isEmpty();
    }

    @Test
    void twoSectionsOfOneSlice_haveSeparateMeters() {
        var registry = new SimpleMeterRegistry();
        var provider = provider(registry);
        var first = provide(provider, SECTION_A);

        provide(provider, SECTION_B);
        call(first, SUCCEEDING);

        assertThat(count(registry, ADMITTED, SECTION_A)).isEqualTo(1.0);
        assertThat(count(registry, ADMITTED, SECTION_B)).as("the other section's bucket was not used").isEqualTo(0.0);
    }

    @Test
    void redeploy_leavesOneSetOfMeters_boundToTheLiveLimiter() {
        var registry = new SimpleMeterRegistry();
        var provider = provider(registry);
        var old = provide(provider, SECTION_A);

        call(old, SUCCEEDING);
        provider.releaseAll(SLICE).await();
        assertThat(registry.find(ADMITTED).counters()).as("unload removes the admitted counter").isEmpty();
        assertThat(registry.find(REJECTED).counters()).as("unload removes the rejected counter").isEmpty();

        var live = provide(provider, SECTION_A);

        assertThat(registry.find(ADMITTED).counters()).as("one counter, not two").hasSize(1);
        assertThat(count(registry, ADMITTED, SECTION_A)).as("fresh series").isEqualTo(0.0);

        call(live, SUCCEEDING);

        assertThat(count(registry, ADMITTED, SECTION_A)).isEqualTo(1.0);
    }

    private static Promise<String> countedRun(AtomicInteger runs, String request) {
        runs.incrementAndGet();
        return Promise.success(request);
    }

    private static SpiResourceProvider provider(MeterRegistry registry) {
        var provider = SpiResourceProvider.spiResourceProvider(List.<ResourceFactory<?, ?>> of(new RateLimitInterceptorFactory()),
                                                               (_, _) -> Result.success(config()));

        provider.registerExtension(MeterRegistry.class, registry);
        return provider;
    }

    private static RateLimitMethodInterceptor provide(SpiResourceProvider provider, String section) {
        var context = ProvisioningContext.provisioningContext().withExtension(String.class, SLICE);

        return provider.provide(RateLimitMethodInterceptor.class, section, context).await().unwrap();
    }
}
