// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import java.util.List;
import java.util.function.Function;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;


/// The durable-entity linearizable-read no-op consensus round (#345 item 1e-b, spec §8.1 `no-op-round`
/// mechanism) — the entity-module analog of the stream's `LinearizableBarrier`. Before serving a
/// `LINEARIZABLE` read the committed owner orders ONE benign consensus round for the key's
/// `(keyspace, partition)` arc and awaits its OWN local apply of it: because Rabia applies decisions in a
/// single total order, once this round has applied locally every ownership change committed before it has
/// ALSO applied locally (advancing the epoch high-water), so re-checking the fence AFTER the round makes
/// the serve decision current.
///
/// The round runs ONLY at the committed owner, ONLY for `LINEARIZABLE` reads, at the serve point — no
/// cost to [ReadConsistency#BOUNDED_STALE] reads. When no barrier is wired the `LINEARIZABLE` arm does
/// NOT degrade to a local read: it rejects with [DurableEntityError.LinearizableUnavailable], because a
/// silently weaker read served under the stronger name is worse than a refusal (#345 I1 owner ruling).
@FunctionalInterface
public interface EntityLinearizableBarrier {
    /// Order one no-op consensus round for the `(keyspace, partition)` arc and complete once THIS node
    /// has applied it locally (the barrier). On expiry of the read's timeout budget the returned promise
    /// fails so the read is rejected rather than served from a pre-round view.
    Promise<Unit> awaitRound(String keyspace, int partition);

    /// The production `no-op-round` mechanism (#345 I1): submit a [KVCommand.Noop] through the cluster
    /// apply path — which resolves only after the batch is committed AND applied to THIS node's state
    /// machine — and bound the wait by `timeout`. The direct mirror of the stream's
    /// `LinearizableBarrier.noOpRound`, over the SAME `KVCommand`-generic cluster applier and the SAME
    /// [StreamPartitionOwnershipKey] record family the entity arcs already reuse, so concurrent barriers
    /// on one arc share a single round via the content-derived batch id; the applier ignores the key.
    ///
    /// Unlike the stream barrier this does NOT rename an expiry to a domain cause: every failure in this
    /// module's vocabulary is keyed by an entity key ([DurableEntityError#key]), and a round is scoped to
    /// an ARC, not a key. The expiry therefore reaches the caller as the transport-level timeout it is.
    static EntityLinearizableBarrier noOpRound(Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                               TimeSpan timeout) {
        return (keyspace, partition) -> applier.apply(barrierCommand(keyspace, partition))
                                               .timeout(timeout)
                                               .mapToUnit();
    }

    private static List<KVCommand<AetherKey>> barrierCommand(String keyspace, int partition) {
        return List.of(new KVCommand.Noop<AetherKey>(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(keyspace,
                                                                                                             partition)));
    }
}
