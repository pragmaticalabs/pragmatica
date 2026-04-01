package org.pragmatica.aether.environment.azure;

import org.pragmatica.aether.environment.LoadBalancerInfo;
import org.pragmatica.aether.environment.LoadBalancerProvider;
import org.pragmatica.aether.environment.LoadBalancerState;
import org.pragmatica.aether.environment.RouteChange;
import org.pragmatica.cloud.azure.AzureClient;
import org.pragmatica.cloud.azure.api.AzureLoadBalancer;
import org.pragmatica.cloud.azure.api.AzureLoadBalancer.BackendAddress;
import org.pragmatica.cloud.azure.api.AzureLoadBalancer.BackendAddressProperties;
import org.pragmatica.cloud.azure.api.AzureLoadBalancer.BackendPool;
import org.pragmatica.cloud.azure.api.AzureLoadBalancer.PoolProperties;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;

/// Azure Cloud load balancer provider.
/// Manages backend address pool entries on a pre-existing Azure Load Balancer.
public record AzureLoadBalancerProvider( AzureClient client,
                                         String loadBalancerName,
                                         String backendPoolName,
                                         String vnetId) implements LoadBalancerProvider {
    private static final Logger log = LoggerFactory.getLogger(AzureLoadBalancerProvider.class);

    /// Factory method for creating an AzureLoadBalancerProvider.
    public static Result<AzureLoadBalancerProvider> azureLoadBalancerProvider(AzureClient client,
                                                                              String loadBalancerName,
                                                                              String backendPoolName,
                                                                              String vnetId) {
        return success(new AzureLoadBalancerProvider(client, loadBalancerName, backendPoolName, vnetId));
    }

    @Override public Promise<Unit> onRouteChanged(RouteChange routeChange) {
        log.debug("Updating backend pool for route {} {} with {} IPs",
                  routeChange.httpMethod(),
                  routeChange.pathPrefix(),
                  routeChange.nodeIps().size());
        return buildPoolFromIps(routeChange.nodeIps()).flatMap(this::updatePool);
    }

    @Override public Promise<Unit> onNodeRemoved(String nodeIp) {
        log.debug("Removing IP {} from backend pool {} on LB {}", nodeIp, backendPoolName, loadBalancerName);
        return client.getLb(loadBalancerName).map(this::currentIpsFromLb)
                           .flatMap(currentIps -> removeIpAndUpdate(currentIps, nodeIp));
    }

    @Override public Promise<LoadBalancerInfo> loadBalancerInfo() {
        return client.getLb(loadBalancerName).map(this::toLoadBalancerInfo);
    }

    // --- Leaf: map Azure LB to LoadBalancerInfo ---
    private LoadBalancerInfo toLoadBalancerInfo(AzureLoadBalancer lb) {
        var targets = extractLbTargetIps(lb).stream()
                                        .map(ip -> new LoadBalancerInfo.TargetInfo(ip, "active", 1))
                                        .toList();
        return new LoadBalancerInfo(lb.id(), lb.name(), "", "active", targets);
    }

    // --- Leaf: extract backend IPs from Azure LB for loadBalancerInfo ---
    private Set<String> extractLbTargetIps(AzureLoadBalancer lb) {
        return option(lb.properties()).flatMap(p -> option(p.backendAddressPools()))
                     .map(pools -> extractIpsFromPools(pools, backendPoolName))
                     .or(Set.of());
    }

    @Override public Promise<Unit> reconcile(LoadBalancerState state) {
        log.debug("Reconciling LB {} pool {} with {} active nodes",
                  loadBalancerName,
                  backendPoolName,
                  state.activeNodeIps().size());
        return buildPoolFromIps(state.activeNodeIps()).flatMap(this::updatePool);
    }

    // --- Leaf: build a backend pool from a set of IPs ---
    private Promise<BackendPool> buildPoolFromIps(Set<String> ips) {
        var addresses = ips.stream().map(this::toBackendAddress)
                                  .toList();
        var pool = new BackendPool(null, backendPoolName, new PoolProperties(addresses));
        return Promise.success(pool);
    }

    // --- Leaf: create a BackendAddress from an IP ---
    private BackendAddress toBackendAddress(String ip) {
        return new BackendAddress("addr-" + ip.replace('.', '-'), new BackendAddressProperties(ip));
    }

    // --- Leaf: update the backend pool on the load balancer ---
    private Promise<Unit> updatePool(BackendPool pool) {
        return client.updateLbBackendPool(loadBalancerName, backendPoolName, pool).mapToUnit();
    }

    // --- Leaf: resolve current IPs from a load balancer ---
    private Set<String> currentIpsFromLb(AzureLoadBalancer lb) {
        return option(lb.properties()).flatMap(props -> option(props.backendAddressPools()))
                     .map(pools -> extractIpsFromPools(pools, backendPoolName))
                     .or(Set.of());
    }

    // --- Leaf: extract IPs from the matching backend pool ---
    private static Set<String> extractIpsFromPools(List<BackendPool> pools, String poolName) {
        return pools.stream().filter(pool -> poolName.equals(pool.name()))
                           .findFirst()
                           .map(AzureLoadBalancerProvider::extractIpsFromPool)
                           .orElse(Set.of());
    }

    // --- Leaf: extract IP addresses from a single pool ---
    private static Set<String> extractIpsFromPool(BackendPool pool) {
        return option(pool.properties()).flatMap(props -> option(props.loadBalancerBackendAddresses()))
                     .map(AzureLoadBalancerProvider::toIpSet)
                     .or(Set.of());
    }

    // --- Leaf: convert backend addresses to IP set ---
    private static Set<String> toIpSet(List<BackendAddress> addresses) {
        return addresses.stream().map(AzureLoadBalancerProvider::ipOf)
                               .collect(Collectors.toSet());
    }

    // --- Leaf: extract IP from backend address ---
    private static String ipOf(BackendAddress addr) {
        return option(addr.properties()).map(BackendAddressProperties::ipAddress)
                     .or("0.0.0.0");
    }

    // --- Sequencer: remove an IP from current set and update pool ---
    private Promise<Unit> removeIpAndUpdate(Set<String> currentIps, String nodeIp) {
        var remaining = currentIps.stream().filter(Predicate.not(nodeIp::equals))
                                         .collect(Collectors.toSet());
        return buildPoolFromIps(remaining).flatMap(this::updatePool);
    }
}
