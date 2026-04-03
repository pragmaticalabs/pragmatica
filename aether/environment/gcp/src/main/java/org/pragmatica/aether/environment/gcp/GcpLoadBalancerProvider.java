package org.pragmatica.aether.environment.gcp;

import org.pragmatica.aether.environment.LoadBalancerInfo;
import org.pragmatica.aether.environment.LoadBalancerProvider;
import org.pragmatica.aether.environment.LoadBalancerState;
import org.pragmatica.aether.environment.RouteChange;
import org.pragmatica.cloud.gcp.GcpClient;
import org.pragmatica.cloud.gcp.api.NetworkEndpoint;
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


/// GCP Cloud NEG-based load balancer provider.
/// Manages network endpoints on a pre-existing GCP Network Endpoint Group (NEG).
public record GcpLoadBalancerProvider(GcpClient client, String negName, int destinationPort) implements LoadBalancerProvider {
    private static final Logger log = LoggerFactory.getLogger(GcpLoadBalancerProvider.class);

    public static Result<GcpLoadBalancerProvider> gcpLoadBalancerProvider(GcpClient client,
                                                                          String negName,
                                                                          int destinationPort) {
        return success(new GcpLoadBalancerProvider(client, negName, destinationPort));
    }

    @Override public Promise<Unit> onRouteChanged(RouteChange routeChange) {
        log.debug("Adding {} endpoints for route {} {}",
                  routeChange.nodeIps().size(),
                  routeChange.httpMethod(),
                  routeChange.pathPrefix());
        var attachOps = routeChange.nodeIps().stream()
                                           .map(this::attachEndpoint)
                                           .toList();
        return combineAll(attachOps);
    }

    @Override public Promise<Unit> onNodeRemoved(String nodeIp) {
        log.debug("Removing endpoint {} from NEG {}", nodeIp, negName);
        return client.detachNetworkEndpoint(negName, toEndpoint(nodeIp)).mapToUnit();
    }

    @Override public Promise<LoadBalancerInfo> loadBalancerInfo() {
        return client.listNetworkEndpoints(negName).map(this::toLoadBalancerInfo);
    }

    private LoadBalancerInfo toLoadBalancerInfo(List<NetworkEndpoint> endpoints) {
        var targetInfos = endpoints.stream().map(ep -> new LoadBalancerInfo.TargetInfo(ep.ipAddress(),
                                                                                       "healthy",
                                                                                       1))
                                          .toList();
        return new LoadBalancerInfo(negName, negName, "", "active", targetInfos);
    }

    @Override public Promise<Unit> reconcile(LoadBalancerState state) {
        log.debug("Reconciling NEG {} with {} active nodes",
                  negName,
                  state.activeNodeIps().size());
        var desiredIps = state.activeNodeIps();
        return client.listNetworkEndpoints(negName).map(GcpLoadBalancerProvider::currentIpsFromEndpoints)
                                          .flatMap(currentIps -> reconcileDiff(currentIps, desiredIps));
    }

    private Promise<Unit> attachEndpoint(String ip) {
        return client.attachNetworkEndpoint(negName, toEndpoint(ip)).mapToUnit();
    }

    private Promise<Unit> detachEndpoint(String ip) {
        return client.detachNetworkEndpoint(negName, toEndpoint(ip)).mapToUnit();
    }

    @SuppressWarnings("JBCT-RET-03") private NetworkEndpoint toEndpoint(String ip) {
        return new NetworkEndpoint(ip, destinationPort, null);
    }

    private static Set<String> currentIpsFromEndpoints(List<NetworkEndpoint> endpoints) {
        return endpoints.stream().map(NetworkEndpoint::ipAddress)
                               .collect(Collectors.toSet());
    }

    private static List<String> missingIps(Set<String> currentIps, Set<String> desiredIps) {
        return desiredIps.stream().filter(Predicate.not(currentIps::contains))
                                .toList();
    }

    private static List<String> surplusIps(Set<String> currentIps, Set<String> desiredIps) {
        return currentIps.stream().filter(Predicate.not(desiredIps::contains))
                                .toList();
    }

    private Promise<Unit> reconcileDiff(Set<String> currentIps, Set<String> desiredIps) {
        var ipsToAttach = missingIps(currentIps, desiredIps);
        var ipsToDetach = surplusIps(currentIps, desiredIps);
        log.debug("Reconciliation diff: {} to attach, {} to detach", ipsToAttach.size(), ipsToDetach.size());
        var attachOps = ipsToAttach.stream().map(this::attachEndpoint);
        var detachOps = ipsToDetach.stream().map(this::detachEndpoint);
        var all = Stream.concat(attachOps, detachOps).toList();
        return combineAll(all);
    }

    private static Promise<Unit> combineAll(Collection<Promise<Unit>> promises) {
        return Promise.allOf(promises).map(GcpLoadBalancerProvider::collectResults)
                            .flatMap(Result::async);
    }

    private static Result<Unit> collectResults(List<Result<Unit>> results) {
        return Result.allOf(results).map(Unit::toUnit);
    }
}
