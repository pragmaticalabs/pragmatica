package org.pragmatica.aether.config;
/**
 * Configuration for automatic rollback on persistent slice failures.
 *
 * <p>Example aether.toml:
 * <pre>
 * [controller.rollback]
 * enabled = true
 * trigger_on_all_instances_failed = true
 * cooldown_seconds = 300
 * max_rollbacks = 2
 * </pre>
 *
 * @param enabled Whether automatic rollback is enabled
 * @param triggerOnAllInstancesFailed Whether to trigger rollback when all instances fail
 * @param cooldownSeconds Minimum time between rollbacks for the same artifact
 * @param maxRollbacks Maximum consecutive rollbacks before requiring human intervention
 */
public record RollbackConfig(boolean enabled,
                             boolean triggerOnAllInstancesFailed,
                             int cooldownSeconds,
                             int maxRollbacks) {
    private static final RollbackConfig DEFAULT = new RollbackConfig(true, true, 300, 2);

    /**
     * Default configuration with automatic rollback enabled.
     */
    public static RollbackConfig defaults() {
        return DEFAULT;
    }

    /**
     * Configuration with rollback disabled.
     */
    public static RollbackConfig disabled() {
        return new RollbackConfig(false, false, 0, 0);
    }

    /**
     * Factory method following JBCT naming convention.
     */
    public static RollbackConfig rollbackConfig(boolean enabled,
                                                boolean triggerOnAllInstancesFailed,
                                                int cooldownSeconds,
                                                int maxRollbacks) {
        return new RollbackConfig(enabled, triggerOnAllInstancesFailed, cooldownSeconds, maxRollbacks);
    }
}
