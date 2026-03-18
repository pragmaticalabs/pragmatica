package org.pragmatica.aether.environment.hetzner;

import org.pragmatica.cloud.hetzner.HetznerConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.List;

import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;

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
                                       Option<HetznerLbConfig> loadBalancer,
                                       Option<String> clusterName,
                                       Option<Long> selfServerId,
                                       long discoveryPollIntervalMs) {
    private static final long DEFAULT_POLL_INTERVAL_MS = 30_000L;

    /// Load balancer configuration for Hetzner environment.
    public record HetznerLbConfig(long loadBalancerId, int destinationPort) {
        /// Factory method for creating a Hetzner load balancer configuration.
        public static Result<HetznerLbConfig> hetznerLbConfig(long loadBalancerId, int destinationPort) {
            return success(new HetznerLbConfig(loadBalancerId, destinationPort));
        }
    }

    /// Factory method for creating a Hetzner environment configuration.
    public static Result<HetznerEnvironmentConfig> hetznerEnvironmentConfig(HetznerConfig hetznerConfig,
                                                                            String serverType,
                                                                            String image,
                                                                            String region,
                                                                            List<Long> sshKeyIds,
                                                                            List<Long> networkIds,
                                                                            List<Long> firewallIds,
                                                                            String userData) {
        return success(new HetznerEnvironmentConfig(hetznerConfig,
                                                    serverType,
                                                    image,
                                                    region,
                                                    List.copyOf(sshKeyIds),
                                                    List.copyOf(networkIds),
                                                    List.copyOf(firewallIds),
                                                    userData,
                                                    Option.empty(),
                                                    Option.empty(),
                                                    Option.empty(),
                                                    DEFAULT_POLL_INTERVAL_MS));
    }

    /// Factory method for creating a Hetzner environment configuration with load balancer.
    public static Result<HetznerEnvironmentConfig> hetznerEnvironmentConfig(HetznerConfig hetznerConfig,
                                                                            String serverType,
                                                                            String image,
                                                                            String region,
                                                                            List<Long> sshKeyIds,
                                                                            List<Long> networkIds,
                                                                            List<Long> firewallIds,
                                                                            String userData,
                                                                            HetznerLbConfig loadBalancer) {
        return success(new HetznerEnvironmentConfig(hetznerConfig,
                                                    serverType,
                                                    image,
                                                    region,
                                                    List.copyOf(sshKeyIds),
                                                    List.copyOf(networkIds),
                                                    List.copyOf(firewallIds),
                                                    userData,
                                                    some(loadBalancer),
                                                    Option.empty(),
                                                    Option.empty(),
                                                    DEFAULT_POLL_INTERVAL_MS));
    }

    /// Return a copy with discovery enabled for the specified cluster name.
    @SuppressWarnings("JBCT-VO-02") // Copy-with-change builder — direct constructor is intentional
    public HetznerEnvironmentConfig withDiscovery(String clusterLabel) {
        return new HetznerEnvironmentConfig(hetznerConfig,
                                            serverType,
                                            image,
                                            region,
                                            sshKeyIds,
                                            networkIds,
                                            firewallIds,
                                            userData,
                                            loadBalancer,
                                            some(clusterLabel),
                                            selfServerId,
                                            discoveryPollIntervalMs);
    }

    /// Return a copy with the self server ID set.
    @SuppressWarnings("JBCT-VO-02") // Copy-with-change builder — direct constructor is intentional
    public HetznerEnvironmentConfig withSelfServerId(long serverId) {
        return new HetznerEnvironmentConfig(hetznerConfig,
                                            serverType,
                                            image,
                                            region,
                                            sshKeyIds,
                                            networkIds,
                                            firewallIds,
                                            userData,
                                            loadBalancer,
                                            clusterName,
                                            some(serverId),
                                            discoveryPollIntervalMs);
    }

    /// Return a copy with the discovery poll interval set.
    @SuppressWarnings("JBCT-VO-02") // Copy-with-change builder — direct constructor is intentional
    public HetznerEnvironmentConfig withDiscoveryPollInterval(long intervalMs) {
        return new HetznerEnvironmentConfig(hetznerConfig,
                                            serverType,
                                            image,
                                            region,
                                            sshKeyIds,
                                            networkIds,
                                            firewallIds,
                                            userData,
                                            loadBalancer,
                                            clusterName,
                                            selfServerId,
                                            intervalMs);
    }
}
