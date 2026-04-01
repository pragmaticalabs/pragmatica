package org.pragmatica.aether.config.cluster;
public record ClusterSpec( String name,
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
