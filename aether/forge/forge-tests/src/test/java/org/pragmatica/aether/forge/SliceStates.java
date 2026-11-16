// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.util.ArrayList;
import java.util.List;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;


/// Engine-neutral reader of a node's slice state for forge tests (deployment state machine spec 1.6.1).
///
/// The engine writes only its own row family: `NodeArtifactKey` under LEGACY, `SliceObservedKey` under FSM2. A test that reads
/// one family directly is bound to one engine and waits forever under the other. This reader goes through the reader node's own
/// [AetherNode#sliceRows()], the production source that already makes that choice, so no row-family logic is duplicated here.
interface SliceStates {
    /// True when `reader`'s committed view holds an ACTIVE instance of `artifact` on `node`, whichever engine wrote it.
    static boolean isActive(AetherNode reader, NodeId node, Artifact artifact) {
        return statesOf(reader, node, artifact).contains(SliceState.ACTIVE);
    }

    /// Every state `reader`'s committed view holds for `artifact` on `node`; empty when the engine wrote no current row.
    static List<SliceState> statesOf(AetherNode reader, NodeId node, Artifact artifact) {
        var states = new ArrayList<SliceState>();
        reader.sliceRows()
              .forEachRow(reader.kvStore(),
                          (rowNode, rowArtifact, state) -> collect(states, node, artifact, rowNode, rowArtifact, state));
        return states;
    }

    @Contract
    private static void collect(List<SliceState> states,
                                NodeId node,
                                Artifact artifact,
                                NodeId rowNode,
                                Artifact rowArtifact,
                                SliceState state) {
        if (rowNode.equals(node) && rowArtifact.equals(artifact)) {
            states.add(state);
        }
    }
}
