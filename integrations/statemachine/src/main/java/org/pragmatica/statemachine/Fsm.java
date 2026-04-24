/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */

package org.pragmatica.statemachine;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

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

    private Fsm(String name, FsmObserver<S, E> observer) {
        this.name = name;
        this.currentState = new AtomicReference<>();
        this.observer = observer;
    }

    private Fsm(String name, S initial, FsmObserver<S, E> observer) {
        this(name, observer);
        this.currentState.set(initial);
    }

    public static <S extends FsmState<S, E>, E> Fsm<S, E> fsm(String name, S initial) {
        return new Fsm<>(name, initial, FsmObserver.noop());
    }

    public static <S extends FsmState<S, E>, E> Fsm<S, E> fsm(String name, S initial, FsmObserver<S, E> observer) {
        return new Fsm<>(name, initial, observer);
    }

    /// Constructor-driven initial state factory. The factory receives the partially-constructed
    /// `Fsm` (with no initial state set yet) and returns the initial state. Intended for FSMs whose
    /// state instances need to reference the enclosing FSM — for example, when a shared `Context`
    /// object must hold the FSM reference before any state singleton is created.
    ///
    /// The factory MUST NOT call [`dispatch`] or read [`current`] — the FSM is in a transiently
    /// uninitialized state during factory execution. It MAY store the FSM reference into a Context
    /// that future state handlers will use.
    ///
    /// Initial state's [`FsmState#onEntry`] is NOT invoked automatically, matching the behavior of
    /// the plain [`fsm(String, FsmState)`] overload.
    public static <S extends FsmState<S, E>, E> Fsm<S, E> fsm(String name,
                                                              Function<Fsm<S, E>, S> initialStateFactory) {
        return fsm(name, initialStateFactory, FsmObserver.noop());
    }

    public static <S extends FsmState<S, E>, E> Fsm<S, E> fsm(String name,
                                                              Function<Fsm<S, E>, S> initialStateFactory,
                                                              FsmObserver<S, E> observer) {
        var fsm = new Fsm<S, E>(name, observer);
        fsm.currentState.set(initialStateFactory.apply(fsm));
        return fsm;
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
