/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */

package org.pragmatica.statemachine;

/// Hook for observing FSM transitions without coupling the core library to a metrics system.
/// Implementations can emit metrics, append trace events, or record transitions for tests.
///
/// Callbacks fire from the thread that executes the transition — the same thread that called
/// [`Fsm#dispatch`]. Implementations must be thread-safe and non-blocking.
public interface FsmObserver<S, E> {
    /// Fired after a successful CAS and after `onExit` / transition action / `onEntry` have run.
    void onTransition(String fsmName, S from, S to);

    /// Fired when a CAS fails because another thread advanced the state between read and swap.
    /// `actual` is the state observed after the CAS attempt.
    void onCasLost(String fsmName, S expected, S actual);

    /// Fired when a state's `handle` method did not call `transitionTo` / `transitionToOrDrop`
    /// (event dropped through the default branch).
    void onEventIgnored(String fsmName, S state, E event);

    /// No-op observer. Returned from [`#noop`].
    @SuppressWarnings("rawtypes")
    FsmObserver NOOP = new FsmObserver() {
        @Override public void onTransition(String fsmName, Object from, Object to) {}
        @Override public void onCasLost(String fsmName, Object expected, Object actual) {}
        @Override public void onEventIgnored(String fsmName, Object state, Object event) {}
    };

    @SuppressWarnings("unchecked")
    static <S, E> FsmObserver<S, E> noop() {
        return (FsmObserver<S, E>) NOOP;
    }
}
