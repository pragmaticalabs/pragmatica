package org.pragmatica.aether.environment.hetzner;

import org.pragmatica.cloud.hetzner.HetznerConfig;

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
                                       String userData) {

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
                                            List.copyOf(firewallIds), userData);
    }
}
