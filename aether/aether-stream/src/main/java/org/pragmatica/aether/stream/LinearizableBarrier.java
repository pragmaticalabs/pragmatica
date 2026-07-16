// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;
import java.util.function.Function;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.CoreError;
import org.pragmatica.lang.io.TimeSpan;


/// The linearizable-read no-op consensus round (#345 item 1e-a, spec §8.1 `no-op-round` mechanism).
/// Stream appends do NOT go through consensus — the owner is the ordering authority — so a deposed
/// owner that has not yet APPLIED the committed ownership change passes its local epoch fence and
/// could serve reads missing the new owner's appends. Before serving a `LINEARIZABLE` read the owner
/// orders ONE benign consensus round and awaits its OWN local apply of it: because Rabia applies
/// decisions in a single total order, once this round has applied locally every ownership change
/// committed before it has ALSO applied locally (advancing the epoch high-water), so re-checking the
/// fence AFTER the round makes the serve decision current. Owner-routing + this round + the post-round
/// epoch fence + the catch-up gate together make the read linearizable.
///
/// The round runs ONLY at the committed owner, ONLY for `LINEARIZABLE` reads, at the serve point — no
/// cost to `BOUNDED_STALE`/`NEAREST`/`GOVERNOR`/`ANY_REPLICA` reads.
@FunctionalInterface
public interface LinearizableBarrier {
    /// Order one no-op consensus round for `(streamName, partition)` and complete once THIS node has
    /// applied it locally (the barrier). On expiry of the read's timeout budget the returned promise
    /// fails with [StreamError.LinearizableRoundTimeout] so the read is rejected rather than served
    /// from a pre-round view.
    Promise<Unit> awaitRound(String streamName, int partition);

    /// The production `no-op-round` mechanism: submit a [KVCommand.Noop] through the cluster apply
    /// path (which resolves only after the batch is committed AND applied to THIS node's state
    /// machine), bound the wait by the read timeout budget, and rename an expiry to the domain-named
    /// [StreamError.LinearizableRoundTimeout]. The `Noop` carries the arc's
    /// [StreamPartitionOwnershipKey] so concurrent barriers on the same arc share one round via the
    /// content-derived batch id; the applier ignores the key.
    static LinearizableBarrier noOpRound(Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                         TimeSpan timeout) {
        return (streamName, partition) -> applier.apply(barrierCommand(streamName, partition))
                                                 .timeout(timeout)
                                                 .mapToUnit()
                                                 .mapError(cause -> toRoundCause(cause, streamName, partition));
    }

    private static List<KVCommand<AetherKey>> barrierCommand(String streamName, int partition) {
        return List.of(new KVCommand.Noop<AetherKey>(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(streamName,
                                                                                                             partition)));
    }

    private static Cause toRoundCause(Cause cause, String streamName, int partition) {
        return cause instanceof CoreError.Timeout
               ? new StreamError.LinearizableRoundTimeout(streamName, partition)
               : cause;
    }
}
