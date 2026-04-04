package org.pragmatica.aether.config.cluster;

public record UpgradeSpec(UpgradeStrategy strategy) {
    public static UpgradeSpec upgradeSpec(UpgradeStrategy strategy) {
        return new UpgradeSpec(strategy);
    }

    public static UpgradeSpec defaultUpgradeSpec() {
        return new UpgradeSpec(UpgradeStrategy.ROLLING);
    }
}
