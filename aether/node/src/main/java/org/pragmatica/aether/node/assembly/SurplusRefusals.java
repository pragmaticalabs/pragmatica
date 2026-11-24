// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Result.unitResult;
import static org.pragmatica.lang.Unit.unit;


/// #3626: the refusal last told for each victim of the voter-excluded core retirement, so a refused drain is warned once per
/// transition of (victim, cause), never on every 1 s retirement pass. A victim that drains, or that a pass did not try, is
/// forgotten, so its next refusal is told again.
record SurplusRefusals(Map<NodeId, String> told, BiConsumer<NodeId, Cause> warn) {
    static SurplusRefusals surplusRefusals(BiConsumer<NodeId, Cause> warn) {
        return new SurplusRefusals(new ConcurrentHashMap<>(), warn);
    }

    /// Keeps only the victims this pass tries: a victim no longer tried has left its refused state.
    Unit retainOnly(Collection<NodeId> tried) {
        told.keySet().retainAll(Set.copyOf(tried));

        return unit();
    }

    /// FER: one victim's drain outcome is absorbed here, so a refusal skips only its own victim and the pass goes on to the next.
    /// The refusal is not lost: it is warned on its transition, and the victim is offered again on the next pass.
    Result<Unit> settle(NodeId victim, Result<Unit> drained) {
        return drained.fold(cause -> refused(victim, cause), _ -> drainedNow(victim));
    }

    private Result<Unit> refused(NodeId victim, Cause cause) {
        Option.option(told.put(victim,
                               cause.message()))
              .filter(cause.message()::equals)
              .onEmpty(() -> warn.accept(victim, cause));

        return unitResult();
    }

    private Result<Unit> drainedNow(NodeId victim) {
        told.remove(victim);

        return unitResult();
    }
}
