package org.pragmatica.aether.config.cluster;

public record AutoHealSpec(boolean enabled, String retryInterval, String startupCooldown) {
    public static AutoHealSpec autoHealSpec(boolean enabled, String retryInterval, String startupCooldown) {
        return new AutoHealSpec(enabled, retryInterval, startupCooldown);
    }

    public static AutoHealSpec defaultAutoHealSpec() {
        return new AutoHealSpec(true, "60s", "15s");
    }
}
