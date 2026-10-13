// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Map;
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

    /// Whether no reconcile step derives this operation again once the intent that carried it is lost: it answers an event (a node
    /// answered or left, a slice became ACTIVE), where the others follow from rows and clocks the sweeps read.
    default boolean eventOnly() {
        return this instanceof ClearUnresponsive || this instanceof ResetRetries;
    }

    /// Fold `ops` over the committed judgment. The result is none when nothing is left to record (no permanent mark, no
    /// counts, no exclusions): the key is then removed (4.4, "empty after clears").
    static Option<ArtifactJudgmentValue> fold(Option<ArtifactJudgmentValue> committed, List<JudgmentOp> ops) {
        var start = new Judged(committed.map(ArtifactJudgmentValue::permanentFailure).or(false),
                               committed.map(ArtifactJudgmentValue::retries).or(Map.of()),
                               committed.map(ArtifactJudgmentValue::unresponsive).or(Map.of()));
        var end = ops.stream().reduce(start, Judged::apply, (_, later) -> later);

        return end.isEmpty()
               ? Option.none()
               : Option.some(new ArtifactJudgmentValue(end.permanent(), end.retries(), end.unresponsive()));
    }

    /// The judgment while operations are folded over it; each operation returns the next value.
    record Judged(boolean permanent, Map<NodeId, Integer> retries, Map<NodeId, UnresponsiveEntry> unresponsive) {
        Judged apply(JudgmentOp op) {
            return switch (op) {
                case Fatal _ -> new Judged(true, retries, unresponsive);
                case Transient t -> new Judged(permanent,
                                               with(retries,
                                                    t.node(),
                                                    retries.getOrDefault(t.node(), 0) + 1),
                                               unresponsive);
                case ResetRetries r -> new Judged(permanent, without(retries, r.node()), unresponsive);
                case Exhausted e -> new Judged(permanent, without(retries, e.node()), unresponsive);
                case Retract r -> new Judged(permanent,
                                             retries,
                                             with(unresponsive, r.node(), r.entry()));
                case ClearUnresponsive c -> new Judged(permanent, retries, without(unresponsive, c.node()));
            };
        }

        boolean isEmpty() {
            return ! permanent
                   && retries.isEmpty()
                   && unresponsive.isEmpty();
        }

        private static <V> Map<NodeId, V> with(Map<NodeId, V> map, NodeId node, V value) {
            var next = new HashMap<>(map);

            next.put(node, value);

            return next;
        }

        private static <V> Map<NodeId, V> without(Map<NodeId, V> map, NodeId node) {
            var next = new HashMap<>(map);

            next.remove(node);

            return next;
        }
    }
}
