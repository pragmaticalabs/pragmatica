// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.metrics.DrainCommandRegistry;
import org.pragmatica.aether.metrics.DrainSubject;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.BootTokens;

import static org.assertj.core.api.Assertions.assertThat;

/// #2315 round 1, F3: a drain entry is identified by the node life AND its requester (the operator, or the topology manager).
/// An operator drain and a manager drain of the same life are two entries, and a clear names one drain and ends only that
/// one. Run against the production token registry (`BootTokens.bootTokens`), where a node id never changes its token, so the
/// two requesters always drain the SAME process. Observable through the registry only: presence of the node as a drain target.
class TopologyDrainsRequesterTest {
    private static final NodeId NODE = NodeId.nodeId("drained").unwrap();

    private final BootTokens tokens = BootTokens.bootTokens(99L);
    private final DrainCommandRegistry registry = DrainCommandRegistry.drainCommandRegistry();
    private final TopologyDrains drains = TopologyDrains.topologyDrains(registry);

    private void admitTheNode() {
        tokens.admit(NODE, 1L);
        registry.bindSubject(DrainSubject.drainSubject(tokens::tokenOf, _ -> 0L));
    }

    /// Red when: the manager's clear ends the operator's drain of the same process, because both share one DrainId.
    @Test
    void operatorDrain_requestedAfterTheManagersDrain_survivesTheManagersClear() {
        admitTheNode();

        drains.request(NODE);
        var operators = registry.requestDrain(NODE).unwrap();
        drains.clear(NODE);

        assertThat(registry.isDrainRequested(NODE)).as("the manager's clear ended the operator's drain").isTrue();

        registry.clearDrain(operators);

        assertThat(registry.isDrainRequested(NODE)).as("control: the operator's own clear ends it").isFalse();
    }

    /// Red when: the manager's request, made while an operator's drain is pending, joins the operator's id, so the manager's
    /// clear (grace-terminate, cancel, deactivate) ends a drain the operator has not withdrawn.
    @Test
    void operatorDrain_requestedBeforeTheManagersDrain_survivesTheManagersClear() {
        admitTheNode();

        var operators = registry.requestDrain(NODE).unwrap();
        drains.request(NODE);
        drains.clear(NODE);

        assertThat(registry.isDrainRequested(NODE)).as("the manager's clear ended the operator's drain").isTrue();

        registry.clearDrain(operators);

        assertThat(registry.isDrainRequested(NODE)).as("control: the operator's own clear ends it").isFalse();
    }

    /// Red when: the operator's clear, naming the operator's drain, ends the manager's drain of the same process.
    @Test
    void managersDrain_survivesTheOperatorsClearOfItsOwnDrain() {
        admitTheNode();

        drains.request(NODE);
        var operators = registry.requestDrain(NODE).unwrap();
        registry.clearDrain(operators);

        assertThat(registry.isDrainRequested(NODE)).as("the operator's clear ended the manager's drain").isTrue();

        drains.clear(NODE);

        assertThat(registry.isDrainRequested(NODE)).as("control: the manager's own clear ends it").isFalse();
    }
}
