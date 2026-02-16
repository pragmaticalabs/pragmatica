package org.pragmatica.aether.environment;

import org.pragmatica.lang.Result;

import java.util.List;
import java.util.Set;

import static org.pragmatica.lang.Result.success;

/// Full snapshot of expected load balancer state, used for reconciliation.
public record LoadBalancerState(Set<String> activeNodeIps, List<RouteChange> routes) {
    public static Result<LoadBalancerState> loadBalancerState(Set<String> activeNodeIps,
                                                              List<RouteChange> routes) {
        return success(new LoadBalancerState(Set.copyOf(activeNodeIps), List.copyOf(routes)));
    }
}
