// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.pragmatica.lang.utils.CircuitBreaker.StateListener;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// A circuit breaker around one section's calls, with optional meters (#280 R27).
///
/// `meters` is present only when provisioning found a `MeterRegistry`, the slice id and the [ResourceSection] in its
/// context; otherwise the breaker runs unmetered, exactly as before. Meters are per node and per provision: the breaker
/// instance is per `(slice, section)` on one node, so each node reports its own breaker.
public record CircuitBreakerMethodInterceptor(CircuitBreaker breaker, Option<Meters> meters) implements MethodInterceptor {
    public static CircuitBreakerMethodInterceptor circuitBreakerMethodInterceptor(CircuitBreaker breaker) {
        return new CircuitBreakerMethodInterceptor(breaker, none());
    }

    /// Builds the breaker through `breakerFor`, handing it the listener that counts its transitions, so the counters
    /// exist before the breaker and the gauge after it: no call can see a half-bound set. Without meters in the context
    /// `breakerFor` receives a listener that ignores every change.
    static CircuitBreakerMethodInterceptor circuitBreakerMethodInterceptor(Fn1<CircuitBreaker, StateListener> breakerFor,
                                                                           ProvisioningContext context) {
        return Counters.bind(context).fold(() -> circuitBreakerMethodInterceptor(breakerFor.apply(StateListener.none())),
                                           counters -> counters.interceptor(breakerFor));
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

        return breaker.execute(() -> method.apply(bound.executing(executed, request)))
                      .withResult(result -> bound.settle(executed.get(),
                                                         result));
    }

    private static final String PREFIX = "aether.interceptor.circuit_breaker.";

    /// The meters that exist before the breaker: the transition counters, which the breaker's own listener increments
    /// at the moment a transition happens (so each transition is counted once, whichever thread made it, the scheduler's
    /// open to half-open change included), and the rejection counter.
    record Counters(MeterRegistry registry, Tags tags, Counter rejected, Map<Transition, Counter> transitions) {
        record Transition(State from, State to) {
            static Transition transition(State from, State to) {
                return new Transition(from, to);
            }
        }

        static Option<Counters> bind(ProvisioningContext context) {
            return Result.all(context.extension(MeterRegistry.class),
                              context.extension(String.class),
                              context.extension(ResourceSection.class))
                         .map((registry, slice, section) -> counters(registry,
                                                                     Tags.of("slice",
                                                                             slice,
                                                                             "section",
                                                                             section.name())))
                         .option();
        }

        private static Counters counters(MeterRegistry registry, Tags tags) {
            return new Counters(registry,
                                tags,
                                counter(registry, PREFIX + "rejected", tags),
                                transitionCounters(registry, tags));
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
            return counter(registry,
                           PREFIX + "transitions",
                           tags.and("from",
                                    change.from().name(),
                                    "to",
                                    change.to().name()));
        }

        private static Counter counter(MeterRegistry registry, String name, Tags tags) {
            return MeterClaims.claim(registry, name, tags, () -> registry.counter(name, tags));
        }

        /// Counts every change the breaker reports. The listener is total: `transitions` holds every `from != to` pair,
        /// and the breaker reports only changes.
        StateListener listener() {
            return (from, to) -> transitions.get(Transition.transition(from, to))
                                            .increment();
        }

        CircuitBreakerMethodInterceptor interceptor(Fn1<CircuitBreaker, StateListener> breakerFor) {
            return bound(breakerFor.apply(listener()));
        }

        private CircuitBreakerMethodInterceptor bound(CircuitBreaker breaker) {
            return new CircuitBreakerMethodInterceptor(breaker, some(meters(breaker)));
        }

        private Meters meters(CircuitBreaker breaker) {
            var gauge = MeterClaims.claim(registry,
                                          PREFIX + "state",
                                          tags,
                                          () -> Gauge.builder(PREFIX + "state", breaker, Meters::stateCode)
                                                     .tags(tags)
                                                     .register(registry));
            var owned = new ArrayList<Meter>(transitions.values());

            owned.add(gauge);
            owned.add(rejected);

            return new Meters(registry, List.copyOf(owned), rejected);
        }
    }

    /// The breaker's meters. The state gauge reads the live breaker through an explicit mapping, not the enum's ordinal:
    /// 0 closed, 1 open, 2 half-open stay the published values if the core enum is reordered. `rejected` counts calls
    /// the breaker refused without running the method.
    record Meters(MeterRegistry registry, List<Meter> owned, Counter rejected) {
        static double stateCode(CircuitBreaker breaker) {
            return switch (breaker.state()) {
                case CLOSED -> 0;
                case OPEN -> 1;
                case HALF_OPEN -> 2;
            };
        }

        /// Marks the call as run by the breaker; evaluated only inside the breaker's supplier.
        <T> T executing(AtomicBoolean executed, T request) {
            executed.set(true);

            return request;
        }

        /// A rejection is the breaker's own open error on a call whose method never ran: the same cause coming back
        /// from inside the method (a downstream breaker) is not this breaker's rejection.
        @Contract
        void settle(boolean executed, Result<?> result) {
            if (!executed && result.fold(cause -> cause instanceof CircuitBreakerOpenError, _ -> false)) {
                rejected.increment();
            }
        }

        Unit release() {
            return MeterClaims.release(registry, owned);
        }
    }
}
