/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package org.pragmatica.statemachine;

import java.util.Queue;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.utils.OwnerLifetime;


/// Per-dispatch transition-request handle passed to [`FsmState#handle`]. Captures the FSM, the
/// state the dispatcher observed when it called `handle` together with the lifetime of that entry, the dispatch's
/// frame, and the triggering event. State code calls one of the `transitionTo*` methods to request a transition;
/// the CAS, the `onExit` / transition action / `onEntry` sequence, and the observer callback all
/// run inside the library.
///
/// Instances are single-use — each dispatch builds a fresh one — and confined to that dispatch: every method checks
/// first that the calling thread is inside the dispatch that built the instance, and throws
/// [IllegalStateException] (after one ERROR line and a count, see [`Fsm#requireFrame`]) otherwise.
public final class TransitionRequest<S extends FsmState<S, E>, E> {
    private final Fsm<S, E> fsm;
    private final Fsm.Entered<S> entered;
    private final Queue<E> frame;
    private final E event;

    TransitionRequest(Fsm<S, E> fsm, Fsm.Entered<S> entered, Queue<E> frame, E event) {
        this.fsm = fsm;
        this.entered = entered;
        this.frame = frame;
        this.event = event;
    }

    public S fromState() {
        fsm.requireFrame(frame);

        return entered.state();
    }

    /// The lifetime of the entry into [#fromState()] that this dispatch observed: it ends when a successor's CAS wins.
    public OwnerLifetime lifetime() {
        fsm.requireFrame(frame);

        return entered.lifetime();
    }

    public E event() {
        fsm.requireFrame(frame);

        return event;
    }

    /// Request a transition to `target` with no transition-specific action.
    ///
    /// On CAS success: `fromState.onExit()` → `target.onEntry()` → observer.onTransition.
    /// On CAS loss (another thread advanced the state first): the event is forwarded to the new
    /// current state via `fsm.dispatch(event)`. This is a tail call; the effective retry loop is
    /// bounded by the FSM's transition graph.
    @Contract
    public void transitionTo(S target) {
        transitionTo(target, NO_ACTION);
    }

    /// Request a transition to `target`, running `transitionAction` between `onExit` and `onEntry`.
    /// CAS-loss behaviour is identical to [`#transitionTo(FsmState)`].
    @Contract
    public void transitionTo(S target, Runnable transitionAction) {
        fsm.requireFrame(frame);
        if (!fsm.tryAdvance(entered.state(), target, transitionAction)) {
            fsm.dispatch(event);
        }
    }

    /// Request a transition but abandon silently if the CAS fails (no forward-to-current retry).
    /// Use for idempotent / self-regenerating events such as timer ticks: the new state's own
    /// timer will re-fire, so losing a stale tick is correct.
    @Contract
    public void transitionToOrDrop(S target) {
        transitionToOrDrop(target, NO_ACTION);
    }

    @Contract
    public void transitionToOrDrop(S target, Runnable transitionAction) {
        fsm.requireFrame(frame);
        fsm.tryAdvance(entered.state(), target, transitionAction);
    }

    /// Explicitly mark the event as ignored by this state. Records through the observer; no
    /// state change. Use for intentionally-dropped branches (idempotent replays, stale events,
    /// default fall-through) when you want them visible in metrics.
    @Contract
    public void ignore() {
        fsm.requireFrame(frame);
        fsm.recordIgnored(entered.state(), event);
    }

    /// Handle the event via a side effect without changing state. Distinct from `ignore` —
    /// `handle` says "the event was consumed at the side-effect layer; no transition needed".
    /// Generates `fsm.events.handled` observability instead of `fsm.events.ignored`.
    ///
    /// The action MUST NOT dispatch events (reentrancy) and MUST NOT throw (JBCT).
    @Contract
    public void handle(Runnable action) {
        fsm.requireFrame(frame);
        action.run();
        fsm.recordHandled(entered.state(), event);
    }

    private static final Runnable NO_ACTION = () -> {};
}
