package org.pragmatica.aether.environment;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import java.util.Map;

/// SPI for managing external load balancer configuration.
/// Implementations synchronize route and target changes with a cloud load balancer
/// (e.g., Hetzner LB, AWS ALB) so that external traffic reaches the correct nodes.
public interface LoadBalancerProvider {
    Promise<Unit> onRouteChanged(RouteChange routeChange);

    Promise<Unit> onNodeRemoved(String nodeIp);

    Promise<Unit> reconcile(LoadBalancerState state);

    /// Create a new load balancer with the given specification.
    default Promise<LoadBalancerInfo> createLoadBalancer(LoadBalancerSpec spec) {
        return EnvironmentError.operationNotSupported("createLoadBalancer")
                               .promise();
    }

    /// Delete a load balancer by its provider-assigned ID.
    default Promise<Unit> deleteLoadBalancer(String loadBalancerId) {
        return EnvironmentError.operationNotSupported("deleteLoadBalancer")
                               .promise();
    }

    /// Get current load balancer info (targets, health, algorithm).
    default Promise<LoadBalancerInfo> loadBalancerInfo() {
        return EnvironmentError.operationNotSupported("loadBalancerInfo")
                               .promise();
    }

    /// Configure health check parameters for the load balancer. Default: no-op.
    default Promise<Unit> configureHealthCheck(HealthCheckConfig healthCheck) {
        return Promise.success(Unit.unit());
    }

    /// Sync weighted routing from Aether rolling update weights to cloud LB.
    /// weightsByIp maps node IP to weight (0-100). Weight 0 means drain. Default: no-op.
    default Promise<Unit> syncWeights(Map<String, Integer> weightsByIp) {
        return Promise.success(Unit.unit());
    }

    /// Deregister a target with graceful drain (connection draining delay).
    /// Default: immediate removal via onNodeRemoved.
    default Promise<Unit> deregisterWithDrain(String nodeIp, TimeSpan drainTimeout) {
        return onNodeRemoved(nodeIp);
    }

    /// Configure TLS termination on the load balancer.
    default Promise<Unit> configureTls(TlsTerminationConfig tlsConfig) {
        return EnvironmentError.operationNotSupported("configureTls")
                               .promise();
    }
}
