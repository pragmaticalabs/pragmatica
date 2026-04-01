package org.pragmatica.aether.config;

import org.pragmatica.lang.io.TimeSpan;

/// Configuration for DHT replication behavior.
///
/// @param cooldownDelay  Delay after node startup before upgrading to target RF
/// @param cooldownRate   Max entries/sec during replication warmup
/// @param targetRf       Target replication factor (0 = full replication)
public record DhtReplicationConfig( TimeSpan cooldownDelay,
                                    int cooldownRate,
                                    int targetRf) {
    public static final TimeSpan DEFAULT_COOLDOWN_DELAY = TimeSpan.timeSpan(10).seconds();
    public static final int DEFAULT_COOLDOWN_RATE = 10_000;
    public static final int DEFAULT_TARGET_RF = 3;

    /// Create config with specified values.
    public static DhtReplicationConfig dhtReplicationConfig(TimeSpan cooldownDelay,
                                                            int cooldownRate,
                                                            int targetRf) {
        return new DhtReplicationConfig(cooldownDelay, cooldownRate, targetRf);
    }

    /// Create config with all defaults.
    public static DhtReplicationConfig dhtReplicationConfig() {
        return new DhtReplicationConfig(DEFAULT_COOLDOWN_DELAY, DEFAULT_COOLDOWN_RATE, DEFAULT_TARGET_RF);
    }
}
