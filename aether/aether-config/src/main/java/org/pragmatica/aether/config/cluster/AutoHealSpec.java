package org.pragmatica.aether.config.cluster;
/// Auto-healing configuration.
///
/// @param enabled whether auto-healing is enabled
/// @param retryInterval interval between heal attempts
/// @param startupCooldown cooldown before first heal check
public record AutoHealSpec(boolean enabled,
                           String retryInterval,
                           String startupCooldown) {
    /// Factory method.
    public static AutoHealSpec autoHealSpec(boolean enabled, String retryInterval, String startupCooldown) {
        return new AutoHealSpec(enabled, retryInterval, startupCooldown);
    }

    /// Default: enabled, 60s retry, 15s cooldown.
    public static AutoHealSpec defaultAutoHealSpec() {
        return new AutoHealSpec(true, "60s", "15s");
    }
}
