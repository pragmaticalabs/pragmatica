/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */

package org.pragmatica.statemachine;

import org.pragmatica.lang.Contract;

/// A state in a GoF-style state machine. Each state owns the logic for the events it accepts.
/// Implementations pattern-match the event and call [`TransitionRequest#transitionTo`] (or a
/// sibling) to request a transition; the library guards the transition with CAS on the state
/// reference.
///
/// Data-free states should be implemented as singletons (`public static final X INSTANCE = new X()`
/// or enum constants) — CAS compares references, so every entry into a data-free state must
/// resolve to the same object.
///
/// Data-carrying states should be immutable records. Each entry creates a fresh instance; the
/// reference uniquely identifies *this* entry. If a state genuinely needs mutable internal data,
/// the state itself owns the thread-safety of those fields (via atomic fields or explicit
/// synchronization). The library does not promise atomicity of state-internal mutations.
///
/// @param <S> The state type (the sealed hierarchy).
/// @param <E> The event type accepted by the state.
@Contract
public interface FsmState<S extends FsmState<S, E>, E> {
    /// Process an event. Pattern-match on the event type and call `tx.transitionTo(...)` to
    /// request a transition. Un-handled events should fall through to the default case (no
    /// transition requested, observer records the ignored event).
    void handle(E event, TransitionRequest<S, E> tx);

    /// Invariant cleanup for this state. Runs once on any transition *out* of this state,
    /// after the CAS succeeds and before the transition action.
    default void onExit() {}

    /// Invariant setup for this state. Runs once on any transition *into* this state, after the
    /// transition action and before the observer callback.
    default void onEntry() {}

    /// Invoked on `target` when its CAS attempt loses to another thread. The state was constructed
    /// with any eager resources it declared (e.g. scheduled futures in factory methods) but never
    /// became current — CAS lost. Use this hook to release those resources.
    ///
    /// MUST NOT dispatch events (same reentrancy constraint as `onEntry` / `onExit`).
    /// MUST NOT throw (JBCT).
    default void onCasLost() {}
}
