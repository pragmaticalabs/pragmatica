/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package org.pragmatica.statemachine;

import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;


/// GoF-style state machine with CAS-guarded transitions. Dispatch runs on the caller thread; no
/// executor, no queue. Concurrent dispatches are serialized via `AtomicReference.compareAndSet`
/// on the state reference: only the thread that wins the CAS runs the transition action and
/// entry/exit hooks.
///
/// A hook ([`FsmState#onEntry`], [`FsmState#onExit`]) never dispatches; a follow-up event it decides goes through
/// [`#followUp`]. GUARANTEE: a dispatch returns only after every follow-up queued by its completed transition is
/// dispatched, after that transition's observer callback, FIFO among that dispatch's follow-ups, each one a full
/// dispatch step (its own follow-ups are delivered before the next sibling's). Within a follow-up chain, a follow-up
/// whose dispatch throws stops neither its later siblings nor the rest of any enclosing follow-up frame: the drain
/// continues, and once the queue is empty the first [Throwable] is rethrown (a checked one, which only a sneaky throw
/// raises, wrapped in [UndeclaredThrowableException]), with every later distinct one added to it as suppressed. A
/// dispatch nested directly in a handler is a call that handler makes: its drain rethrows the first follow-up throwable
/// to the handler. A handler that throws, including one that lets such a rethrow propagate, delivers none of its own
/// frame's follow-ups. Handlers are total by contract, so a
/// throw is a contract breach whose only obligation is that every frame is restored: the thread's frame is the enclosing
/// one again, and later dispatches are unaffected. A [VirtualMachineError] is never deferred: it propagates at once,
/// after the frames are restored, no later follow-up runs, and a follow-up failure recorded before it is not attached
/// to it. MECHANISM: every [`#dispatch`] opens a follow-up frame for the calling thread, restores the enclosing frame
/// when its handler returns or throws, then drains its own frame only if the handler returned; the drain catches each
/// follow-up's throwable per step, a [VirtualMachineError] excepted. Code that runs after that dispatch (the rest of
/// the caller's handler or task) therefore sees the follow-up's outcome, exactly as a nested dispatch inside the hook
/// would have shown it, and observers see the transitions in the order they happen.
///
/// State identity is by reference. Data-free states must be singletons; data-carrying states
/// are typically immutable records created fresh per transition. See [`FsmState`] for details.
///
/// @param <S> The sealed state hierarchy.
/// @param <E> The event type accepted by the FSM.
public final class Fsm<S extends FsmState<S, E>, E> {
    private final FsmTags tags;
    private final AtomicReference<S> currentState;
    private final FsmObserver<S, E> observer;
    private final ThreadLocal<Option<Queue<E>>> followUps = ThreadLocal.withInitial(Option::none);

    private Fsm(FsmTags tags, FsmObserver<S, E> observer) {
        this.tags = tags;
        this.currentState = new AtomicReference<>();
        this.observer = observer;
    }

    private Fsm(FsmTags tags, S initial, FsmObserver<S, E> observer) {
        this(tags, observer);
        this.currentState.set(initial);
    }

    public static <S extends FsmState<S, E>, E> Fsm<S, E> fsm(String kind, String instance, S initial) {
        return fsm(kind, instance, initial, FsmObserver.noop());
    }

    public static <S extends FsmState<S, E>, E> Fsm<S, E> fsm(String kind,
                                                              String instance,
                                                              S initial,
                                                              FsmObserver<S, E> observer) {
        return new Fsm<>(new FsmTags(kind, instance), initial, observer);
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
    /// the plain [`fsm(String, String, FsmState)`] overload.
    public static <S extends FsmState<S, E>, E> Fsm<S, E> fsm(String kind,
                                                              String instance,
                                                              Function<Fsm<S, E>, S> initialStateFactory) {
        return fsm(kind, instance, initialStateFactory, FsmObserver.noop());
    }

    public static <S extends FsmState<S, E>, E> Fsm<S, E> fsm(String kind,
                                                              String instance,
                                                              Function<Fsm<S, E>, S> initialStateFactory,
                                                              FsmObserver<S, E> observer) {
        var fsm = new Fsm<S, E>(new FsmTags(kind, instance), observer);

        fsm.currentState.set(initialStateFactory.apply(fsm));

        return fsm;
    }

    /// Legacy single-name factory. Parses `name` via [`FsmTags#fromLegacyName`] and delegates to
    /// the two-arg form. Kept for back-compat with callers that have not yet been migrated.
    public static <S extends FsmState<S, E>, E> Fsm<S, E> fsm(String name, S initial) {
        var parsed = FsmTags.fromLegacyName(name);

        return fsm(parsed.kind(), parsed.instance(), initial, FsmObserver.noop());
    }

    /// Legacy single-name factory. See [`#fsm(String, FsmState)`].
    public static <S extends FsmState<S, E>, E> Fsm<S, E> fsm(String name, S initial, FsmObserver<S, E> observer) {
        var parsed = FsmTags.fromLegacyName(name);

        return fsm(parsed.kind(), parsed.instance(), initial, observer);
    }

    /// Legacy single-name factory. See [`#fsm(String, String, Function)`].
    public static <S extends FsmState<S, E>, E> Fsm<S, E> fsm(String name, Function<Fsm<S, E>, S> initialStateFactory) {
        var parsed = FsmTags.fromLegacyName(name);

        return fsm(parsed.kind(), parsed.instance(), initialStateFactory, FsmObserver.noop());
    }

    /// Legacy single-name factory. See [`#fsm(String, String, Function, FsmObserver)`].
    public static <S extends FsmState<S, E>, E> Fsm<S, E> fsm(String name,
                                                              Function<Fsm<S, E>, S> initialStateFactory,
                                                              FsmObserver<S, E> observer) {
        var parsed = FsmTags.fromLegacyName(name);

        return fsm(parsed.kind(), parsed.instance(), initialStateFactory, observer);
    }

    public FsmTags tags() {
        return tags;
    }

    public String name() {
        return tags.displayName();
    }

    public S current() {
        return currentState.get();
    }

    /// Dispatch an event to the current state. The current state's [`FsmState#handle`] method
    /// decides whether to request a transition; the CAS happens inside
    /// [`TransitionRequest#transitionTo`].
    ///
    /// The follow-ups the transition's hooks decided ([`#followUp`]) are dispatched before this returns; a throwing
    /// follow-up surfaces here only after the rest are dispatched, a [VirtualMachineError] at once (see the class
    /// GUARANTEE). Called from inside a handler, this is the handler's call: the throwable surfaces in the handler.
    @Contract
    public void dispatch(E event) {
        Queue<E> pending = new ArrayDeque<>();

        handleInFrame(event, pending);
        drain(pending);
    }

    /// Deliver `event` as the follow-up of the transition whose hook is running: queued in the frame of the innermost
    /// dispatch on this thread and dispatched when that dispatch returns (see the class GUARANTEE). Called outside any
    /// dispatch (a hook invoked directly, as tests do), it is dispatched at once.
    @Contract
    public void followUp(E event) {
        followUps.get().onPresent(pending -> pending.add(event)).onEmpty(() -> dispatch(event));
    }

    /// The handler runs with `pending` as this thread's frame; the enclosing frame comes back on every exit. A handler
    /// that throws delivers none of its follow-ups: the exception propagates out of [`#dispatch`] before the drain, so
    /// a half-run transition starts no further steps (the caller decides what a failed dispatch means).
    private void handleInFrame(E event, Queue<E> pending) {
        var enclosing = followUps.get();

        followUps.set(Option.some(pending));
        try {
            var state = currentState.get();

            state.handle(event, new TransitionRequest<>(this, state, event));
        } finally {
            followUps.set(enclosing);
        }
    }

    /// Dispatch every queued follow-up in FIFO order, each to completion, a throwing one included: the first throwable
    /// is rethrown once the queue is empty, later ones suppressed into it (the #3605 ruling: one failed follow-up never
    /// drops the events queued after it; the caller still sees that a step failed). A [VirtualMachineError] leaves at
    /// once and the rest of the queue is discarded (#3605 round-4 ruling).
    private void drain(Queue<E> pending) {
        Option<Throwable> failure = Option.none();

        for (var next = pending.poll(); next != null; next = pending.poll()) {
            failure = dispatchRecordingFailure(next, failure);
        }

        failure.onPresent(Fsm::rethrow);
    }

    /// One drain step: dispatch `event`; a throwable becomes the first failure, or is suppressed into the earlier one.
    /// A [VirtualMachineError] is rethrown unchanged at once: the dispatch that raised it already restored its frame,
    /// and nothing is allocated on its path (the earlier failure, if any, is not attached).
    @SuppressWarnings("JBCT-EX-01")  // #3605 ruling: a throwing follow-up never stops the drain; a VM error does, at once
    private Option<Throwable> dispatchRecordingFailure(E event, Option<Throwable> failure) {
        try {
            dispatch(event);

            return failure;
        } catch (VirtualMachineError vmError) {
            throw vmError;
        } catch (Throwable thrown) {
            return Option.some(firstWithLaterSuppressed(failure, thrown));
        }
    }

    private static Throwable firstWithLaterSuppressed(Option<Throwable> first, Throwable later) {
        return first.onPresent(earlier -> suppressInto(earlier, later))
                    .or(later);
    }

    /// The same instance thrown twice (a shared exception) is kept once: [Throwable#addSuppressed] refuses
    /// self-suppression with an exception that would escape the drain and drop the follow-ups after it.
    private static void suppressInto(Throwable earlier, Throwable later) {
        if (earlier != later) {
            earlier.addSuppressed(later);
        }
    }

    /// Rethrow `failure` unchanged when unchecked. A checked one (only a sneaky throw raises it: [`#dispatch`] declares
    /// none) is wrapped in [UndeclaredThrowableException], the JDK's carrier for an undeclared checked throwable.
    @SuppressWarnings("JBCT-EX-01")  // #3605 ruling: the first throwable surfaces after the drain
    private static void rethrow(Throwable failure) {
        switch (failure) {
            case RuntimeException unchecked -> throw unchecked;
            case Error error -> throw error;
            default -> throw new UndeclaredThrowableException(failure);
        }
    }

    /// Package-private helper invoked by [`TransitionRequest#ignore`].
    void recordIgnored(S state, E event) {
        observer.onEventIgnored(tags, state, event);
    }

    /// Package-private helper invoked by [`TransitionRequest#handle`].
    void recordHandled(S state, E event) {
        observer.onHandled(tags, state, event);
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
            observer.onCasLost(tags, expected, currentState.get());
            target.onCasLost();

            return false;
        }

        expected.onExit();
        transitionAction.run();
        target.onEntry();
        observer.onTransition(tags, expected, target);

        return true;
    }
}
