// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.ntt;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Unit.unit;


/// #3439: the refusal last told for each surplus-drain candidate the drain choke point refuses, so a refused drain is warned once
/// per transition of (node, cause), never on every reconcile pass, and stays readable as state while it holds. A node the choke
/// point stops refusing, or that is no longer a candidate, is forgotten, so its next refusal is told again. The whole record ends with
/// the term that made it and whenever a surplus episode closes ([#clear]).
record DrainRefusals(Map<NodeId, String> told, BiConsumer<NodeId, Cause> warn) {
    static DrainRefusals drainRefusals(BiConsumer<NodeId, Cause> warn) {
        return new DrainRefusals(new ConcurrentHashMap<>(), warn);
    }

    /// The selector's question, answered from the choke point's own refusal for `node`: `true` when the node may be drained now.
    /// A refusal is told on its transition and recorded, never dropped.
    boolean permits(NodeId node, Option<Cause> refusal) {
        return refusal.fold(() -> permitted(node), cause -> refusedNow(node, cause));
    }

    /// A refusal that reached dispatch (the choke point answered after the selector did): told and recorded as any other. An accepted
    /// drain needs no counterpart: the selector's `permits` already forgot the node when the predicate allowed it, before dispatch.
    Unit refused(NodeId node, Cause cause) {
        refusedNow(node, cause);

        return unit();
    }

    /// Keeps only the nodes this pass asked about: a node no longer a candidate has left its refused state.
    Unit retainOnly(Collection<NodeId> asked) {
        told.keySet().retainAll(Set.copyOf(asked));

        return unit();
    }

    /// Ends the record: every entry and so the told-state, because both live only while the term and the surplus episode do (invariant, #3439).
    Unit clear() {
        told.clear();

        return unit();
    }

    /// The nodes now refused, with the cause each was last refused for.
    Map<NodeId, String> view() {
        return Map.copyOf(told);
    }

    private boolean permitted(NodeId node) {
        told.remove(node);

        return true;
    }

    private boolean refusedNow(NodeId node, Cause cause) {
        option(told.put(node, cause.message())).filter(cause.message()::equals).onEmpty(() -> warn.accept(node, cause));

        return false;
    }
}
