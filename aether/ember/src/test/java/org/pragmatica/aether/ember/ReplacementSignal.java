// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.util.List;

import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementPhase;
import org.pragmatica.consensus.NodeId;

/// #3740: the completion signal of a node replacement, read where the next `begin` reads it. `begin` is refused from the SERVING
/// leader's own replacement index; every node applies the leader's commits at slightly different moments, so a follower can show
/// `DONE` while the leader still holds the record live. A test that begins a second replacement after the first waits on the
/// leader's `status`, never on "some node shows DONE".
final class ReplacementSignal {
    private ReplacementSignal() {}

    /// True when the leader's own record for `victim` is `DONE`: the state `begin` on that leader checks.
    static boolean settledOnLeader(List<? extends ManageableNode> nodes, NodeId victim) {
        return nodes.stream()
                    .anyMatch(node -> isDone(node, victim));
    }

    private static boolean isDone(ManageableNode node, NodeId victim) {
        return node.nodeReplacementService()
                   .status(victim)
                   .filter(record -> record.phase() == NodeReplacementPhase.DONE)
                   .isPresent();
    }
}
