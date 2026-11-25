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
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.CircuitBreaker.CircuitBreakerError.CircuitBreakerOpenError;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #280 R27: the circuit breaker's state, transitions and rejections are metered, per slice and section, and the meters
/// follow the provisioning: absent without a registry, one set per live instance across a redeploy.
class CircuitBreakerMetersTest {
    private static final String STATE = "aether.interceptor.circuit_breaker.state";
    private static final String TRANSITIONS = "aether.interceptor.circuit_breaker.transitions";
    private static final String REJECTED = "aether.interceptor.circuit_breaker.rejected";
    private static final String SLICE = "org.example:orders:1.0.0";
    private static final String SECTION_A = "circuit-breaker.payments";
    private static final String SECTION_B = "circuit-breaker.inventory";

    private static final Fn1<Promise<String>, String> FAILING = _ -> Causes.cause("boom").promise();
    private static final Fn1<Promise<String>, String> SUCCEEDING = request -> Promise.success("ok-" + request);

    private static CircuitBreakerConfig config() {
        return CircuitBreakerConfig.circuitBreakerConfig(2, timeSpan(1).hours(), 1).unwrap();
    }

    private static ProvisioningContext context(MeterRegistry registry, String section) {
        return ProvisioningContext.provisioningContext()
                                  .withExtension(MeterRegistry.class, registry)
                                  .withExtension(String.class, SLICE)
                                  .withExtension(ResourceSection.class, ResourceSection.resourceSection(section));
    }

    private static CircuitBreakerMethodInterceptor provision(ProvisioningContext context) {
        return new CircuitBreakerInterceptorFactory().provision(config(), context).await().unwrap();
    }

    private static Result<String> call(CircuitBreakerMethodInterceptor interceptor, Fn1<Promise<String>, String> method) {
        return interceptor.intercept(method).apply("x").await();
    }

    private static Double gauge(MeterRegistry registry, String section) {
        var gauge = registry.find(STATE).tags("slice", SLICE, "section", section).gauge();

        return gauge == null ? null : gauge.value();
    }

    private static double transitions(MeterRegistry registry, String section, String from, String to) {
        var counter = registry.find(TRANSITIONS).tags("slice", SLICE, "section", section, "from", from, "to", to).counter();

        return counter == null ? -1 : counter.count();
    }

    private static double rejected(MeterRegistry registry, String section) {
        var counter = registry.find(REJECTED).tags("slice", SLICE, "section", section).counter();

        return counter == null ? -1 : counter.count();
    }

    @Test
    void provisioned_registersTheStateGauge_taggedBySliceAndSection() {
        var registry = new SimpleMeterRegistry();

        provision(context(registry, SECTION_A));

        assertThat(gauge(registry, SECTION_A)).isEqualTo(0.0);
    }

    @Test
    void tripping_movesTheGauge_andCountsTheTransitionFromClosedToOpen() {
        var registry = new SimpleMeterRegistry();
        var interceptor = provision(context(registry, SECTION_A));

        call(interceptor, FAILING);
        call(interceptor, FAILING);

        assertThat(gauge(registry, SECTION_A)).as("open = 1").isEqualTo(1.0);
        assertThat(transitions(registry, SECTION_A, "CLOSED", "OPEN")).isEqualTo(1.0);
        assertThat(transitions(registry, SECTION_A, "OPEN", "CLOSED")).as("the reverse direction").isEqualTo(0.0);
    }

    @Test
    void openBreaker_countsRejections_andNeverRunsTheMethod() {
        var registry = new SimpleMeterRegistry();
        var interceptor = provision(context(registry, SECTION_A));
        var runs = new AtomicInteger();

        call(interceptor, FAILING);
        call(interceptor, FAILING);
        var rejected = call(interceptor, request -> countedRun(runs, request));

        assertThat(rejected.isFailure()).isTrue();
        assertThat(runs.get()).as("the open breaker refused the call").isZero();
        assertThat(rejected(registry, SECTION_A)).isEqualTo(1.0);
    }

    @Test
    void openErrorFromInsideTheMethod_isNotThisBreakersRejection() {
        var registry = new SimpleMeterRegistry();
        var interceptor = provision(context(registry, SECTION_A));
        Fn1<Promise<String>, String> downstreamOpen = _ -> CircuitBreakerOpenError.circuitBreakerOpenError("downstream",
                                                                                                       timeSpan(1).seconds())
                                                                                .promise();

        call(interceptor, downstreamOpen);

        assertThat(rejected(registry, SECTION_A)).isEqualTo(0.0);
    }

    @Test
    void successfulCalls_changeNothing() {
        var registry = new SimpleMeterRegistry();
        var interceptor = provision(context(registry, SECTION_A));

        assertThat(call(interceptor, SUCCEEDING).isSuccess()).isTrue();
        assertThat(gauge(registry, SECTION_A)).isEqualTo(0.0);
        assertThat(rejected(registry, SECTION_A)).isEqualTo(0.0);
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

        var plain = new CircuitBreakerInterceptorFactory().provision(config()).await().unwrap();

        assertThat(call(plain, SUCCEEDING).isSuccess()).isTrue();
        assertThat(registry.getMeters()).as("no extension, no meters").isEmpty();
    }

    @Test
    void twoSectionsOfOneSlice_haveSeparateMeters() {
        var registry = new SimpleMeterRegistry();
        var provider = provider(registry);
        var first = provide(provider, SECTION_A);
        var second = provide(provider, SECTION_B);

        call(first, FAILING);
        call(first, FAILING);

        assertThat(second).isNotSameAs(first);
        assertThat(gauge(registry, SECTION_A)).isEqualTo(1.0);
        assertThat(gauge(registry, SECTION_B)).as("the other section's breaker is closed").isEqualTo(0.0);
    }

    @Test
    void redeploy_leavesOneGauge_boundToTheLiveBreaker() {
        var registry = new SimpleMeterRegistry();
        var provider = provider(registry);

        var old = provide(provider, SECTION_A);

        call(old, FAILING);
        provider.releaseAll(SLICE).await();
        assertThat(registry.find(STATE).gauges()).as("unload removes the gauge").isEmpty();
        assertThat(registry.find(TRANSITIONS).counters()).as("unload removes the transition counters").isEmpty();
        assertThat(registry.find(REJECTED).counters()).as("unload removes the rejection counter").isEmpty();

        var live = provide(provider, SECTION_A);

        assertThat(live).isNotSameAs(old);
        assertThat(registry.find(STATE).gauges()).as("one gauge, not two").hasSize(1);
        assertThat(gauge(registry, SECTION_A)).as("fresh breaker is closed").isEqualTo(0.0);

        call(live, FAILING);
        call(live, FAILING);

        assertThat(gauge(registry, SECTION_A)).as("the gauge follows the live breaker").isEqualTo(1.0);
    }

    private static Promise<String> countedRun(AtomicInteger runs, String request) {
        runs.incrementAndGet();
        return Promise.success(request);
    }

    private static SpiResourceProvider provider(MeterRegistry registry) {
        var provider = SpiResourceProvider.spiResourceProvider(List.<ResourceFactory<?, ?>> of(new CircuitBreakerInterceptorFactory()),
                                                               (_, _) -> Result.success(config()));

        provider.registerExtension(MeterRegistry.class, registry);
        return provider;
    }

    private static CircuitBreakerMethodInterceptor provide(SpiResourceProvider provider, String section) {
        var context = ProvisioningContext.provisioningContext().withExtension(String.class, SLICE);

        return provider.provide(CircuitBreakerMethodInterceptor.class, section, context).await().unwrap();
    }
}
