// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.deployment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.cluster.TerminationRetirement;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;

/// W-28 (b), (c), MT-14q, MT-14r (phase-3 design 8.3 B18, B19; 12 item 4b): the CTM's confirmed reap and the operator `--force` clear retire
/// the node's directive and departure record under FSM2 only. Red when: either gate hands LEGACY the retirement (LEGACY deletes the directive
/// at `NodeRemoved`, so a second delete would race it), withholds it from FSM2, or records a different binding than it applies.
class RetirementGateTest {
    private static final TerminationRetirement RETIREMENT = _ -> Promise.unitPromise();

    private static final Path AETHER_NODE = Path.of("src", "main", "java", "org", "pragmatica", "aether", "node", "AetherNode.java");

    /// T5: the node retires through the production retirement (`TerminationRetirement.production`: the production retry policy and the node's
    /// stop signal), never a hand-built one. Red when `AetherNode` builds its retirement any other way (a one-attempt retry, no stop signal).
    @Test
    void theNode_retiresThroughTheProductionRetirement() throws IOException {
        var source = Files.readString(AETHER_NODE, StandardCharsets.UTF_8);

        assertThat(source.split("TerminationRetirement\\.production\\(", -1)).as("one production retirement").hasSize(2);
        assertThat(source).as("no hand-built retirement").doesNotContain("TerminationRetirement.directiveAndRecord(");
        assertThat(source.replaceAll("\\s+", "")).as("with the node's stop signal")
                                               .contains("TerminationRetirement.production(kvStore,clusterCommandApplier,periodicTasks::isCancelled)");
    }

    @Test
    void underLegacy_neitherAuthorityRetires_andBothAreRecordedOff() {
        try (var harness = new Fsm2Harness(DeploymentEngine.LEGACY, false)) {
            assertThat(harness.wiring.reaperRetirement(RETIREMENT)).isSameAs(TerminationRetirement.NONE);
            assertThat(harness.wiring.forceClearRetirement(RETIREMENT)).isSameAs(TerminationRetirement.NONE);
            assertThat(harness.wiring.recorded()).contains("B18.CtmReaperDirectiveDelete -> off", "B19.ForceClearDirectiveDelete -> off");
        }
    }

    @Test
    void underFsm2_bothAuthoritiesRetire_andBothAreRecordedOn() {
        try (var harness = new Fsm2Harness(DeploymentEngine.FSM2, false)) {
            assertThat(harness.wiring.reaperRetirement(RETIREMENT)).isSameAs(RETIREMENT);
            assertThat(harness.wiring.forceClearRetirement(RETIREMENT)).isSameAs(RETIREMENT);
            assertThat(harness.wiring.recorded()).contains("B18.CtmReaperDirectiveDelete -> on", "B19.ForceClearDirectiveDelete -> on");
        }
    }
}
