package org.pragmatica.aether.environment.hetzner;

import org.pragmatica.aether.environment.LoadBalancerProvider;
import org.pragmatica.aether.environment.LoadBalancerState;
import org.pragmatica.aether.environment.RouteChange;
import org.pragmatica.cloud.hetzner.HetznerClient;
import org.pragmatica.cloud.hetzner.api.LoadBalancer.Target;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Hetzner Cloud L4 load balancer provider.
/// Manages IP-based targets on a pre-existing Hetzner load balancer.
public record HetznerLoadBalancerProvider(HetznerClient client,
                                          long loadBalancerId,
                                          int destinationPort) implements LoadBalancerProvider {
    private static final Logger log = LoggerFactory.getLogger(HetznerLoadBalancerProvider.class);

    /// Factory method for creating a HetznerLoadBalancerProvider.
    public static HetznerLoadBalancerProvider hetznerLoadBalancerProvider(HetznerClient client,
                                                                          long loadBalancerId,
                                                                          int destinationPort) {
        return new HetznerLoadBalancerProvider(client, loadBalancerId, destinationPort);
    }

    @Override
    public Promise<Unit> onRouteChanged(RouteChange routeChange) {
        log.debug("Adding {} IP targets for route {} {}",
                  routeChange.nodeIps()
                             .size(),
                  routeChange.httpMethod(),
                  routeChange.pathPrefix());
        return combineAll(routeChange.nodeIps()
                                     .stream()
                                     .map(this::addTarget)
                                     .toList());
    }

    @Override
    public Promise<Unit> onNodeRemoved(String nodeIp) {
        log.debug("Removing IP target {} from load balancer {}", nodeIp, loadBalancerId);
        return client.removeIpTarget(loadBalancerId, nodeIp);
    }

    @Override
    public Promise<Unit> reconcile(LoadBalancerState state) {
        log.debug("Reconciling load balancer {} with {} active nodes",
                  loadBalancerId,
                  state.activeNodeIps()
                       .size());
        return client.getLoadBalancer(loadBalancerId)
                     .flatMap(lb -> applyDiff(extractCurrentIps(lb.targets()),
                                              state.activeNodeIps()));
    }

    // --- Leaf: add a single IP target ---
    private Promise<Unit> addTarget(String ip) {
        return client.addIpTarget(loadBalancerId, ip);
    }

    // --- Leaf: extract current IP targets from load balancer ---
    private static Set<String> extractCurrentIps(java.util.List<Target> targets) {
        return targets.stream()
                      .filter(HetznerLoadBalancerProvider::isIpTarget)
                      .map(target -> target.ip()
                                           .ip())
                      .collect(Collectors.toSet());
    }

    // --- Leaf: check if target is IP-based ---
    private static boolean isIpTarget(Target target) {
        return "ip".equals(target.type()) && target.ip() != null;
    }

    // --- Sequencer: compute and apply diff between current and desired state ---
    private Promise<Unit> applyDiff(Set<String> currentIps, Set<String> desiredIps) {
        var toAdd = desiredIps.stream()
                              .filter(ip -> !currentIps.contains(ip))
                              .map(this::addTarget)
                              .toList();
        var toRemove = currentIps.stream()
                                 .filter(ip -> !desiredIps.contains(ip))
                                 .map(ip -> client.removeIpTarget(loadBalancerId, ip))
                                 .toList();
        log.debug("Reconciliation diff: {} to add, {} to remove", toAdd.size(), toRemove.size());
        var all = new java.util.ArrayList<Promise<Unit>>(toAdd.size() + toRemove.size());
        all.addAll(toAdd);
        all.addAll(toRemove);
        return combineAll(all);
    }

    // --- Leaf: combine a collection of Promise<Unit> into a single Promise<Unit> ---
    private static Promise<Unit> combineAll(java.util.Collection<Promise<Unit>> promises) {
        if (promises.isEmpty()) {
            return Promise.success(Unit.unit());
        }
        return Promise.allOf(promises)
                      .map(results -> Result.allOf(results)
                                            .map(Unit::toUnit))
                      .flatMap(Result::async);
    }
}
