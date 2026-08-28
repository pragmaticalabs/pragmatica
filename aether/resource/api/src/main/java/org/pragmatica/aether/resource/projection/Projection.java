// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.projection;

import org.pragmatica.aether.slice.topic.Topic;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;


/// A projection is NOTHING BUT a durable subscriber with an idempotent apply (durable-pubsub-spec
/// §10): fold each durably-delivered topic event into a keyed read model.
///
/// ```java
/// var projection = Projection.of(ORDERS_COMPLETED)              // Topic<OrderCompleted>
///                            .into(store, OrderCompleted::orderId)
///                            .apply(Orders::fold);
/// ```
///
/// The slice's durable subscriber method delegates each event to [#onEvent]; the durable dispatch
/// path (serial per group × partition, bounded redelivery, group-attributed DLQ) is what makes the
/// projection converge — the facade adds the fold, the keyed write, and the rebuild lifecycle.
///
/// **Current guarantee, stated loudly (guard pending):** the apply is **at-least-once** — a
/// crash-window redelivery or a retry racing a slow attempt re-applies the event. The §8
/// idempotency guard keyed `(projectionName, generation, messageId)` completes this facade once
/// the context-aware subscriber shape lands (the messageId has no channel to this method until
/// then); folds MUST tolerate re-application until that lands — idempotent folds (last-write-wins
/// on a keyed upsert, set-union, max) are unaffected; counting folds will overcount on redelivery.
/// This paragraph is deleted by the change that wires the guard, not softened.
///
/// **Rebuild (one operator procedure, spec §10):** [#rebuild] bumps the persisted generation, then
/// resets the read model ([ProjectionStore#reset] — the §13-item-6 contract: data cleared,
/// generation preserved), then asks the cursor-reset seam to send the group's cursor to the
/// earliest retained offset. Until the D3 operator surface provides that reset, the DEFAULT seam
/// REFUSES loudly — a rebuild that silently skipped the cursor step would clear the model and then
/// replay nothing, converging to an empty projection that looks caught-up. A rebuild replays only
/// what retention still holds; older history is a partial rebuild, reported by the same cursor
/// machinery (`CURSOR_GAP` semantics when that surface lands).
public record Projection<S, T>(String name,
                               Topic<T> topic,
                               ProjectionStore<S> store,
                               Fn1<String, T> key,
                               Fn2<S, Option<S>, T> fold,
                               Fn0<Promise<Unit>> cursorReset) {
    private static final Cause CURSOR_RESET_PENDING = Causes.cause("Projection rebuild: group-cursor reset is not wired yet (arrives with the durable pub-sub"
                                                                  + " operator surface, #386 D3) — rebuild refused rather than silently replaying nothing");

    /// Apply one durably-delivered event: read the keyed state, fold, write back. At-least-once
    /// applied — see the class doc's guard-pending paragraph.
    public Promise<Unit> onEvent(T event) {
        var eventKey = key.apply(event);

        return store.read(eventKey)
                    .flatMap(current -> store.write(eventKey,
                                                    fold.apply(current, event)));
    }

    /// Bump generation → reset read model → reset the group cursor. Order is load-bearing: the
    /// generation moves FIRST so every replayed event lands under the new generation's idempotency
    /// keys (once the guard exists) instead of being dedup'd into a no-op by the prior pass's
    /// claims.
    public Promise<Unit> rebuild() {
        return store.bumpGeneration()
                    .flatMap(_ -> store.reset())
                    .flatMap(_ -> cursorReset.apply());
    }

    public static <T> Builder<T> of(Topic<T> topic) {
        return new Builder<>(topic);
    }

    public record Builder<T>(Topic<T> topic) {
        public <S> Bound<S, T> into(ProjectionStore<S> store, Fn1<String, T> key) {
            return new Bound<>(topic, store, key);
        }
    }

    public record Bound<S, T>(Topic<T> topic, ProjectionStore<S> store, Fn1<String, T> key) {
        /// Group identity = the projection's name (spec §10); defaults to the topic name — one
        /// projection per topic per slice is the common case, and a second one names itself.
        public Projection<S, T> apply(Fn2<S, Option<S>, T> fold) {
            return apply(topic.name(), fold);
        }

        public Projection<S, T> apply(String projectionName, Fn2<S, Option<S>, T> fold) {
            return new Projection<>(projectionName, topic, store, key, fold, () -> CURSOR_RESET_PENDING.promise());
        }

        /// Deployment-wired variant: the cursor-reset seam is supplied by the runtime once the
        /// operator surface exists; tests supply a recording stub.
        public Projection<S, T> apply(String projectionName,
                                      Fn2<S, Option<S>, T> fold,
                                      Fn0<Promise<Unit>> cursorReset) {
            return new Projection<>(projectionName, topic, store, key, fold, cursorReset);
        }
    }
}
