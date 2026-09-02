// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import java.time.Duration;
import java.util.UUID;

import org.pragmatica.lang.Option;
import org.pragmatica.aether.resource.Mutator;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// A durable, single-writer, keyed entity — the foundational primitive for durable workflows and
/// sagas (epic #345, spec §5). An entity instance is a `(key, state)` pair whose `state` is an
/// application-defined immutable value, mutated by exactly one writer at a time.
///
/// ## Semantics (spec §4.2–§4.3, §8)
///
///   - **Single-writer total order per key.** All operations on the same `key` are applied in
///     total order (serialized); operations on different keys proceed concurrently. The sole
///     inherent bottleneck is a single hot key.
///   - **Pure mutator.** [#update] runs a pure `S → S` mutator inside the per-key serialization;
///     it performs no IO. Side effects belong to the caller, which consumes the returned state
///     (spec §10).
///   - **Per-call read consistency (spec §8.1, resolves S5 / #382).** Reads take an optional
///     [ReadConsistency]. The no-arg [#get] and [#get(Object, ReadConsistency)] with
///     [ReadConsistency#BOUNDED_STALE] read this process's committed-prefix map for the key — a local,
///     single-writer-serialized read (the honest current semantics of the HA-only in-memory cut).
///     [ReadConsistency#LINEARIZABLE] routes to the key's committed partition owner and orders a no-op
///     consensus round + post-round epoch fence before serving (#345 item 1e-b); when the owner-routing
///     substrate is not yet wired (#277) it degrades to the local read, which on a single owner already
///     reflects every acknowledged write.
///
/// ## Binding a keyspace into a slice
///
/// There is no shipped `@Entity` qualifier, and that is deliberate. `@Http` and `@Notify` are
/// parameterless because each names a single fixed section — `@ResourceQualifier(type = ...,
/// config = "http")` bakes the section into the meta-annotation at the DECLARATION site, so the use
/// site carries no string. Durable entities are **per-keyspace** (`entities.orders`,
/// `entities.payments`), so one shipped qualifier could not cover them without growing a
/// `config = "..."` member, and strings at the use site are not this codebase's style.
///
/// The pattern is therefore **one author-declared annotation per keyspace**:
///
/// ```
/// @ResourceQualifier(type = DurableEntity.class, config = "entities.orders")
/// @Retention(RUNTIME) @Target(PARAMETER)
/// public @interface OrderEntity {}
/// ```
///
/// Used bare at the factory parameter, with no string in sight:
///
/// ```
/// static OrderSlice orderSlice(@OrderEntity DurableEntity<String, OrderState> orders) { ... }
/// ```
///
/// A slice holding several families declares several qualifiers — `@OrderEntity`,
/// `@PaymentEntity` — each naming its own `[entities.<keyspace>]` section in exactly one place.
/// The section named must exist in the blueprint's `resources.toml`.
///
/// **Serialization comes for free.** The slice processor collects the type arguments of every
/// resource-qualified parameter as codec types, so `K` and `S` above (`String`, `OrderState`) are
/// registered in the slice's `SliceCodec` without any author annotation. Records and enums have
/// their codecs generated; a state type that is neither must have a codec supplied by the node
/// (`@CodecFor`), and its absence fails at slice load with a named type rather than at first write.
///
/// ## Slice boundary (this cut)
///
/// This is the **HA-only, in-memory** first functional cut (spec §4.4, plan Phase 2b): state lives
/// in an in-memory map and operations serialize per key on a single owner. The ownership fence
/// (#345) and restart-durable state (fenced log / persistent DHT, spec §4.4 / epic #349) replace
/// the backing store in later slices behind this same API, with no author churn.
///
/// @param <K> entity key type — used only as a map key (equals/hashCode); never mutated
/// @param <S> entity state type — an application-defined immutable value (record / sealed interface)
public interface DurableEntity<K, S, C extends Mutator<S>> {
    /// Create a new entity instance for `key` with `initial` state.
    ///
    /// Fails with [EntityError.EntityAlreadyExists] if the key already holds state. The create
    /// is applied inside the per-key serialization, so concurrent creates on the same key are
    /// totally ordered and exactly one wins.
    ///
    /// @param key     entity key
    /// @param initial initial state
    ///
    /// @return the created state, or a failure if the key already exists
    Promise<S> create(K key, S initial);

    /// Read the current state for `key` with [ReadConsistency#BOUNDED_STALE] — the local committed-prefix
    /// read of this process's in-memory map (reflects [#create]/[#update] applied on THIS owner). Returns
    /// [Option#none()] when no state exists for the key. Equivalent to
    /// [#get(Object, ReadConsistency)] with [ReadConsistency#BOUNDED_STALE].
    ///
    /// @param key entity key
    ///
    /// @return the current state if present, otherwise empty
    Promise<Option<S>> get(K key);

    /// Read the current state for `key` with the requested [ReadConsistency] (spec §8.1). Returns
    /// [Option#none()] when no state exists for the key.
    ///
    /// [ReadConsistency#BOUNDED_STALE] serves the local committed-prefix read (identical to [#get]).
    /// [ReadConsistency#LINEARIZABLE] reflects every write acknowledged before the read began — routed to
    /// the key's committed partition owner + no-op round + post-round epoch fence (#345 item 1e-b).
    ///
    /// This default serves BOTH consistencies with the local read [#get]: on a single-owner cut (the
    /// HA-only in-memory / fenced backings) the local committed-prefix read already reflects every
    /// acknowledged write, so `LINEARIZABLE` is served correctly by the local read. Cluster-wired
    /// implementations override this to route a `LINEARIZABLE` read to the committed owner; when the
    /// owner-routing substrate is absent or no ownership record is committed for the arc, they too degrade
    /// to the local read.
    ///
    /// @param key         entity key
    /// @param consistency requested read consistency
    ///
    /// @return the current state if present, otherwise empty
    default Promise<Option<S>> get(K key, ReadConsistency consistency) {
        return get(key);
    }

    /// Apply a pure `mutator` to the current state for `key`, committing the result.
    ///
    /// The mutator runs **on the owner, inside the per-key serialization** (spec §4.3): same-key
    /// updates are totally ordered, so the read-modify-write is race-free without locks. The
    /// mutator must be a pure `S → S` with no IO; side effects belong to the caller consuming the
    /// returned state (spec §10). Fails with [EntityError.EntityNotFound] if the key holds no
    /// state.
    ///
    /// @param key     entity key
    /// @param mutator pure state transition applied under the per-key serialization
    ///
    /// @return the post-mutation state, or a failure if the key is absent
    Promise<S> update(K key, C mutator);

    /// Schedule a one-shot timer that applies `onFire` to the entity state after `delay`.
    ///
    /// Supported by the fenced-log backing (#345 I4), where a pending timer is a record in the entity's
    /// own durable log and therefore survives handover and restart by the same machinery state does. The
    /// HA-only in-memory cut declines with [EntityError.TimerNotSupported].
    ///
    /// ## Semantics, stated per property rather than as a label
    ///   - **One-shot.** The timer fires at most once and leaves the pending set when it does. There is no
    ///     repeat form; a recurring timer is the caller re-scheduling from the fired command.
    ///   - **Wall-clock instant, stamped by the OWNER.** `delay` is resolved to an absolute instant by the
    ///     committed owner, on the owner's own clock, at the moment the schedule is appended to the log. A
    ///     schedule issued anywhere else travels as a DELAY and is stamped on arrival, so the clock that
    ///     mints the instant is the clock that later finds it due and sender/owner skew never enters the
    ///     timer. The instant is then stored, so a handover or restart does not restart the delay. Skew
    ///     enters in one place only: across a HANDOVER, where the stamped instant is compared against a
    ///     successor owner's clock and the fire shifts by the difference between the two.
    ///   - **At or after, never before — as measured by the FIRING node's clock.** A timer fires at the
    ///     first tick at or after its instant, so lateness is bounded by the tick interval. Worst-case
    ///     lateness is one tick interval — one second — plus however long the key's partition goes without
    ///     a live owner: a timer whose owner is being replaced fires when the new owner takes over, late
    ///     but not lost. Earliness in REAL time has exactly one route, the handover above: a successor
    ///     owner whose clock runs ahead of the stamping owner's fires the timer that far before the
    ///     wall-clock instant the caller meant. **Sub-second punctuality is not offered here.** A deadline
    ///     measured in milliseconds wants in-memory scheduling, which buys that precision by giving up
    ///     durability across an owner change.
    ///     [design intent — unverified] — the handover and full-restart gates measure it.
    ///   - **The key must exist.** Scheduling on a key that holds no state fails with
    ///     [EntityError.EntityNotFound] — there is nothing for `onFire` to mutate.
    ///   - **The token is the CALLER's, and a retry carrying it is the SAME schedule.** This overload mints
    ///     a fresh token and delegates to [#scheduleTimer(Object, Duration, Mutator, TimerToken)], which is
    ///     the entry a caller that intends to retry uses directly — the only one able to present the same
    ///     token twice. The owner recognises an already-pending token, appends nothing, and answers with
    ///     that same token. A retry therefore cannot plant a second timer, and the at-least-once caveat
    ///     that a retried operation may leave behind an effect the caller cannot name does NOT apply here.
    ///   - **The fire is an ordinary update.** `onFire` runs on the owner, inside the key's per-key
    ///     serialization, totally ordered against every concurrent operation on that key (spec §4.5).
    ///   - **A fire that cannot be applied is CONSUMED, not retried.** If the command fails to decode or
    ///     its mutator throws, the timer is durably cancelled and the failure logged; the entity's state is
    ///     untouched. `onFire` is a pure `S -> S`, so a retry would fail identically. Recovery is the
    ///     operator's: fix the command and schedule again.
    ///
    /// @param key    entity key
    /// @param delay  delay before the timer fires
    /// @param onFire pure state transition applied when the timer fires
    ///
    /// @return a token identifying the scheduled timer, or a failure
    default Promise<TimerToken> scheduleTimer(K key, Duration delay, C onFire) {
        return scheduleTimer(key,
                             delay,
                             onFire,
                             TimerToken.timerToken(UUID.randomUUID().toString()));
    }

    /// Schedule a one-shot timer under a token the CALLER supplies — the retry-safe entry.
    ///
    /// Every semantic of [#scheduleTimer(Object, Duration, Mutator)] holds unchanged; the only difference is
    /// where the token comes from, and that difference is the whole point. `scheduleTimer` returns its token
    /// in the answer, so a caller whose acknowledgement is lost or times out never learns it: the schedule
    /// may well have landed, and there is no cancel-by-key verb to reach it with. Minting the token BEFORE
    /// the call closes that window — the caller holds the handle whatever the answer turns out to be, and
    /// can re-send until it gets one.
    ///
    /// **A re-send carrying the same token is the same schedule, not a second one.** The owner answers a
    /// token it already has pending for this key with that token and appends nothing, so the effect lands
    /// exactly once no matter how many times the request is repeated. This holds across the forward hop
    /// too: the token travels to the committed owner, which re-runs its own admission and its own
    /// already-pending check on arrival.
    ///
    /// The token identifies the timer only WITHIN its key, so uniqueness is the caller's obligation only
    /// against its own concurrent schedules on the same key. Two DIFFERENT tokens on one key are two
    /// timers, deliberately.
    ///
    /// @param key    entity key
    /// @param delay  delay before the timer fires
    /// @param onFire pure state transition applied when the timer fires
    /// @param token  caller-minted handle identifying this schedule within `key`
    ///
    /// @return `token`, once the timer is pending, or a failure
    Promise<TimerToken> scheduleTimer(K key, Duration delay, C onFire, TimerToken token);

    /// Cancel a previously scheduled timer.
    ///
    /// Supported by the fenced-log backing (#345 I4); the HA-only in-memory cut declines with
    /// [EntityError.TimerNotSupported].
    ///
    /// **Idempotent** (spec §5.1): a token that already fired, was already cancelled, or belonged to a key
    /// that has since been deleted succeeds without doing anything — [#delete] auto-cancels the key's
    /// pending timers, so an absent key counts as already-cancelled rather than as an error.
    ///
    /// **A token whose schedule never landed is the same noop-success.** Both schedule entries fix the token
    /// before anything is appended, so a caller can hold a token for a schedule that was refused, timed out,
    /// or never reached the owner at all. Nothing is pending under such a token, so cancelling it succeeds
    /// with no record appended, exactly as an already-cancelled one does. That is what makes "cancel the
    /// token you hold" a safe recovery for a schedule whose outcome is unknown: it either removes the timer
    /// or finds nothing to remove, and the caller cannot tell — nor need to.
    ///
    /// @param key   entity key
    /// @param token token a schedule entry returned or was given
    ///
    /// @return success when cancelled or when there was nothing to cancel, or a failure
    Promise<Unit> cancelTimer(K key, TimerToken token);

    /// Delete the entity instance for `key`.
    ///
    /// Applied inside the per-key serialization, and it AUTO-CANCELS the key's pending timers (spec §5.1)
    /// — otherwise each would come due against a key with no state, once per tick. Fails with
    /// [EntityError.EntityNotFound] if the key holds no state.
    ///
    /// @param key entity key
    ///
    /// @return success when deleted, or a failure if the key is absent
    Promise<Unit> delete(K key);

    /// Opaque handle identifying a scheduled timer for an entity (spec §5).
    record TimerToken(String value) {
        public static TimerToken timerToken(String value) {
            return new TimerToken(value);
        }
    }
}
