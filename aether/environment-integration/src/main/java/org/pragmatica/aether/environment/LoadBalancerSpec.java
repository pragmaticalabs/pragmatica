package org.pragmatica.aether.environment;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.List;
import java.util.Map;

import static org.pragmatica.lang.Result.success;

/// Specification for creating a load balancer.
public record LoadBalancerSpec( String name,
                                String algorithm,
                                List<ServicePort> servicePorts,
                                Option<String> region,
                                Map<String, String> tags) {
    /// A port mapping for load balancer traffic forwarding.
    public record ServicePort(String protocol, int listenPort, int destinationPort) {
        public static Result<ServicePort> servicePort(String protocol,
                                                      int listenPort,
                                                      int destinationPort) {
            return success(new ServicePort(protocol, listenPort, destinationPort));
        }
    }

    public static Result<LoadBalancerSpec> loadBalancerSpec(String name,
                                                            String algorithm,
                                                            List<ServicePort> servicePorts) {
        return success(new LoadBalancerSpec(name, algorithm, List.copyOf(servicePorts), Option.empty(), Map.of()));
    }
}
