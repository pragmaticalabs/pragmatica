package org.pragmatica.aether.config;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Configuration for automatic rollback on persistent slice failures.
///
///
/// Example aether.toml:
/// ```
/// [controller.rollback]
/// enabled = true
/// trigger_on_all_instances_failed = true
/// cooldown = "5m"
/// max_rollbacks = 2
/// ```
///
/// @param enabled Whether automatic rollback is enabled
/// @param triggerOnAllInstancesFailed Whether to trigger rollback when all instances fail
/// @param cooldown Minimum time between rollbacks for the same artifact
/// @param maxRollbacks Maximum consecutive rollbacks before requiring human intervention
public record RollbackConfig( boolean enabled,
                              boolean triggerOnAllInstancesFailed,
                              TimeSpan cooldown,
                              int maxRollbacks) {
    /// Factory method following JBCT naming convention.
    public static Result<RollbackConfig> rollbackConfig(boolean enabled,
                                                        boolean triggerOnAllInstancesFailed,
                                                        TimeSpan cooldown,
                                                        int maxRollbacks) {
        return success(new RollbackConfig(enabled, triggerOnAllInstancesFailed, cooldown, maxRollbacks));
    }

    private static final RollbackConfig ENABLED = rollbackConfig(true, true, timeSpan(5).minutes(), 2).unwrap();
    private static final RollbackConfig DISABLED = rollbackConfig(false, false, timeSpan(0).millis(), 0).unwrap();

    /// Default configuration with automatic rollback enabled.
    public static RollbackConfig rollbackConfig() {
        return ENABLED;
    }

    /// Configuration based on enabled flag.
    public static RollbackConfig rollbackConfig(boolean enabled) {
        return enabled
               ? ENABLED
               : DISABLED;
    }
}
