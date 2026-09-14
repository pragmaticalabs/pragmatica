// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether;

import org.pragmatica.aether.config.ConfigLoader;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #675: `[timeouts.scaling] auto_heal_startup_cooldown` parsed into `TimeoutsConfig` and stopped
/// there — `Main.resolveAutoHeal` built the runtime `AutoHealConfig` from `DEFAULT` plus
/// `[cluster] max_nodes` only, so the one auto-heal timing the runtime honours (the formation-check
/// delay in `ClusterTopologyManagerRecord.activateWithFormation`) was not operator-tunable.
class MainAutoHealResolutionTest {
    private static final String MINIMAL_CLUSTER = """
        [cluster]
        environment = "docker"
        nodes = 3
        """;

    @Test
    void startupCooldown_reachesTheRuntimeConfig() {
        var toml = MINIMAL_CLUSTER + """

            [timeouts.scaling]
            auto_heal_startup_cooldown = "42s"
            """;

        ConfigLoader.loadFromString(toml)
                    .onFailure(cause -> fail(cause.message()))
                    .onSuccess(config -> assertThat(Main.resolveAutoHeal(Option.some(config)).startupCooldown())
                        .as("the declared cooldown must reach AutoHealConfig, not stop at the parse boundary")
                        .isEqualTo(timeSpan(42).seconds()));
    }

    @Test
    void startupCooldown_defaultsWhenAbsent_andMaxNodesStillCarried() {
        ConfigLoader.loadFromString(MINIMAL_CLUSTER + "max_nodes = 7\n")
                    .onFailure(cause -> fail(cause.message()))
                    .onSuccess(config -> {
                        var resolved = Main.resolveAutoHeal(Option.some(config));

                        assertThat(resolved.startupCooldown()).isEqualTo(AutoHealConfig.DEFAULT.startupCooldown());
                        assertThat(resolved.maxNodes()).isEqualTo(Option.some(7));
                    });
    }
}
