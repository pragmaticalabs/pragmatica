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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.utils.OwnerLifetime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.utils.OwnerLifetime.ownerLifetime;


/// GoF-style state machine with CAS-guarded transitions. Dispatch runs on the caller thread; no
/// executor, no queue. Concurrent dispatches race on `AtomicReference.compareAndSet` over the
/// state reference: the CAS decides WHICH transition wins, and only the winner runs the transition action and
/// entry/exit hooks. Nothing serialises the hooks of two successive winners: a winner's `onEntry` may still be
/// running when the next winner's `onExit` starts on another thread.
///
/// What bounds an effect to its state is the state's lifetime, not hook ordering. Every entered state owns one
/// [OwnerLifetime]; the winner of a CAS ends the predecessor's lifetime before it runs the predecessor's `onExit`.
/// A hook that arms a timer arms it through the lifetime it is handed ([`FsmState#onEntry(OwnerLifetime)`]); a
/// registration made after that state has been left is refused (nothing is enqueued, the handle comes back
/// cancelled), whatever thread order the hooks ran in. A resource armed by a state's `onEntry` therefore never
/// outlives that state's exit. A resource the hook keeps outside the lifetime (a raw handle in a field) has no such
/// bound.
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
/// A [TransitionRequest] belongs to the dispatch frame that built it: every method of it checks, before any effect, that the
/// calling thread's current frame is that frame. A call from another thread, or after the dispatch returned, is refused
/// (see [#requireFrame]). A nested dispatch restores the enclosing frame, so a handler may use its request after a nested
/// dispatch returns.
///
/// State identity is by reference. Data-free states must be singletons; data-carrying states
/// are typically immutable records created fresh per transition. See [`FsmState`] for details.
///
/// @param <S> The sealed state hierarchy.
/// @param <E> The event type accepted by the FSM.
public final class Fsm<S extends FsmState<S, E>, E> {
    private static final Logger log = LoggerFactory.getLogger(Fsm.class);

    private final FsmTags tags;
    private final AtomicReference<Entered<S>> currentState;
    private final FsmObserver<S, E> observer;
    private final ThreadLocal<Option<Queue<E>>> followUps = ThreadLocal.withInitial(Option::none);
    private final AtomicLong frameBreaches = new AtomicLong(0);

    private Fsm(FsmTags tags, FsmObserver<S, E> observer) {
        this.tags = tags;
        this.currentState = new AtomicReference<>();
        this.observer = observer;
    }

    private Fsm(FsmTags tags, S initial, FsmObserver<S, E> observer) {
        this(tags, observer);
        this.currentState.set(Entered.entered(initial));
    }

    /// A state together with the lifetime that bounds what its entry armed. The pair is replaced as one value by every
    /// transition, so a reader never sees a state with the lifetime of another entry.
    ///
    /// @param state the state reference
    /// @param lifetime the lifetime of this entry into `state`; ended when a successor's CAS wins
    public record Entered<S>(S state, OwnerLifetime lifetime) {
        static <S> Entered<S> entered(S state) {
            return new Entered<>(state, ownerLifetime());
        }
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

        fsm.currentState.set(Entered.entered(initialStateFactory.apply(fsm)));

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
        return currentState.get()
                           .state();
    }

    /// The current state with the lifetime of its entry, read as one value. Code that arms a timer outside a hook
    /// (a registry callback, say) arms it through this lifetime and checks eligibility against this state, never
    /// against a separate [#current()] read.
    public Entered<S> entered() {
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
            var entered = currentState.get();

            entered.state().handle(event, new TransitionRequest<>(this, entered, pending, event));
        } finally {
            followUps.set(enclosing);
        }
    }

    /// Refuse a call of a [TransitionRequest] made outside the dispatch that built it. GUARANTEE: a request acts only while
    /// its own frame is the calling thread's current frame, so a request kept past its dispatch, or handed to another
    /// thread, changes nothing. MECHANISM: `frame` is the queue of the dispatch that built the request; the calling thread's
    /// current frame must be that same queue. Otherwise this logs one ERROR line naming the rule and the calling thread,
    /// counts it ([#frameBreachesForTest]) and throws [IllegalStateException]. A throw on a scheduler thread is swallowed by
    /// the scheduler, so the ERROR line is the operator's signal.
    @Contract
    void requireFrame(Queue<E> frame) {
        if (!inFrame(frame)) {
            throw frameBreach();
        }
    }

    private boolean inFrame(Queue<E> frame) {
        return followUps.get()
                        .filter(current -> current == frame)
                        .isPresent();
    }

    private IllegalStateException frameBreach() {
        var message = "TransitionRequest used outside the dispatch that built it: FSM " + name()
                    + ", called from thread " + callingThread();

        frameBreaches.incrementAndGet();
        log.error(message);

        return new IllegalStateException(message);
    }

    /// The caller named usefully: `#id`, plus `'name'` when the name is non-empty (a virtual thread's is empty).
    private static String callingThread() {
        var thread = Thread.currentThread();
        var name = thread.getName();

        return name.isEmpty()
               ? "#" + thread.threadId()
               : "#" + thread.threadId() + " '" + name + "'";
    }

    /// Test seam: how many calls [#requireFrame] refused.
    long frameBreachesForTest() {
        return frameBreaches.get();
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
    /// Action ordering on CAS success: `end(from's lifetime) -> onExit(from) -> transitionAction -> onEntry(target) ->
    /// observer.onTransition`. The lifetime ends first, before any hook: from that moment nothing can be armed
    /// against `from`, including by a hook of an earlier winner that has not yet run.
    boolean tryAdvance(S expected, S target, Runnable transitionAction) {
        var next = Entered.entered(target);

        return replaceExpected(expected, next).fold(() -> casLost(expected, target),
                                                    left -> advanced(left, next, transitionAction));
    }

    private boolean casLost(S expected, S target) {
        observer.onCasLost(tags, expected, current());
        target.onCasLost();

        return false;
    }

    private boolean advanced(Entered<S> left, Entered<S> next, Runnable transitionAction) {
        var expected = left.state();
        var target = next.state();

        left.lifetime().end();
        expected.onExit();
        transitionAction.run();
        target.onEntry(next.lifetime());
        observer.onTransition(tags, expected, target);

        return true;
    }

    /// The CAS of the state reference, which is by identity of the STATE: a state re-entered since the dispatcher read
    /// it (a data-free singleton) still matches, as it always did. The pair's CAS can fail only because another
    /// transition replaced it, so the read is repeated.
    private Option<Entered<S>> replaceExpected(S expected, Entered<S> next) {
        for (var seen = currentState.get(); seen.state() == expected; seen = currentState.get()) {
            if (currentState.compareAndSet(seen, next)) {
                return Option.some(seen);
            }
        }

        return Option.none();
    }
}
