package org.pragmatica.aether.environment.hetzner;

import org.pragmatica.aether.environment.LoadBalancerProvider;
import org.pragmatica.aether.environment.LoadBalancerState;
import org.pragmatica.aether.environment.RouteChange;
import org.pragmatica.cloud.hetzner.HetznerClient;
import org.pragmatica.cloud.hetzner.api.LoadBalancer;
import org.pragmatica.cloud.hetzner.api.LoadBalancer.Target;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Result.success;

/// Hetzner Cloud L4 load balancer provider.
/// Manages IP-based targets on a pre-existing Hetzner load balancer.
public record HetznerLoadBalancerProvider(HetznerClient client,
                                          long loadBalancerId,
                                          int destinationPort) implements LoadBalancerProvider {
    private static final Logger log = LoggerFactory.getLogger(HetznerLoadBalancerProvider.class);

    /// Factory method for creating a HetznerLoadBalancerProvider.
    public static Result<HetznerLoadBalancerProvider> hetznerLoadBalancerProvider(HetznerClient client,
                                                                                  long loadBalancerId,
                                                                                  int destinationPort) {
        return success(new HetznerLoadBalancerProvider(client, loadBalancerId, destinationPort));
    }

    @Override
    public Promise<Unit> onRouteChanged(RouteChange routeChange) {
        log.debug("Adding {} IP targets for route {} {}",
                  routeChange.nodeIps()
                             .size(),
                  routeChange.httpMethod(),
                  routeChange.pathPrefix());
        var targets = routeChange.nodeIps()
                                 .stream()
                                 .map(this::ipTargetRegistration)
                                 .toList();
        return combineAll(targets);
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
        var desiredIps = state.activeNodeIps();
        return client.getLoadBalancer(loadBalancerId)
                     .map(this::currentIpsFromLb)
                     .flatMap(currentIps -> reconcileDiff(currentIps, desiredIps));
    }

    // --- Leaf: resolve current IPs from a load balancer ---
    private Set<String> currentIpsFromLb(LoadBalancer lb) {
        return resolveCurrentIps(lb.targets());
    }

    // --- Leaf: register a single IP target ---
    private Promise<Unit> ipTargetRegistration(String ip) {
        return client.addIpTarget(loadBalancerId, ip);
    }

    // --- Leaf: unregister a single IP target ---
    private Promise<Unit> ipTargetUnregistration(String ip) {
        return client.removeIpTarget(loadBalancerId, ip);
    }

    // --- Leaf: resolve current IP targets from load balancer ---
    private static Set<String> resolveCurrentIps(List<Target> targets) {
        return targets.stream()
                      .filter(HetznerLoadBalancerProvider::isIpTarget)
                      .map(HetznerLoadBalancerProvider::ipOf)
                      .collect(Collectors.toSet());
    }

    // --- Leaf: resolve IP string from target ---
    private static String ipOf(Target target) {
        return target.ip()
                     .ip();
    }

    // --- Leaf: check if target is IP-based ---
    private static boolean isIpTarget(Target target) {
        return "ip".equals(target.type()) && target.ip() != null;
    }

    // --- Leaf: compute IPs present in desired but missing from current ---
    private static List<String> missingIps(Set<String> currentIps, Set<String> desiredIps) {
        return desiredIps.stream()
                         .filter(Predicate.not(currentIps::contains))
                         .toList();
    }

    // --- Leaf: compute IPs present in current but not in desired ---
    private static List<String> surplusIps(Set<String> currentIps, Set<String> desiredIps) {
        return currentIps.stream()
                         .filter(Predicate.not(desiredIps::contains))
                         .toList();
    }

    // --- Sequencer: compute and apply diff between current and desired state ---
    private Promise<Unit> reconcileDiff(Set<String> currentIps, Set<String> desiredIps) {
        var ipsToRegister = missingIps(currentIps, desiredIps);
        var ipsToUnregister = surplusIps(currentIps, desiredIps);
        log.debug("Reconciliation diff: {} to add, {} to remove", ipsToRegister.size(), ipsToUnregister.size());
        var registerOps = ipsToRegister.stream()
                                       .map(this::ipTargetRegistration);
        var unregisterOps = ipsToUnregister.stream()
                                           .map(this::ipTargetUnregistration);
        var all = Stream.concat(registerOps, unregisterOps)
                        .toList();
        return combineAll(all);
    }

    // --- Leaf: combine a collection of Promise<Unit> into a single Promise<Unit> ---
    private static Promise<Unit> combineAll(Collection<Promise<Unit>> promises) {
        return Promise.allOf(promises)
                      .map(HetznerLoadBalancerProvider::collectResults)
                      .flatMap(Result::async);
    }

    // --- Leaf: collect results into a single unit ---
    private static Result<Unit> collectResults(List<Result<Unit>> results) {
        return Result.allOf(results)
                     .map(Unit::toUnit);
    }
}
