/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */

package org.pragmatica.statemachine;

import org.pragmatica.lang.Contract;

/// Hook for observing FSM transitions without coupling the core library to a metrics system.
/// Implementations can emit metrics, append trace events, or record transitions for tests.
///
/// Callbacks fire from the thread that executes the transition — the same thread that called
/// [`Fsm#dispatch`]. Implementations must be thread-safe and non-blocking.
@Contract
public interface FsmObserver<S, E> {
    /// Fired after a successful CAS and after `onExit` / transition action / `onEntry` have run.
    void onTransition(FsmTags tags, S from, S to);

    /// Fired when a CAS fails because another thread advanced the state between read and swap.
    /// `actual` is the state observed after the CAS attempt.
    void onCasLost(FsmTags tags, S expected, S actual);

    /// Fired when a state's `handle` method did not call `transitionTo` / `transitionToOrDrop`
    /// (event dropped through the default branch).
    void onEventIgnored(FsmTags tags, S state, E event);

    /// Fired when a state's `handle` method consumed the event via a side effect (no transition,
    /// not a fall-through ignore). Distinct from [`#onEventIgnored`] — the event was handled at
    /// the side-effect layer and must not conflate with truly-dropped events in dashboards.
    default void onHandled(FsmTags tags, S state, E event) {}

    /// No-op observer. Returned from [`#noop`].
    @SuppressWarnings("rawtypes")
    FsmObserver NOOP = new FsmObserver() {
        @Override @Contract public void onTransition(FsmTags tags, Object from, Object to) {}
        @Override @Contract public void onCasLost(FsmTags tags, Object expected, Object actual) {}
        @Override @Contract public void onEventIgnored(FsmTags tags, Object state, Object event) {}
        @Override @Contract public void onHandled(FsmTags tags, Object state, Object event) {}
    };

    @SuppressWarnings("unchecked")
    static <S, E> FsmObserver<S, E> noop() {
        return (FsmObserver<S, E>) NOOP;
    }
}
