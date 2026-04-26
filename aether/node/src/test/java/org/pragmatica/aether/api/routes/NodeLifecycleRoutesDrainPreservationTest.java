// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProvisioningSource;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// Theme E #189: operator-initiated lifecycle transitions (drain/activate/shutdown)
/// must forward `host`/`port`/`observedCoreEpoch`/`provisioningSource` from the
/// prior `NodeLifecycleValue` to satisfy the single-writer SSOT invariant.
class NodeLifecycleRoutesDrainPreservationTest {
    @Test
    void operatorDrain_preservesHostPortEpoch() {
        var prior = NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY,
                                                          1234L,
                                                          "operator-host.example",
                                                          7777,
                                                          Epoch.epoch(11, 0),
                                                          HlcTimestamp.ZERO,
                                                          ProvisioningSource.MANUAL);

        var drained = NodeLifecycleRoutes.buildLifecycleAtom(Option.some(prior), NodeLifecycleState.DRAINING);

        assertThat(drained.state()).isEqualTo(NodeLifecycleState.DRAINING);
        assertThat(drained.host()).isEqualTo("operator-host.example");
        assertThat(drained.port()).isEqualTo(7777);
        assertThat(drained.observedCoreEpoch()).isEqualTo(Epoch.epoch(11, 0));
        assertThat(drained.provisioningSource()).isEqualTo(ProvisioningSource.MANUAL);
    }

    @Test
    void operatorDrain_noPriorValue_writesDefaults() {
        var drained = NodeLifecycleRoutes.buildLifecycleAtom(Option.none(), NodeLifecycleState.DRAINING);

        assertThat(drained.state()).isEqualTo(NodeLifecycleState.DRAINING);
        assertThat(drained.host()).isEqualTo("");
        assertThat(drained.port()).isEqualTo(0);
        assertThat(drained.observedCoreEpoch()).isEqualTo(Epoch.ZERO);
    }
}
