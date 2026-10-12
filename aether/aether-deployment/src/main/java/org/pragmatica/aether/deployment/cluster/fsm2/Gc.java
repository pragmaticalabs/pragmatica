// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Set;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.TxDraft;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Option;


/// Garbage collection of desired keys (spec C11, step 10): a desired key whose goal is UNLOADED and whose observed key is
/// gone has served its purpose. The removals of one drain are **batched** into one transaction (B-062, NT-30).
public sealed interface Gc {
    /// Queue the removal of `pair`'s desired key, expecting the exact value the mirror holds.
    static ClusterModel removeDesired(ClusterModel m, SlicePair pair) {
        return m.desired.get(pair)
                        .fold(() -> m,
                              value -> queue(m, pair, value));
    }

    private static ClusterModel queue(ClusterModel m, SlicePair pair, AetherValue.SliceDesiredValue value) {
        var mutation = new KVCommand.Mutation<AetherKey, AetherValue>(pair.desiredKey(),
                                                                      Option.some((AetherValue) value),
                                                                      Option.none());
        return Option.from(m.outbox.stream().filter(draft -> draft.kind() == SubmitKind.GC).findFirst())
                     .fold(() -> Outbox.direct(m,
                                               TxDraft.draft(SubmitKind.GC,
                                                             pair.desiredKey(),
                                                             List.of(mutation))
                                                      .withPairs(Set.of(pair))),
                           draft -> merge(m, draft, pair, mutation));
    }

    private static ClusterModel merge(ClusterModel m,
                                      TxDraft draft,
                                      SlicePair pair,
                                      KVCommand.Mutation<AetherKey, AetherValue> mutation) {
        var mutations = new ArrayList<>(draft.mutations());
        var pairs = new HashSet<>(draft.pairs());

        if (mutations.stream().noneMatch(known -> known.key()
                                                       .equals(mutation.key()))) {
            mutations.add(mutation);
            pairs.add(pair);
        }

        var next = new ArrayList<>(m.outbox);

        next.set(next.indexOf(draft),
                 new TxDraft(SubmitKind.GC,
                             draft.key(),
                             mutations,
                             draft.guards(),
                             pairs,
                             draft.node(),
                             draft.attempt(),
                             draft.onAccepted()));
        m.outbox = next;

        return m;
    }

    record unused() implements Gc {}
}
