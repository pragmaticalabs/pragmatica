package org.pragmatica.aether.config.cluster;
public record UpgradeSpec( UpgradeStrategy strategy) {
    /// Factory method.
    public static UpgradeSpec upgradeSpec(UpgradeStrategy strategy) {
        return new UpgradeSpec(strategy);
    }

    /// Default: rolling.
    public static UpgradeSpec defaultUpgradeSpec() {
        return new UpgradeSpec(UpgradeStrategy.ROLLING);
    }
}
