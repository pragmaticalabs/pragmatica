// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
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

    default Promise<LoadBalancerInfo> createLoadBalancer(LoadBalancerSpec spec) {
        return EnvironmentError.operationNotSupported("createLoadBalancer").promise();
    }

    default Promise<Unit> deleteLoadBalancer(String loadBalancerId) {
        return EnvironmentError.operationNotSupported("deleteLoadBalancer").promise();
    }

    default Promise<LoadBalancerInfo> loadBalancerInfo() {
        return EnvironmentError.operationNotSupported("loadBalancerInfo").promise();
    }

    default Promise<Unit> configureHealthCheck(HealthCheckConfig healthCheck) {
        return Promise.success(Unit.unit());
    }

    default Promise<Unit> syncWeights(Map<String, Integer> weightsByIp) {
        return Promise.success(Unit.unit());
    }

    default Promise<Unit> deregisterWithDrain(String nodeIp, TimeSpan drainTimeout) {
        return onNodeRemoved(nodeIp);
    }

    default Promise<Unit> configureTls(TlsTerminationConfig tlsConfig) {
        return EnvironmentError.operationNotSupported("configureTls").promise();
    }
}
