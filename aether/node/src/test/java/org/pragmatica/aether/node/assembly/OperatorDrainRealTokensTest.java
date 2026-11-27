// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.metrics.DrainCommandRegistry;
import org.pragmatica.aether.metrics.DrainSubject;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.BootTokens;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// #2315 round 1, F1: failure input (a), a drained node whose process restarts, earned in production and not through a token
/// that changes. The real `BootTokens` records the first token of a node id and never replaces it (`putIfAbsent`, no removal):
/// the restarted process is refused (CONFLICT) and its id retired, so it never becomes MEMBER under that id. What ends the
/// drain of the retired life is the into-DEAD edge of the real membership FSM, through `DrainAssembly.clearOperatorDrainOnDeath`.
///
/// This test is green at the head BY DESIGN: it names the production path that carries (a), so that deleting the mechanism a
/// mocked token alone reaches (the process pin's supersede branch, `clearSupersededProcess`) leaves (a) pinned.
class OperatorDrainRealTokensTest {
    private static final NodeId NODE = NodeId.nodeId("drained").unwrap();

    @Test
    void restartedProcess_isRefusedItsToken_andTheRetiredLifeEndsTheEntryAtDead() {
        var tokens = BootTokens.bootTokens(99L);
        var fsm = MembershipFsm.membershipFsm();
        var registry = DrainCommandRegistry.drainCommandRegistry();

        registry.bindSubject(DrainSubject.drainSubject(tokens::tokenOf,
                                                       node -> Option.option(fsm.memberIncarnations().get(node)).or(0L)));
        fsm.onTransition(record -> DrainAssembly.clearOperatorDrainOnDeath(registry, record));

        assertThat(tokens.admit(NODE, 1L).admitted()).as("the first process is admitted").isTrue();
        fsm.onSwimHealthy(NODE, 1L);
        registry.requestDrain(NODE);
        fsm.onDrainRequested(NODE);

        var restarted = tokens.admit(NODE, 2L);

        assertThat(restarted).as("a new token for a known id is refused and the id retired").isEqualTo(BootTokens.Admission.CONFLICT);
        assertThat(tokens.tokenOf(NODE)).as("the recorded token never changes").isEqualTo(1L);
        assertThat(registry.isDrainRequested(NODE)).as("the retired life's drain stays until that life ends").isTrue();

        fsm.onSwimDeparted(NODE, 1L);

        assertThat(fsm.memberStates()).as("instrument: the retired life reached DEAD").containsEntry(NODE, "Dead");
        assertThat(registry.drainTargets()).as("no entry remains for the id").isEmpty();
    }
}
