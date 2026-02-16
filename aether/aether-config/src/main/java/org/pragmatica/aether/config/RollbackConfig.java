package org.pragmatica.aether.config;

import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;

/// Configuration for automatic rollback on persistent slice failures.
///
///
/// Example aether.toml:
/// ```
/// [controller.rollback]
/// enabled = true
/// trigger_on_all_instances_failed = true
/// cooldown_seconds = 300
/// max_rollbacks = 2
/// ```
///
/// @param enabled Whether automatic rollback is enabled
/// @param triggerOnAllInstancesFailed Whether to trigger rollback when all instances fail
/// @param cooldownSeconds Minimum time between rollbacks for the same artifact
/// @param maxRollbacks Maximum consecutive rollbacks before requiring human intervention
public record RollbackConfig(boolean enabled,
                             boolean triggerOnAllInstancesFailed,
                             int cooldownSeconds,
                             int maxRollbacks) {
    /// Factory method following JBCT naming convention.
    public static Result<RollbackConfig> rollbackConfig(boolean enabled,
                                                        boolean triggerOnAllInstancesFailed,
                                                        int cooldownSeconds,
                                                        int maxRollbacks) {
        return success(new RollbackConfig(enabled, triggerOnAllInstancesFailed, cooldownSeconds, maxRollbacks));
    }

    private static final RollbackConfig ENABLED = rollbackConfig(true, true, 300, 2).unwrap();
    private static final RollbackConfig DISABLED = rollbackConfig(false, false, 0, 0).unwrap();

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
