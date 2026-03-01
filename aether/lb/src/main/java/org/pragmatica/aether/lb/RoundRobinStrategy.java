package org.pragmatica.aether.lb;

import org.pragmatica.http.server.RequestContext;
import org.pragmatica.lang.Option;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/// Round-robin routing strategy.
///
/// Distributes requests evenly across healthy backends using
/// an atomic counter with modular arithmetic.
public final class RoundRobinStrategy implements RoutingStrategy {
    private final AtomicInteger counter = new AtomicInteger(0);

    /// Create a round-robin routing strategy.
    public static RoundRobinStrategy roundRobinStrategy() {
        return new RoundRobinStrategy();
    }

    @Override
    public Option<Backend> select(List<Backend> healthy, RequestContext request) {
        if (healthy.isEmpty()) {
            return Option.empty();
        }
        var index = Math.abs(counter.getAndIncrement() % healthy.size());
        return Option.some(healthy.get(index));
    }
}
