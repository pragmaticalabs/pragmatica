package org.pragmatica.aether.environment.azure;

import org.pragmatica.cloud.azure.AzureConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;

/// Configuration for the Azure environment integration.
/// Contains Azure API credentials and default VM provisioning parameters.
public record AzureEnvironmentConfig(AzureConfig azureConfig,
                                     String vmSize,
                                     String image,
                                     String adminUsername,
                                     String sshPublicKey,
                                     String vnetSubnetId,
                                     String userData,
                                     Option<AzureLbConfig> loadBalancer,
                                     Option<String> clusterName,
                                     Option<String> selfVmName,
                                     long discoveryPollIntervalMs,
                                     Option<String> certificateSecretPrefix) {
    private static final long DEFAULT_POLL_INTERVAL_MS = 30_000L;

    /// Load balancer configuration for Azure environment.
    public record AzureLbConfig(String loadBalancerName, String backendPoolName, String vnetId) {
        /// Factory method for creating an Azure load balancer configuration.
        public static Result<AzureLbConfig> azureLbConfig(String loadBalancerName,
                                                          String backendPoolName,
                                                          String vnetId) {
            return success(new AzureLbConfig(loadBalancerName, backendPoolName, vnetId));
        }
    }

    /// Factory method for creating an Azure environment configuration.
    public static Result<AzureEnvironmentConfig> azureEnvironmentConfig(AzureConfig azureConfig,
                                                                        String vmSize,
                                                                        String image,
                                                                        String adminUsername,
                                                                        String sshPublicKey,
                                                                        String vnetSubnetId,
                                                                        String userData) {
        return success(new AzureEnvironmentConfig(azureConfig,
                                                  vmSize,
                                                  image,
                                                  adminUsername,
                                                  sshPublicKey,
                                                  vnetSubnetId,
                                                  userData,
                                                  Option.empty(),
                                                  Option.empty(),
                                                  Option.empty(),
                                                  DEFAULT_POLL_INTERVAL_MS,
                                                  Option.empty()));
    }

    /// Factory method for creating an Azure environment configuration with load balancer.
    public static Result<AzureEnvironmentConfig> azureEnvironmentConfig(AzureConfig azureConfig,
                                                                        String vmSize,
                                                                        String image,
                                                                        String adminUsername,
                                                                        String sshPublicKey,
                                                                        String vnetSubnetId,
                                                                        String userData,
                                                                        AzureLbConfig loadBalancer) {
        return success(new AzureEnvironmentConfig(azureConfig,
                                                  vmSize,
                                                  image,
                                                  adminUsername,
                                                  sshPublicKey,
                                                  vnetSubnetId,
                                                  userData,
                                                  some(loadBalancer),
                                                  Option.empty(),
                                                  Option.empty(),
                                                  DEFAULT_POLL_INTERVAL_MS,
                                                  Option.empty()));
    }

    /// Return a copy with discovery enabled for the specified cluster name.
    @SuppressWarnings("JBCT-VO-02") // Copy-with-change builder -- direct constructor is intentional
    public AzureEnvironmentConfig withDiscovery(String clusterLabel) {
        return new AzureEnvironmentConfig(azureConfig, vmSize, image, adminUsername, sshPublicKey,
                                          vnetSubnetId, userData, loadBalancer, some(clusterLabel),
                                          selfVmName, discoveryPollIntervalMs, certificateSecretPrefix);
    }

    /// Return a copy with the self VM name set.
    @SuppressWarnings("JBCT-VO-02") // Copy-with-change builder -- direct constructor is intentional
    public AzureEnvironmentConfig withSelfVmName(String vmName) {
        return new AzureEnvironmentConfig(azureConfig, vmSize, image, adminUsername, sshPublicKey,
                                          vnetSubnetId, userData, loadBalancer, clusterName,
                                          some(vmName), discoveryPollIntervalMs, certificateSecretPrefix);
    }

    /// Return a copy with the discovery poll interval set.
    @SuppressWarnings("JBCT-VO-02") // Copy-with-change builder -- direct constructor is intentional
    public AzureEnvironmentConfig withDiscoveryPollInterval(long intervalMs) {
        return new AzureEnvironmentConfig(azureConfig, vmSize, image, adminUsername, sshPublicKey,
                                          vnetSubnetId, userData, loadBalancer, clusterName,
                                          selfVmName, intervalMs, certificateSecretPrefix);
    }

    /// Return a copy with certificate secret prefix set for cloud-backed mTLS.
    @SuppressWarnings("JBCT-VO-02") // Copy-with-change builder -- direct constructor is intentional
    public AzureEnvironmentConfig withCertificateSecretPrefix(String prefix) {
        return new AzureEnvironmentConfig(azureConfig, vmSize, image, adminUsername, sshPublicKey,
                                          vnetSubnetId, userData, loadBalancer, clusterName,
                                          selfVmName, discoveryPollIntervalMs, some(prefix));
    }
}
