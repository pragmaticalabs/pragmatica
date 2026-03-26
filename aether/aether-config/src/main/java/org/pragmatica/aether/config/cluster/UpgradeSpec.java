package org.pragmatica.aether.config.cluster;
/// Upgrade strategy specification.
///
/// @param strategy upgrade strategy (rolling or blue-green)
public record UpgradeSpec(UpgradeStrategy strategy) {
    /// Factory method.
    public static UpgradeSpec upgradeSpec(UpgradeStrategy strategy) {
        return new UpgradeSpec(strategy);
    }

    /// Default: rolling.
    public static UpgradeSpec defaultUpgradeSpec() {
        return new UpgradeSpec(UpgradeStrategy.ROLLING);
    }
}
