// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.pragmatica.aether.resource.ResourceSection;
import org.pragmatica.aether.slice.MethodInterceptor;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.CircuitBreaker;
import org.pragmatica.lang.utils.CircuitBreaker.CircuitBreakerError.CircuitBreakerOpenError;
import org.pragmatica.lang.utils.CircuitBreaker.State;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;


/// A circuit breaker around one section's calls, with optional meters (#280 R27).
///
/// `meters` is present only when provisioning found a `MeterRegistry`, the slice id and the [ResourceSection] in its
/// context; otherwise the breaker runs unmetered, exactly as before. Meters are per node and per provision: the breaker
/// instance is per `(slice, section)` on one node, so each node reports its own breaker.
public record CircuitBreakerMethodInterceptor(CircuitBreaker breaker, Option<Meters> meters) implements MethodInterceptor {
    public static CircuitBreakerMethodInterceptor circuitBreakerMethodInterceptor(CircuitBreaker breaker) {
        return new CircuitBreakerMethodInterceptor(breaker, Option.none());
    }

    public static CircuitBreakerMethodInterceptor circuitBreakerMethodInterceptor(CircuitBreaker breaker,
                                                                                  ProvisioningContext context) {
        return new CircuitBreakerMethodInterceptor(breaker, Meters.bind(breaker, context));
    }

    @Override
    public <R, T> Fn1<Promise<R>, T> intercept(Fn1<Promise<R>, T> method) {
        return request -> meters.fold(() -> breaker.execute(() -> method.apply(request)),
                                      bound -> metered(bound, method, request));
    }

    /// Removes this breaker's meters from the registry; the factory calls it when the provider unloads the resource.
    public Unit release() {
        return meters.map(Meters::release)
                     .or(Unit.unit());
    }

    private <R, T> Promise<R> metered(Meters bound, Fn1<Promise<R>, T> method, T request) {
        var executed = new AtomicBoolean();

        bound.observe(breaker);

        return breaker.execute(() -> method.apply(bound.executing(executed, request)))
                      .withResult(result -> bound.settle(breaker,
                                                         executed.get(),
                                                         result));
    }

    /// The breaker's meters. The state gauge reads the live breaker (0 closed, 1 open, 2 half-open). Transitions are
    /// counted as OBSERVED at call boundaries, because the core breaker has no transition listener: two transitions
    /// between consecutive calls (closed to open to half-open) are counted as the one change a caller could see.
    /// `rejected` counts calls the breaker refused without running the method.
    record Meters(MeterRegistry registry,
                  List<Meter> owned,
                  Counter rejected,
                  Map<Transition, Counter> transitions,
                  AtomicReference<State> observed) {
        private static final String PREFIX = "aether.interceptor.circuit_breaker.";

        record Transition(State from, State to) {
            static Transition transition(State from, State to) {
                return new Transition(from, to);
            }
        }

        static Option<Meters> bind(CircuitBreaker breaker, ProvisioningContext context) {
            return Result.all(context.extension(MeterRegistry.class),
                              context.extension(String.class),
                              context.extension(ResourceSection.class))
                         .map((registry, slice, section) -> register(registry,
                                                                     breaker,
                                                                     Tags.of("slice",
                                                                             slice,
                                                                             "section",
                                                                             section.name())))
                         .option();
        }

        private static Meters register(MeterRegistry registry, CircuitBreaker breaker, Tags tags) {
            var gauge = Gauge.builder(PREFIX + "state", breaker, Meters::stateCode).tags(tags).register(registry);
            var rejected = registry.counter(PREFIX + "rejected", tags);
            var transitions = transitionCounters(registry, tags);
            var owned = new ArrayList<Meter>(transitions.values());

            owned.add(gauge);
            owned.add(rejected);

            return new Meters(registry,
                              List.copyOf(owned),
                              rejected,
                              transitions,
                              new AtomicReference<>(breaker.state()));
        }

        private static Map<Transition, Counter> transitionCounters(MeterRegistry registry, Tags tags) {
            return changes().stream()
                          .collect(Collectors.toUnmodifiableMap(Function.identity(),
                                                                change -> transitionCounter(registry, tags, change)));
        }

        private static List<Transition> changes() {
            return Arrays.stream(State.values())
                         .flatMap(from -> Arrays.stream(State.values())
                                                .filter(to -> to != from)
                                                .map(to -> Transition.transition(from, to)))
                         .toList();
        }

        private static Counter transitionCounter(MeterRegistry registry, Tags tags, Transition change) {
            return registry.counter(PREFIX + "transitions",
                                    tags.and("from",
                                             change.from().name(),
                                             "to",
                                             change.to().name()));
        }

        private static double stateCode(CircuitBreaker breaker) {
            return breaker.state()
                          .ordinal();
        }

        /// Marks the call as run by the breaker; evaluated only inside the breaker's supplier.
        <T> T executing(AtomicBoolean executed, T request) {
            executed.set(true);

            return request;
        }

        @Contract
        void observe(CircuitBreaker breaker) {
            var current = breaker.state();
            var previous = observed.getAndSet(current);

            if (previous != current) {
                transitions.get(Transition.transition(previous, current)).increment();
            }
        }

        /// A rejection is the breaker's own open error on a call whose method never ran: the same cause coming back
        /// from inside the method (a downstream breaker) is not this breaker's rejection.
        @Contract
        void settle(CircuitBreaker breaker, boolean executed, Result<?> result) {
            observe(breaker);
            if (!executed && result.fold(cause -> cause instanceof CircuitBreakerOpenError, _ -> false)) {
                rejected.increment();
            }
        }

        Unit release() {
            owned.forEach(registry::remove);

            return Unit.unit();
        }
    }
}
