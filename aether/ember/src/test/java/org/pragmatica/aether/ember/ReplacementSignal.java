// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.util.ArrayList;
import java.util.List;

import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

/// #3740: the completion signal of a node replacement, read where the next `begin` reads it. `begin` is refused from the SERVING
/// leader's own replacement index; every node applies the leader's commits at slightly different moments, so a follower can show
/// `DONE` while the leader still holds the record live. A test that begins a second replacement after the first waits on the
/// leader's `status`, never on "some node shows DONE".
///
/// #3799: this class is the ONLY reader of `nodeReplacementService().status(...)` in the Ember tests, and `settledOnLeader` is the only
/// DONE wait it offers. A test that needs a view that survives the leader being the victim or being killed uses
/// `newestRecordOnAnyNode`, which names what it is; `ReplacementSignalFenceTest` fails the build for a read of `status` anywhere else.
final class ReplacementSignal {
    private ReplacementSignal() {}

    /// True when the leader's own record for `victim` is `DONE`: the state `begin` on that leader checks.
    static boolean settledOnLeader(List<? extends ManageableNode> nodes, NodeId victim) {
        return nodes.stream()
                    .filter(ManageableNode::isLeader)
                    .anyMatch(node -> isDone(node, victim));
    }

    /// The newest committed record (highest epoch) for `victim` that any of `nodes` holds, or none. This is NOT a signal that a
    /// following `begin` will be accepted: nodes apply the leader's commits at slightly different moments, so the newest record may
    /// be ahead of the leader's own. It exists for the observers that cannot read the leader: the victim may BE the leader, or be
    /// killed mid-run and still hold its last, older view, so the highest epoch wins. Use it for terminal-state waits and for
    /// assertions after one; never to gate a `begin` (use `settledOnLeader`), and never as a DONE wait.
    static Option<NodeReplacementValue> newestRecordOnAnyNode(List<? extends ManageableNode> nodes, NodeId victim) {
        NodeReplacementValue newest = null;

        for (var node : new ArrayList<>(nodes)) {
            var found = node.nodeReplacementService()
                            .status(victim)
                            .or((NodeReplacementValue) null);

            if (found != null && (newest == null || found.epoch() > newest.epoch())) {
                newest = found;
            }
        }

        return Option.option(newest);
    }

    private static boolean isDone(ManageableNode node, NodeId victim) {
        return node.nodeReplacementService()
                   .status(victim)
                   .filter(record -> record.phase() == NodeReplacementPhase.DONE)
                   .isPresent();
    }
}
