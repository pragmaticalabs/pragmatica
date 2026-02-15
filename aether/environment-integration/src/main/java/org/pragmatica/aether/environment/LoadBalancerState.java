package org.pragmatica.aether.environment;

import java.util.List;
import java.util.Set;

/// Full snapshot of expected load balancer state, used for reconciliation.
public record LoadBalancerState(Set<String> activeNodeIps, List<RouteChange> routes) {

    public static LoadBalancerState loadBalancerState(Set<String> activeNodeIps, List<RouteChange> routes) {
        return new LoadBalancerState(Set.copyOf(activeNodeIps), List.copyOf(routes));
    }
}
