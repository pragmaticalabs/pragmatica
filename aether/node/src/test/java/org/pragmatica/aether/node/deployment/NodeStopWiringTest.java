// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.deployment;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;

import static org.assertj.core.api.Assertions.assertThat;

/// #3754: the node's stop reaches the legacy node deployment FSM through the wiring (`DeploymentWiring.nodeStop` hands the manager's stop over, `stop()` runs
/// it), because `AetherNode` may not name the manager outside the wiring package (`WiringArchitectureTest`). Mutants: `stop()` does not run the handed
/// hook, `nodeStop` binds under FSM2 as well, `nodeStop` never stores the target: each goes red here.
class NodeStopWiringTest {
    private static final String POINT = "B13.ndm.Stop";

    @Test
    void underLegacy_theWiringsStopRunsTheStopTheManagerHandedOver_andRecordsItBound() {
        try (var harness = Fsm2Harness.production(DeploymentEngine.LEGACY)) {
            var stops = new AtomicInteger();

            harness.wiring.nodeStop().accept(stops::incrementAndGet);
            assertThat(stops).as("registering runs nothing").hasValue(0);

            harness.wiring.stop();

            assertThat(stops).as("the node's stop reached the legacy node FSM exactly once").hasValue(1);
            assertThat(harness.wiring.recorded()).contains(POINT + " -> legacy");
        }
    }

    @Test
    void underFsm2_theLegacyNodeStopIsADroppedBinding_neverRun() {
        try (var harness = Fsm2Harness.production(DeploymentEngine.FSM2)) {
            var stops = new AtomicInteger();

            harness.wiring.nodeStop().accept(stops::incrementAndGet);
            harness.wiring.stop();

            assertThat(stops).as("the legacy node FSM is unbound under FSM2: nothing to stop").hasValue(0);
            assertThat(harness.wiring.recorded()).contains(POINT + " -> dropped");
        }
    }
}
