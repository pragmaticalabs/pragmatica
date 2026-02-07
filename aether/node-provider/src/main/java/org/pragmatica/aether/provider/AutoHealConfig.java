package org.pragmatica.aether.provider;

import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/**
 * Configuration for cluster auto-healing retry behavior.
 *
 * @param retryInterval interval between provisioning attempts when cluster is below target size
 */
public record AutoHealConfig(TimeSpan retryInterval) {
    public static final AutoHealConfig DEFAULT =
        new AutoHealConfig(timeSpan(10).seconds());
}
