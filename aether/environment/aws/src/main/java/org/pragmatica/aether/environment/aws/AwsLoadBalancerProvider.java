package org.pragmatica.aether.environment.aws;

import org.pragmatica.aether.environment.LoadBalancerInfo;
import org.pragmatica.aether.environment.LoadBalancerProvider;
import org.pragmatica.aether.environment.LoadBalancerState;
import org.pragmatica.aether.environment.RouteChange;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.cloud.aws.AwsClient;
import org.pragmatica.cloud.aws.api.TargetHealth;
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

/// AWS ELBv2 target-group-based load balancer provider.
/// Manages instance-based targets on a pre-existing ALB/NLB target group.
public record AwsLoadBalancerProvider(AwsClient client,
                                      String targetGroupArn) implements LoadBalancerProvider {
    private static final Logger log = LoggerFactory.getLogger(AwsLoadBalancerProvider.class);

    /// Factory method for creating an AwsLoadBalancerProvider.
    public static Result<AwsLoadBalancerProvider> awsLoadBalancerProvider(AwsClient client,
                                                                          String targetGroupArn) {
        return success(new AwsLoadBalancerProvider(client, targetGroupArn));
    }

    @Override
    public Promise<Unit> onRouteChanged(RouteChange routeChange) {
        log.debug("Registering {} targets for route {} {}",
                  routeChange.nodeIps()
                             .size(),
                  routeChange.httpMethod(),
                  routeChange.pathPrefix());
        var instanceIds = List.copyOf(routeChange.nodeIps());
        return client.registerTargets(targetGroupArn, instanceIds);
    }

    @Override
    public Promise<Unit> onNodeRemoved(String nodeIp) {
        log.debug("Deregistering target {} from target group {}", nodeIp, targetGroupArn);
        return client.deregisterTargets(targetGroupArn, List.of(nodeIp));
    }

    @Override
    public Promise<LoadBalancerInfo> loadBalancerInfo() {
        return client.describeTargetHealth(targetGroupArn)
                     .map(this::toLoadBalancerInfo);
    }

    // --- Leaf: map target health list to LoadBalancerInfo ---
    private LoadBalancerInfo toLoadBalancerInfo(List<TargetHealth> targets) {
        var targetInfos = targets.stream()
                                 .map(t -> new LoadBalancerInfo.TargetInfo(t.targetId(),
                                                                           t.state(),
                                                                           1))
                                 .toList();
        return new LoadBalancerInfo(targetGroupArn, targetGroupArn, "", "active", targetInfos);
    }

    @Override
    public Promise<Unit> deregisterWithDrain(String nodeIp, TimeSpan drainTimeout) {
        // AWS ELBv2 handles connection draining automatically on deregistration
        return onNodeRemoved(nodeIp);
    }

    @Override
    public Promise<Unit> reconcile(LoadBalancerState state) {
        log.debug("Reconciling target group {} with {} active nodes",
                  targetGroupArn,
                  state.activeNodeIps()
                       .size());
        var desiredIds = state.activeNodeIps();
        return client.describeTargetHealth(targetGroupArn)
                     .map(AwsLoadBalancerProvider::currentTargetIds)
                     .flatMap(currentIds -> reconcileDiff(currentIds, desiredIds));
    }

    // --- Leaf: extract current target IDs from health response ---
    private static Set<String> currentTargetIds(List<TargetHealth> targets) {
        return targets.stream()
                      .map(TargetHealth::targetId)
                      .collect(Collectors.toSet());
    }

    // --- Leaf: compute IDs present in desired but missing from current ---
    private static List<String> missingIds(Set<String> currentIds, Set<String> desiredIds) {
        return desiredIds.stream()
                         .filter(Predicate.not(currentIds::contains))
                         .toList();
    }

    // --- Leaf: compute IDs present in current but not in desired ---
    private static List<String> surplusIds(Set<String> currentIds, Set<String> desiredIds) {
        return currentIds.stream()
                         .filter(Predicate.not(desiredIds::contains))
                         .toList();
    }

    // --- Sequencer: compute and apply diff between current and desired state ---
    private Promise<Unit> reconcileDiff(Set<String> currentIds, Set<String> desiredIds) {
        var idsToRegister = missingIds(currentIds, desiredIds);
        var idsToDeregister = surplusIds(currentIds, desiredIds);
        log.debug("Reconciliation diff: {} to add, {} to remove", idsToRegister.size(), idsToDeregister.size());
        var registerOp = registerIfNotEmpty(idsToRegister);
        var deregisterOp = deregisterIfNotEmpty(idsToDeregister);
        var all = Stream.concat(registerOp.stream(),
                                deregisterOp.stream())
                        .toList();
        return combineAll(all);
    }

    // --- Leaf: register targets if list is non-empty ---
    private java.util.Optional<Promise<Unit>> registerIfNotEmpty(List<String> ids) {
        return ids.isEmpty()
               ? java.util.Optional.empty()
               : java.util.Optional.of(client.registerTargets(targetGroupArn, ids));
    }

    // --- Leaf: deregister targets if list is non-empty ---
    private java.util.Optional<Promise<Unit>> deregisterIfNotEmpty(List<String> ids) {
        return ids.isEmpty()
               ? java.util.Optional.empty()
               : java.util.Optional.of(client.deregisterTargets(targetGroupArn, ids));
    }

    // --- Leaf: combine a collection of Promise<Unit> into a single Promise<Unit> ---
    private static Promise<Unit> combineAll(Collection<Promise<Unit>> promises) {
        if (promises.isEmpty()) {
            return Promise.success(Unit.unit());
        }
        return Promise.allOf(promises)
                      .map(AwsLoadBalancerProvider::collectResults)
                      .flatMap(Result::async);
    }

    // --- Leaf: collect results into a single unit ---
    private static Result<Unit> collectResults(List<Result<Unit>> results) {
        return Result.allOf(results)
                     .map(Unit::toUnit);
    }
}
