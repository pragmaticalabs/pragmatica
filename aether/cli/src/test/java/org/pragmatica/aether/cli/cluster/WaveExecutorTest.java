// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.cluster.NodeRole;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;


class WaveExecutorTest {

    // #442 v2b — the scale/reprovision path previously passed an empty tags map to
    // NodeGroupConfig, so CloudProviderSupport.toContext read no cluster name and the VM's
    // aether-cluster label fell back to the provider config / env / "unknown". This asserts the
    // wiring at the point the fix changed: the real cluster name reaches the group tags.
    @Test
    void provisionTags_carriesRealClusterSourceAndRole() {
        var tags = WaveExecutor.provisionTags("prod-cluster", sourceNameOrDefault("eu-1"), NodeRole.CORE);

        assertEquals("prod-cluster", tags.get("aether-cluster"));
        assertEquals("eu-1", tags.get("aether-source"));
        assertEquals("core", tags.get("aether-role"));
    }
}
