// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.projection;

import org.pragmatica.aether.resource.projection.ProjectionClaims.ClaimOutcome;
import org.pragmatica.aether.resource.projection.ProjectionClaims.Claimed;
import org.pragmatica.aether.resource.projection.ProjectionClaims.Held;
import org.pragmatica.aether.resource.projection.ProjectionClaims.Settlement;
import org.pragmatica.aether.slice.topic.MessageContext;
import org.pragmatica.aether.slice.topic.Topic;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


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
/// **Guarantee, per operation (spec §8).** [#onEvent(Object, MessageContext)] — the
/// context-carrying shape — folds under a claim on `(projectionName, generation, messageId)`:
/// [ProjectionClaims#claimIfAbsent] takes a PENDING claim with a lease, the fold runs, then
/// [ProjectionClaims#finalizeClaim] marks it DONE. What that earns:
///
///   - **Concurrent attempts on one key are suppressed to one apply** — a zombie attempt (§6) and its
///     retry included — among every instance whose [ProjectionClaims] backing performs the claim as
///     one indivisible step over a store they all read. The loser gets a retryable failure rather than
///     a success — acknowledging it would lose the event if the holder's fold then failed. Redelivery
///     is BOUNDED (spec §6 default: 5 attempts, 1s base backoff doubling, about 15s in all), so a loser
///     that still meets a live claim when its budget runs out goes to the DLQ; a redrive then finds
///     the key DONE and is suppressed, or reclaims it once the lease has expired.
///   - **A failed fold releases its claim**, so the retry applies instead of being suppressed.
///   - **An attempt that outlives its lease cannot finalize or release its successor's claim.** Every
///     claim carries a fencing token, and [ProjectionClaims#finalizeClaim] and
///     [ProjectionClaims#releaseClaim] act only while the stored claim still carries the caller's
///     token; otherwise they change nothing, answer STALE, and are logged at WARN.
///   - **A crash BETWEEN fold and finalize, or a finalize that is not recorded, re-applies once the
///     lease expires** — through whichever attempt arrives after expiry. The lease must exceed the
///     longest fold (up to the 30s attempt timeout by default), which outlasts the default retry
///     budget, so under defaults that re-apply comes through a DLQ redrive, not the remaining retries.
///     That lease-versus-retry-budget ratio is the interaction to size: a lease shorter than the budget
///     lets retries reclaim, and equally lets a slow fold be overlapped. A non-idempotent fold is
///     therefore at-least-once in that window, and so is an attempt that outlives its lease: the
///     token fences the CLAIM, not the read-model write, so its late fold still lands beside the
///     successor's. Exactly-once apply needs the store to apply fold and claim in ONE transaction —
///     the named path, not built.
///   - **Beyond the claim store's retention or durability**, an evicted or lost DONE claim re-admits
///     the duplicate it was recording.
///
/// This is never exactly-once delivery.
///
/// The single-argument [#onEvent(Object)] remains the honest **at-least-once** path for subscribers
/// that do not carry a [MessageContext]. The two shapes are two honest contracts, not a good one and
/// a degraded one — a fold that is naturally idempotent (last-write-wins upsert, set-union, max)
/// needs nothing more.
///
/// **Ordering: claim, fold, finalize.** The claim is PENDING, not DONE, while the fold runs, so a
/// failed or crashed fold never looks applied — it is released, or reclaimed after its lease — and
/// the event is not lost silently. The cost is the re-apply window named above.
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
                               Option<ClaimGuard> claims) {
    private static final Logger LOG = LoggerFactory.getLogger(Projection.class);

    private static final Cause CURSOR_RESET_PENDING = Causes.cause("Projection rebuild: group-cursor reset is not wired yet (arrives with the durable pub-sub"
                                                                  + " operator surface, #386 D3) — rebuild refused rather than silently replaying nothing");

    private static final Cause CLAIMS_UNWIRED = Causes.cause("Projection: a context-carrying event arrived but no ProjectionClaims backing is wired, so the §8"
                                                            + " idempotency guard cannot run — refused rather than applying unguarded, because a projection"
                                                            + " that LOOKS guarded and is not is worse than one that plainly is not. Wire a claims backing,"
                                                            + " or use the single-argument onEvent for the honest at-least-once path");

    private static final Cause CLAIM_IN_PROGRESS = Causes.cause("Projection: another attempt holds a live claim on this event, so it is not applied here —"
                                                               + " refused rather than acknowledged, because the holder may still fail and"
                                                               + " release; retry until the claim is DONE or released");

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

    /// Failures of the §8 guard a caller can act on.
    public sealed interface ProjectionError extends Cause {
        /// The configured claim lease is zero or negative. A claim that expires the instant it is taken
        /// suppresses nothing, so the guarded apply is refused rather than run unguarded.
        record NonPositiveLease(TimeSpan lease, String message) implements ProjectionError {
            static final Fn1<NonPositiveLease, TimeSpan> FACTORY = Causes.forOneValue("Projection: claim lease %s is not positive — a claim that expires the instant it is"
                                                                                     + " taken suppresses nothing, so the guarded apply is refused",
                                                                                      NonPositiveLease::new);
        }
    }

    /// Apply one durably-delivered event under the §8 claim — see the class doc for the guarantee,
    /// stated per operation.
    ///
    /// The generation is read PER EVENT and deliberately not cached. A rebuild bumps it, and a cached
    /// value would key the replayed events under the previous generation, matching the prior pass's
    /// claims and dedup'ing the entire replay into a no-op — the exact failure `generation` is in the
    /// key to prevent. Local invalidation would not be sound either: the rebuild may be performed on
    /// another node, so this instance never learns of it. The cost is one generation read per event.
    public Promise<Unit> onEvent(T event, MessageContext context) {
        return claims.fold(CLAIMS_UNWIRED::promise, guard -> applyGuarded(event, context, guard));
    }

    /// A non-positive lease is refused BEFORE claiming: such a claim expires the instant it is taken,
    /// so racing attempts would each find the other's claim expired and all fold. Checked per event,
    /// like the unwired-claims refusal, so misconfiguration surfaces on the first guarded event.
    private Promise<Unit> applyGuarded(T event, MessageContext context, ClaimGuard guard) {
        return Result.success(guard)
                     .filter(invalid -> ProjectionError.NonPositiveLease.FACTORY.apply(invalid.lease()),
                             valid -> valid.lease()
                                           .nanos() > 0)
                     .async()
                     .flatMap(_ -> store.generation())
                     .flatMap(generation -> applyOnce(event,
                                                      guard,
                                                      new ClaimKey(name,
                                                                   generation,
                                                                   context.messageId())));
    }

    private Promise<Unit> applyOnce(T event, ClaimGuard guard, ClaimKey claimKey) {
        return guard.backing()
                    .claimIfAbsent(claimKey,
                                   guard.lease())
                    .flatMap(outcome -> resolveClaim(outcome,
                                                     event,
                                                     guard.backing(),
                                                     claimKey));
    }

    private Promise<Unit> resolveClaim(ClaimOutcome outcome, T event, ProjectionClaims backing, ClaimKey claimKey) {
        return switch (outcome) {
            case Claimed(var token) -> applyUnderClaim(event, backing, claimKey, token);
            case Held.DONE -> Promise.unitPromise();
            case Held.IN_PROGRESS -> CLAIM_IN_PROGRESS.promise();
        };
    }

    private Promise<Unit> applyUnderClaim(T event, ProjectionClaims backing, ClaimKey claimKey, long token) {
        return onEvent(event).fold(folded -> settleClaim(folded, backing, claimKey, token));
    }

    /// Success finalizes; failure releases, then reports the FOLD's cause. Both carry the claim's token,
    /// so an attempt whose lease expired changes nothing: its finalize or release answers STALE and is
    /// logged. A failed release is absorbed (FER): the PENDING claim is still reclaimable once its lease
    /// expires, so it delays the retry by at most one lease and never loses the event — while replacing
    /// the fold's cause with the release's would hide why the apply failed.
    private Promise<Unit> settleClaim(Result<Unit> folded, ProjectionClaims backing, ClaimKey claimKey, long token) {
        return folded.fold(cause -> backing.releaseClaim(claimKey, token)
                                           .onSuccess(settlement -> warnIfStale(settlement, "release", claimKey))
                                           .fold(_ -> cause.promise()),
                           _ -> backing.finalizeClaim(claimKey, token)
                                       .onSuccess(settlement -> warnIfStale(settlement, "finalize", claimKey))
                                       .mapToUnit());
    }

    /// A STALE settlement means this attempt no longer holds the claim — its lease expired and the key
    /// was reclaimed, or the claim is gone. Nothing was changed. On `finalize` the fold has already
    /// run, so a successor may apply the event again — the overlap the class doc names.
    @Contract
    private static void warnIfStale(Settlement settlement, String step, ClaimKey claimKey) {
        if (settlement == Settlement.STALE) {
            LOG.warn("Projection claim {} was stale on {}: this attempt no longer holds it (lease expired and"
                    + " reclaimed, or claim gone), so nothing was changed",
                     claimKey,
                     step);
        }
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
    /// keys instead of being dedup'd into a no-op by the prior pass's
    /// claims.
    public Promise<Unit> rebuild() {
        return store.bumpGeneration()
                    .flatMap(_ -> store.reset())
                    .flatMap(_ -> cursorReset.apply());
    }

    public static <T> Builder<T> of(Topic<T> topic) {
        return new Builder<>(topic);
    }

    /// Supply the §8 claims backing and the lease each claim is taken for, enabling
    /// [#onEvent(Object, MessageContext)]. Without it that method refuses rather than applying
    /// unguarded. The lease should exceed the longest fold an attempt can run: a fold that outlives
    /// its lease can be overlapped by a reclaiming attempt's fold. Its ratio to the redelivery budget
    /// decides whether a stuck claim is reclaimed by a retry or by a DLQ redrive (class doc).
    public Projection<S, T> withClaims(ProjectionClaims backing, TimeSpan lease) {
        return new Projection<>(name, topic, store, key, fold, cursorReset, Option.some(new ClaimGuard(backing, lease)));
    }

    /// The claims backing paired with the lease every claim is taken for.
    public record ClaimGuard(ProjectionClaims backing, TimeSpan lease) {}

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
