// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import org.pragmatica.aether.ember.EmberConfig.EmberConfigError.RenamedKey;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #2385: Forge's API port is read from `forge_port`. The old `dashboard_port` key has no alias (pre-GA) and is
/// refused at load with a typed error naming the new key: ignoring it would silently run Forge on the default port.
class EmberConfigForgePortTest {
    @Test
    void loadFromString_forgePort_isReadFromCluster() {
        assertThat(EmberConfig.loadFromString("[cluster]\nforge_port = 8988\n").unwrap().forgePort()).isEqualTo(8988);
    }

    @Test
    void loadFromString_forgePortAbsent_defaultsTo8888() {
        assertThat(EmberConfig.loadFromString("[cluster]\nnodes = 3\n").unwrap().forgePort()).isEqualTo(8888);
        assertThat(EmberConfig.DEFAULT_FORGE_PORT).isEqualTo(8888);
    }

    @Test
    void loadFromString_dashboardPort_isRefusedNamingForgePort() {
        EmberConfig.loadFromString("[cluster]\nnodes = 3\ndashboard_port = 8988\n")
                   .onSuccess(config -> fail("dashboard_port must refuse the load, loaded " + config))
                   .onFailure(cause -> assertThat(cause).isEqualTo(new RenamedKey("cluster", "dashboard_port", "forge_port")))
                   .onFailure(cause -> assertThat(cause.message()).contains("dashboard_port")
                                                                  .contains("forge_port"));
    }

    @Test
    void loadFromString_dashboardPortBesideForgePort_isStillRefused() {
        EmberConfig.loadFromString("[cluster]\nforge_port = 8988\ndashboard_port = 8988\n")
                   .onSuccess(config -> fail("a leftover dashboard_port must refuse the load, loaded " + config))
                   .onFailure(cause -> assertThat(cause).isInstanceOf(RenamedKey.class));
    }

    @Test
    void loadFromString_dashboardPortOutsideCluster_isNotTheRenamedKey() {
        assertThat(EmberConfig.loadFromString("[lb]\ndashboard_port = 8988\n").unwrap().forgePort()).isEqualTo(8888);
    }

    @Test
    void loadFromString_invalidForgePort_namesForgePort() {
        EmberConfig.loadFromString("[cluster]\nforge_port = 70000\n")
                   .onSuccess(config -> fail("forge_port = 70000 must refuse the load, loaded " + config))
                   .onFailure(cause -> assertThat(cause.message()).contains("forge_port"));
        EmberConfig.loadFromString("[cluster]\nmanagement_port = 5150\nforge_port = 5150\n")
                   .onSuccess(config -> fail("equal management_port and forge_port must refuse the load, loaded " + config))
                   .onFailure(cause -> assertThat(cause.message()).contains("forge_port"));
    }
}
