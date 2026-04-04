package org.pragmatica.aether.environment.gcp;

import org.pragmatica.cloud.gcp.GcpConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;

/// Configuration for the GCP environment integration.
/// Contains GCP API credentials and default instance provisioning parameters.
public record GcpEnvironmentConfig(GcpConfig gcpConfig,
                                   String machineType,
                                   String sourceImage,
                                   String network,
                                   String subnetwork,
                                   String userData,
                                   Option<GcpNegConfig> networkEndpointGroup,
                                   Option<String> clusterName,
                                   Option<String> selfInstanceName,
                                   long discoveryPollIntervalMs,
                                   Option<String> certificateSecretPrefix) {
    private static final long DEFAULT_POLL_INTERVAL_MS = 30_000L;

    /// NEG (Network Endpoint Group) configuration for GCP load balancing.
    public record GcpNegConfig(String negName, int port) {
        /// Factory method for creating a GCP NEG configuration.
        public static Result<GcpNegConfig> gcpNegConfig(String negName, int port) {
            return success(new GcpNegConfig(negName, port));
        }
    }

    /// Factory method for creating a GCP environment configuration.
    public static Result<GcpEnvironmentConfig> gcpEnvironmentConfig(GcpConfig gcpConfig,
                                                                    String machineType,
                                                                    String sourceImage,
                                                                    String network,
                                                                    String subnetwork,
                                                                    String userData) {
        return success(new GcpEnvironmentConfig(gcpConfig,
                                                machineType,
                                                sourceImage,
                                                network,
                                                subnetwork,
                                                userData,
                                                Option.empty(),
                                                Option.empty(),
                                                Option.empty(),
                                                DEFAULT_POLL_INTERVAL_MS,
                                                Option.empty()));
    }

    /// Factory method for creating a GCP environment configuration with NEG.
    public static Result<GcpEnvironmentConfig> gcpEnvironmentConfig(GcpConfig gcpConfig,
                                                                    String machineType,
                                                                    String sourceImage,
                                                                    String network,
                                                                    String subnetwork,
                                                                    String userData,
                                                                    GcpNegConfig negConfig) {
        return success(new GcpEnvironmentConfig(gcpConfig,
                                                machineType,
                                                sourceImage,
                                                network,
                                                subnetwork,
                                                userData,
                                                some(negConfig),
                                                Option.empty(),
                                                Option.empty(),
                                                DEFAULT_POLL_INTERVAL_MS,
                                                Option.empty()));
    }

    /// Return a copy with discovery enabled for the specified cluster name.
    @SuppressWarnings("JBCT-VO-02") // Copy-with-change builder — direct constructor is intentional
    public GcpEnvironmentConfig withDiscovery(String clusterLabel) {
        return new GcpEnvironmentConfig(gcpConfig, machineType, sourceImage, network, subnetwork,
                                        userData, networkEndpointGroup, some(clusterLabel),
                                        selfInstanceName, discoveryPollIntervalMs, certificateSecretPrefix);
    }

    /// Return a copy with the self instance name set.
    @SuppressWarnings("JBCT-VO-02") // Copy-with-change builder — direct constructor is intentional
    public GcpEnvironmentConfig withSelfInstanceName(String instanceName) {
        return new GcpEnvironmentConfig(gcpConfig, machineType, sourceImage, network, subnetwork,
                                        userData, networkEndpointGroup, clusterName,
                                        some(instanceName), discoveryPollIntervalMs, certificateSecretPrefix);
    }

    /// Return a copy with the discovery poll interval set.
    @SuppressWarnings("JBCT-VO-02") // Copy-with-change builder — direct constructor is intentional
    public GcpEnvironmentConfig withDiscoveryPollInterval(long intervalMs) {
        return new GcpEnvironmentConfig(gcpConfig, machineType, sourceImage, network, subnetwork,
                                        userData, networkEndpointGroup, clusterName,
                                        selfInstanceName, intervalMs, certificateSecretPrefix);
    }

    /// Return a copy with certificate secret prefix set for cloud-backed mTLS.
    @SuppressWarnings("JBCT-VO-02") // Copy-with-change builder — direct constructor is intentional
    public GcpEnvironmentConfig withCertificateSecretPrefix(String prefix) {
        return new GcpEnvironmentConfig(gcpConfig, machineType, sourceImage, network, subnetwork,
                                        userData, networkEndpointGroup, clusterName,
                                        selfInstanceName, discoveryPollIntervalMs, some(prefix));
    }
}
