// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.projection;

import org.pragmatica.aether.slice.topic.MessageContext;
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
/// **Guarantee, with its exceptions named (spec §8).** [#onEvent(Object, MessageContext)] — the
/// context-carrying shape — applies each event **effectively-once, EXCEPT**:
///
///   1. **Concurrent cross-instance attempts.** The guard reads the claim, folds, then records it.
///      Two attempts that both read before either records will both apply. Suppression therefore
///      holds only if the supplied [ProjectionClaims] backing is itself atomic AND shared across
///      instances; an in-process default is neither, so a zombie attempt (§6) racing its retry on
///      another node is not suppressed.
///   2. **Beyond the claim store's retention or durability.** An evicted or lost claim re-admits the
///      duplicate it was recording.
///
/// This is never exactly-once delivery, and a guard with two named exceptions is not the same claim
/// as "duplicates handled".
///
/// The single-argument [#onEvent(Object)] remains the honest **at-least-once** path for subscribers
/// that do not carry a [MessageContext]. The two shapes are two honest contracts, not a good one and
/// a degraded one — a fold that is naturally idempotent (last-write-wins upsert, set-union, max)
/// needs nothing more.
///
/// **Ordering: fold first, record second.** Recording the claim BEFORE the fold would make a failed
/// fold look applied and lose the event silently; recording after means a crash between the two
/// re-applies it. For a projection converging a read model, a duplicate apply is the safer of the
/// two failures, so the order is chosen deliberately rather than incidentally.
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
                               Fn0<Promise<Unit>> cursorReset,
                               Option<ProjectionClaims> claims) {
    private static final Cause CURSOR_RESET_PENDING = Causes.cause("Projection rebuild: group-cursor reset is not wired yet (arrives with the durable pub-sub"
                                                                  + " operator surface, #386 D3) — rebuild refused rather than silently replaying nothing");

    private static final Cause CLAIMS_UNWIRED = Causes.cause("Projection: a context-carrying event arrived but no ProjectionClaims backing is wired, so the §8"
                                                            + " idempotency guard cannot run — refused rather than applying unguarded, because a projection"
                                                            + " that LOOKS guarded and is not is worse than one that plainly is not. Wire a claims backing,"
                                                            + " or use the single-argument onEvent for the honest at-least-once path");

    /// The §8 idempotency key. A record rather than a concatenated string so equality is structural
    /// and a backing cannot accidentally collide two projections whose names differ only where a
    /// separator would have fallen.
    ///
    /// `messageId` is the component that carries the identity: it is publisher-assigned and SURVIVES
    /// a DLQ redrive, which is precisely the path deduplication exists for — the source position does
    /// not survive it and would key the same event twice. `generation` is what makes a rebuild
    /// possible: bumping it moves every replayed event to fresh keys, so the prior pass's claims go
    /// inert instead of dedup'ing the whole replay into a no-op.
    public record ClaimKey(String projectionName, long generation, String messageId) {}

    /// Apply one durably-delivered event under the §8 guard — see the class doc for the guarantee and
    /// its two named exceptions.
    ///
    /// The generation is read PER EVENT and deliberately not cached. A rebuild bumps it, and a cached
    /// value would key the replayed events under the previous generation, matching the prior pass's
    /// claims and dedup'ing the entire replay into a no-op — the exact failure `generation` is in the
    /// key to prevent. Local invalidation would not be sound either: the rebuild may be performed on
    /// another node, so this instance never learns of it. The cost is one generation read per event.
    public Promise<Unit> onEvent(T event, MessageContext context) {
        return claims.fold(CLAIMS_UNWIRED::promise,
                           backing -> store.generation()
                                           .flatMap(generation -> applyOnce(event,
                                                                            backing,
                                                                            new ClaimKey(name,
                                                                                         generation,
                                                                                         context.messageId()))));
    }

    private Promise<Unit> applyOnce(T event, ProjectionClaims backing, ClaimKey claimKey) {
        return backing.get(claimKey)
                      .flatMap(claimed -> claimed.isPresent()
                                          ? Promise.unitPromise()
                                          : onEvent(event).flatMap(_ -> backing.put(claimKey, Boolean.TRUE)));
    }

    /// Apply one durably-delivered event: read the keyed state, fold, write back. **At-least-once
    /// applied** — no idempotency guard runs on this path, because without a [MessageContext] there is
    /// no key to guard by. Use [#onEvent(Object, MessageContext)] for the guarded shape.
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

    /// Supply the §8 claims backing, enabling [#onEvent(Object, MessageContext)]. Without it that
    /// method refuses rather than applying unguarded.
    public Projection<S, T> withClaims(ProjectionClaims backing) {
        return new Projection<>(name, topic, store, key, fold, cursorReset, Option.some(backing));
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
            return new Projection<>(projectionName,
                                    topic,
                                    store,
                                    key,
                                    fold,
                                    () -> CURSOR_RESET_PENDING.promise(),
                                    Option.none());
        }

        /// Deployment-wired variant: the cursor-reset seam is supplied by the runtime once the
        /// operator surface exists; tests supply a recording stub.
        public Projection<S, T> apply(String projectionName,
                                      Fn2<S, Option<S>, T> fold,
                                      Fn0<Promise<Unit>> cursorReset) {
            return new Projection<>(projectionName, topic, store, key, fold, cursorReset, Option.none());
        }
    }
}
