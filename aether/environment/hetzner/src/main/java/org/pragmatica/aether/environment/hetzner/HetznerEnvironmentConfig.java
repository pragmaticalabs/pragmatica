package org.pragmatica.aether.environment.hetzner;

import org.pragmatica.cloud.hetzner.HetznerConfig;
import org.pragmatica.lang.Option;

import java.util.List;

/// Configuration for the Hetzner environment integration.
/// Contains Hetzner API credentials and default server provisioning parameters.
public record HetznerEnvironmentConfig(HetznerConfig hetznerConfig,
                                       String serverType,
                                       String image,
                                       String region,
                                       List<Long> sshKeyIds,
                                       List<Long> networkIds,
                                       List<Long> firewallIds,
                                       String userData,
                                       Option<HetznerLbConfig> loadBalancer) {

    /// Load balancer configuration for Hetzner environment.
    public record HetznerLbConfig(long loadBalancerId, int destinationPort) {

        /// Factory method for creating a Hetzner load balancer configuration.
        public static HetznerLbConfig hetznerLbConfig(long loadBalancerId, int destinationPort) {
            return new HetznerLbConfig(loadBalancerId, destinationPort);
        }
    }

    /// Factory method for creating a Hetzner environment configuration.
    public static HetznerEnvironmentConfig hetznerEnvironmentConfig(HetznerConfig hetznerConfig,
                                                                     String serverType,
                                                                     String image,
                                                                     String region,
                                                                     List<Long> sshKeyIds,
                                                                     List<Long> networkIds,
                                                                     List<Long> firewallIds,
                                                                     String userData) {
        return new HetznerEnvironmentConfig(hetznerConfig, serverType, image, region,
                                            List.copyOf(sshKeyIds), List.copyOf(networkIds),
                                            List.copyOf(firewallIds), userData, Option.empty());
    }

    /// Factory method for creating a Hetzner environment configuration with load balancer.
    public static HetznerEnvironmentConfig hetznerEnvironmentConfig(HetznerConfig hetznerConfig,
                                                                     String serverType,
                                                                     String image,
                                                                     String region,
                                                                     List<Long> sshKeyIds,
                                                                     List<Long> networkIds,
                                                                     List<Long> firewallIds,
                                                                     String userData,
                                                                     HetznerLbConfig loadBalancer) {
        return new HetznerEnvironmentConfig(hetznerConfig, serverType, image, region,
                                            List.copyOf(sshKeyIds), List.copyOf(networkIds),
                                            List.copyOf(firewallIds), userData, Option.some(loadBalancer));
    }
}
