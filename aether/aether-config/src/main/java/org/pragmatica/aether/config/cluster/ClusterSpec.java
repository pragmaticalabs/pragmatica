package org.pragmatica.aether.config.cluster;
/// Cluster specification describing WHAT to provision.
///
/// @param name unique cluster identifier
/// @param version Aether version to deploy
/// @param core core node specification
/// @param workers worker pool specification
/// @param distribution node distribution configuration
/// @param autoHeal auto-healing configuration
/// @param upgrade upgrade strategy
public record ClusterSpec(String name,
                          String version,
                          CoreSpec core,
                          WorkerSpec workers,
                          DistributionConfig distribution,
                          AutoHealSpec autoHeal,
                          UpgradeSpec upgrade) {
    /// Factory method.
    public static ClusterSpec clusterSpec(String name,
                                          String version,
                                          CoreSpec core,
                                          WorkerSpec workers,
                                          DistributionConfig distribution,
                                          AutoHealSpec autoHeal,
                                          UpgradeSpec upgrade) {
        return new ClusterSpec(name, version, core, workers, distribution, autoHeal, upgrade);
    }
}
