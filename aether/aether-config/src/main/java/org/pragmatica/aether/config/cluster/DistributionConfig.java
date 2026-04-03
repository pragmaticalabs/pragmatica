package org.pragmatica.aether.config.cluster;

import java.util.List;


/// Distribution configuration for node placement across zones.
///
/// @param strategy distribution strategy (balanced or manual)
/// @param zones logical zone names for balanced distribution
public record DistributionConfig(DistributionStrategy strategy, List<String> zones) {
    public static DistributionConfig distributionConfig(DistributionStrategy strategy, List<String> zones) {
        return new DistributionConfig(strategy, List.copyOf(zones));
    }

    public static DistributionConfig defaultDistributionConfig() {
        return new DistributionConfig(DistributionStrategy.BALANCED, List.of());
    }
}
