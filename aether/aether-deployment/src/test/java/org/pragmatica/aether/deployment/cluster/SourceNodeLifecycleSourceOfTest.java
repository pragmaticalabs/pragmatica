// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.SourceName;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;

/// #2062: a retirement reap starts from a node id alone, so the source lifecycle must name a source for every node. A node with an authoritative
/// placement keeps it; a node with none (a bootstrap node of the local harness, blank descriptor source) is reaped in the default source, never
/// left unnamed (which made the reap fall back to a refusal that was logged and dropped).
class SourceNodeLifecycleSourceOfTest {
    private static final NodeId NODE = new NodeId("core-1");
    private static final SourceName WEST = SourceName.sourceName("west").unwrap();

    private static NodeLifecycleManager lifecycle(java.util.function.Function<NodeId, Result<SourceName>> sourceForNode) {
        var registry = SourceComputeRegistry.sourceComputeRegistry(() -> Option.<ClusterConfigValue> none(),
                                                                   config -> Result.success(EnvironmentIntegration.withCompute(null)));

        return NodeLifecycleManager.nodeLifecycleManager(registry, sourceForNode, Option.none(), Option.none());
    }

    @Test
    void aNodeWithAnAuthoritativeSource_keepsIt() {
        assertThat(lifecycle(_ -> Result.success(WEST)).sourceOf(NODE)).isEqualTo(Option.some(WEST));
    }

    @Test
    void aNodeWithNoAuthoritativeSource_isReapedInTheDefaultSource() {
        assertThat(lifecycle(_ -> EnvironmentError.operationNotSupported("No authoritative compute source").<SourceName> result()).sourceOf(NODE))
            .isEqualTo(Option.some(SourceName.DEFAULT));
    }
}
