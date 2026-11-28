// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.deployment.membership.fsm.MembershipTransitionRecord;
import org.pragmatica.aether.metrics.DrainCommandRegistry;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.BootTokens;

/// Test harness: drives a REAL [MembershipFsm] the way production feeds it. A peer's SWIM observation reaches the FSM only after
/// the real [BootTokens] registry admitted the sender's boot token (the transport's admission gate precedes the routing of the
/// observation), and every FSM edge reaches the drain registry through `DrainAssembly.clearOperatorDrainOnDeath`, the one
/// `onTransition` listener of the assembly. The edges the FSM emitted are recorded in order, so a test can name which edge ended
/// a drain and which edges a process never produced.
final class MembershipSequence {
    private final BootTokens tokens;
    private final MembershipFsm fsm;
    private final List<String> edges = new CopyOnWriteArrayList<>();

    private MembershipSequence(BootTokens tokens, MembershipFsm fsm, DrainCommandRegistry registry) {
        this.tokens = tokens;
        this.fsm = fsm;
        fsm.onTransition(record -> onEdge(registry, record));
    }

    static MembershipSequence membershipSequence(BootTokens tokens, MembershipFsm fsm, DrainCommandRegistry registry) {
        return new MembershipSequence(tokens, fsm, registry);
    }

    private void onEdge(DrainCommandRegistry registry, MembershipTransitionRecord record) {
        edges.add(record.nodeId().id() + ":" + record.fromState() + ">" + record.toState());
        DrainAssembly.clearOperatorDrainOnDeath(registry, record);
    }

    /// A HEALTHY observation of `node` from the process holding `token`. Returns whether the token registry admitted the process
    /// (and so whether the FSM saw the observation).
    boolean healthy(NodeId node, long token, long incarnation) {
        var admitted = tokens.admit(node, token).admitted();

        if (admitted) {
            fsm.onSwimHealthy(node, incarnation);
        }
        return admitted;
    }

    void departed(NodeId node, long incarnation) {
        fsm.onSwimDeparted(node, incarnation);
    }

    String state(NodeId node) {
        return fsm.memberStates().get(node);
    }

    /// Every edge the FSM emitted so far, as `node:from>to`, oldest first.
    List<String> edges() {
        return List.copyOf(edges);
    }
}
