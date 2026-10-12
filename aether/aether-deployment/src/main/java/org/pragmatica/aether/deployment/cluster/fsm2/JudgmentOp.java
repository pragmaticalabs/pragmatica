// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.HashMap;
import java.util.List;

import org.pragmatica.aether.slice.kvstore.AetherValue.ArtifactJudgmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.UnresponsiveEntry;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;


/// One change to the durable judgment of an artifact (spec 4.2). The handlers of one mailbox drain collect the
/// operations of an artifact and the flush folds them over the committed value into **one** mutation (R-4.2.2,
/// NT-108): contention on the judgment key is designed out.
public sealed interface JudgmentOp {
    /// A fatal failure: the permanent mark (R-3.3.6).
    record Fatal() implements JudgmentOp {}

    /// A transient failure on `node`: its count goes up by one (R-3.3.7).
    record Transient(NodeId node) implements JudgmentOp {}

    /// `node` recovered (an ACTIVE observation): its count is reset (R-4.2.3).
    record ResetRetries(NodeId node) implements JudgmentOp {}

    /// The retry budget of `node` is spent: its count is dropped (the exhaustion path of #922).
    record Exhausted(NodeId node) implements JudgmentOp {}

    /// A retraction (R-4.2.5, 6.1): `node` is excluded from placement of the artifact.
    record Retract(NodeId node, UnresponsiveEntry entry) implements JudgmentOp {}

    /// `node` answered, left, or the exclusion expired (R-4.2.5).
    record ClearUnresponsive(NodeId node) implements JudgmentOp {}

    /// A republish or removal: the whole judgment goes (R-4.2.2).
    record ClearAll() implements JudgmentOp {}

    /// Fold `ops` over the committed judgment. The result is none when nothing is left to record (no permanent mark, no
    /// counts, no exclusions): the key is then removed (4.4, "empty after clears").
    static Option<ArtifactJudgmentValue> fold(Option<ArtifactJudgmentValue> committed, List<JudgmentOp> ops) {
        var permanent = committed.map(ArtifactJudgmentValue::permanentFailure).or(false);
        var retries = new HashMap<NodeId, Integer>(committed.map(ArtifactJudgmentValue::retries).or(java.util.Map.of()));
        var unresponsive = new HashMap<NodeId, UnresponsiveEntry>(committed.map(ArtifactJudgmentValue::unresponsive)
                                                                           .or(java.util.Map.of()));

        for (var op : ops) {
            switch (op) {
                case Fatal _ -> permanent = true;
                case Transient t -> retries.merge(t.node(), 1, Integer::sum);
                case ResetRetries r -> retries.remove(r.node());
                case Exhausted e -> retries.remove(e.node());
                case Retract r -> unresponsive.put(r.node(), r.entry());
                case ClearUnresponsive c -> unresponsive.remove(c.node());
                case ClearAll _ -> {
                    permanent = false;
                    retries.clear();
                    unresponsive.clear();
                }
            }
        }

        return permanent || !retries.isEmpty() || !unresponsive.isEmpty()
               ? Option.some(new ArtifactJudgmentValue(permanent, retries, unresponsive))
               : Option.none();
    }
}
