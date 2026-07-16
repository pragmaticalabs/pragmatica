// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// The durable-entity linearizable-read no-op consensus round (#345 item 1e-b, spec §8.1 `no-op-round`
/// mechanism) — the entity-module analog of the stream's `LinearizableBarrier`. Before serving a
/// `LINEARIZABLE` read the committed owner orders ONE benign consensus round for the key's
/// `(keyspace, partition)` arc and awaits its OWN local apply of it: because Rabia applies decisions in a
/// single total order, once this round has applied locally every ownership change committed before it has
/// ALSO applied locally (advancing the epoch high-water), so re-checking the fence AFTER the round makes
/// the serve decision current.
///
/// The round runs ONLY at the committed owner, ONLY for `LINEARIZABLE` reads, at the serve point — no
/// cost to [ReadConsistency#BOUNDED_STALE] reads. The production `no-op-round` binding (submitting a
/// `KVCommand.Noop` for the arc through the cluster apply path) is wired with the durable-entity node
/// wiring (#277); until then the barrier is absent and `LINEARIZABLE` degrades to the local read.
@FunctionalInterface
public interface EntityLinearizableBarrier {
    /// Order one no-op consensus round for the `(keyspace, partition)` arc and complete once THIS node
    /// has applied it locally (the barrier). On expiry of the read's timeout budget the returned promise
    /// fails so the read is rejected rather than served from a pre-round view.
    Promise<Unit> awaitRound(String keyspace, int partition);
}
