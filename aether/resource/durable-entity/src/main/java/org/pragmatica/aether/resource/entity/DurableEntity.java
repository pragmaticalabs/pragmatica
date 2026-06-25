// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.time.Duration;


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
///   - **Linearizable get.** [#get] reflects the last committed state for the key (or a later one).
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
public interface DurableEntity<K, S> {
    /// Create a new entity instance for `key` with `initial` state.
    ///
    /// Fails with [DurableEntityError.KeyAlreadyExists] if the key already holds state. The create
    /// is applied inside the per-key serialization, so concurrent creates on the same key are
    /// totally ordered and exactly one wins.
    ///
    /// @param key     entity key
    /// @param initial initial state
    ///
    /// @return the created state, or a failure if the key already exists
    Promise<S> create(K key, S initial);

    /// Read the current state for `key`.
    ///
    /// Linearizable: reflects the last committed [#create]/[#update] for the key, or a later one.
    /// Returns [Option#none()] when no state exists for the key.
    ///
    /// @param key entity key
    ///
    /// @return the current state if present, otherwise empty
    Promise<Option<S>> get(K key);

    /// Apply a pure `mutator` to the current state for `key`, committing the result.
    ///
    /// The mutator runs **on the owner, inside the per-key serialization** (spec §4.3): same-key
    /// updates are totally ordered, so the read-modify-write is race-free without locks. The
    /// mutator must be a pure `S → S` with no IO; side effects belong to the caller consuming the
    /// returned state (spec §10). Fails with [DurableEntityError.KeyNotFound] if the key holds no
    /// state.
    ///
    /// @param key     entity key
    /// @param mutator pure state transition applied under the per-key serialization
    ///
    /// @return the post-mutation state, or a failure if the key is absent
    Promise<S> update(K key, Fn1<S, S> mutator);

    /// Schedule a one-shot timer that applies `onFire` to the entity state after `delay`.
    ///
    /// Durable per-entity timers are owned by a later slice (spec §4.5, plan Phase 2c). The HA-only
    /// in-memory cut declines this with [DurableEntityError.TimerNotSupported]; the signature is
    /// declared here to keep the API faithful to spec §5.
    ///
    /// @param key    entity key
    /// @param delay  delay before the timer fires
    /// @param onFire pure state transition applied when the timer fires
    ///
    /// @return a token identifying the scheduled timer, or a failure
    Promise<TimerToken> scheduleTimer(K key, Duration delay, Fn1<S, S> onFire);

    /// Cancel a previously scheduled timer.
    ///
    /// Durable per-entity timers are owned by a later slice (spec §4.5, plan Phase 2c). The HA-only
    /// in-memory cut declines this with [DurableEntityError.TimerNotSupported]; the signature is
    /// declared here to keep the API faithful to spec §5.
    ///
    /// @param key   entity key
    /// @param token token returned by [#scheduleTimer]
    ///
    /// @return success when cancelled, or a failure
    Promise<Unit> cancelTimer(K key, TimerToken token);

    /// Delete the entity instance for `key`.
    ///
    /// Applied inside the per-key serialization. Fails with [DurableEntityError.KeyNotFound] if the
    /// key holds no state.
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
