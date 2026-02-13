package org.pragmatica.aether.environment;

import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Configuration for cluster auto-healing behavior.
///
/// @param retryInterval    interval between provisioning attempts when cluster is below target size
/// @param startupCooldown  delay before first auto-heal check during initial cluster formation,
///                         allowing all nodes time to join before provisioning replacements
public record AutoHealConfig(TimeSpan retryInterval, TimeSpan startupCooldown) {
    public static final AutoHealConfig DEFAULT =
        new AutoHealConfig(timeSpan(10).seconds(), timeSpan(15).seconds());
}
