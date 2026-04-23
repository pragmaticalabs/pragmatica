/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */

package org.pragmatica.statemachine;

import java.util.concurrent.atomic.AtomicReference;

/// GoF-style state machine with CAS-guarded transitions. Dispatch runs on the caller thread; no
/// executor, no queue. Concurrent dispatches are serialized via `AtomicReference.compareAndSet`
/// on the state reference: only the thread that wins the CAS runs the transition action and
/// entry/exit hooks.
///
/// State identity is by reference. Data-free states must be singletons; data-carrying states
/// are typically immutable records created fresh per transition. See [`FsmState`] for details.
///
/// @param <S> The sealed state hierarchy.
/// @param <E> The event type accepted by the FSM.
public final class Fsm<S extends FsmState<S, E>, E> {
    private final String name;
    private final AtomicReference<S> currentState;
    private final FsmObserver<S, E> observer;

    private Fsm(String name, S initial, FsmObserver<S, E> observer) {
        this.name = name;
        this.currentState = new AtomicReference<>(initial);
        this.observer = observer;
    }

    public static <S extends FsmState<S, E>, E> Fsm<S, E> fsm(String name, S initial) {
        return new Fsm<>(name, initial, FsmObserver.noop());
    }

    public static <S extends FsmState<S, E>, E> Fsm<S, E> fsm(String name, S initial, FsmObserver<S, E> observer) {
        return new Fsm<>(name, initial, observer);
    }

    public String name() {
        return name;
    }

    public S current() {
        return currentState.get();
    }

    /// Dispatch an event to the current state. The current state's [`FsmState#handle`] method
    /// decides whether to request a transition; the CAS happens inside
    /// [`TransitionRequest#transitionTo`].
    public void dispatch(E event) {
        var state = currentState.get();
        var tx = new TransitionRequest<>(this, state, event);
        state.handle(event, tx);
    }

    /// Package-private helper invoked by [`TransitionRequest#ignore`].
    void recordIgnored(S state, E event) {
        observer.onEventIgnored(name, state, event);
    }

    /// Package-private CAS operation invoked by [`TransitionRequest`].
    ///
    /// Returns `true` if the CAS succeeded (and all hooks fired); `false` if another thread
    /// advanced the state first.
    ///
    /// Action ordering on CAS success: `onExit(from) → transitionAction → onEntry(target) →
    /// observer.onTransition`.
    boolean tryAdvance(S expected, S target, Runnable transitionAction) {
        if (!currentState.compareAndSet(expected, target)) {
            observer.onCasLost(name, expected, currentState.get());
            return false;
        }
        expected.onExit();
        transitionAction.run();
        target.onEntry();
        observer.onTransition(name, expected, target);
        return true;
    }
}
