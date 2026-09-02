// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.projection;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// The read-model half of a [Projection] (durable-pubsub-spec §10): where folded state lives, plus
/// the two projection-lifecycle slots the facade needs from its backing — a persisted GENERATION
/// counter and a reset.
///
/// The seam is deliberately backing-agnostic: a KV resource, a distributed cache, or an
/// entity-range backing all implement the same five operations, so the facade never churns when a
/// concrete `.into(...)` sugar arrives for one of them.
///
/// **The reset contract (settles spec §13 item 6):** [#reset] clears EVERY read-model entry the
/// projection wrote — KV-prefix clear, cache clear, or entity range-delete, whichever the backing
/// means by it — while PRESERVING the generation slot. The facade's rebuild sequence depends on
/// that split: it bumps the generation FIRST (so replayed events land under the new generation's
/// idempotency keys once the guard exists), then resets the data, and a reset that also wiped the
/// generation would resurrect the prior pass's keys and dedup the entire replay into a no-op —
/// the exact failure the generation exists to prevent (spec review finding 3).
///
/// **Generation slot durability:** the counter must survive both [#reset] and process restart with
/// the same durability as the read model itself — it versions that model, and a model that
/// outlives its version marker dedups or replays wrongly after recovery.
public interface ProjectionStore<S> {
    Promise<Option<S>> read(String key);
    Promise<Unit> write(String key, S state);
    /// Clear the read model, preserving the generation slot. See the reset contract above.
    Promise<Unit> reset();
    /// Current generation; 0 when never bumped.
    Promise<Long> generation();
    /// Atomically advance and return the new generation.
    Promise<Long> bumpGeneration();
}
