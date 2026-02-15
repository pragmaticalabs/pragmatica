package org.pragmatica.aether.environment;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

/// SPI for managing external load balancer configuration.
/// Implementations synchronize route and target changes with a cloud load balancer
/// (e.g., Hetzner LB, AWS ALB) so that external traffic reaches the correct nodes.
public interface LoadBalancerProvider {
    Promise<Unit> onRouteChanged(RouteChange routeChange);

    Promise<Unit> onNodeRemoved(String nodeIp);

    Promise<Unit> reconcile(LoadBalancerState state);
}
