/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */

package org.pragmatica.statemachine;

/// Per-dispatch transition-request handle passed to [`FsmState#handle`]. Captures the FSM, the
/// `from` state reference (the state the dispatcher observed when it called `handle`), and the
/// triggering event. State code calls one of the `transitionTo*` methods to request a transition;
/// the CAS, the `onExit` / transition action / `onEntry` sequence, and the observer callback all
/// run inside the library.
///
/// Instances are single-use — each dispatch builds a fresh one.
public final class TransitionRequest<S extends FsmState<S, E>, E> {
    private final Fsm<S, E> fsm;
    private final S fromState;
    private final E event;

    TransitionRequest(Fsm<S, E> fsm, S fromState, E event) {
        this.fsm = fsm;
        this.fromState = fromState;
        this.event = event;
    }

    public S fromState() {
        return fromState;
    }

    public E event() {
        return event;
    }

    /// Request a transition to `target` with no transition-specific action.
    ///
    /// On CAS success: `fromState.onExit()` → `target.onEntry()` → observer.onTransition.
    /// On CAS loss (another thread advanced the state first): the event is forwarded to the new
    /// current state via `fsm.dispatch(event)`. This is a tail call; the effective retry loop is
    /// bounded by the FSM's transition graph.
    public void transitionTo(S target) {
        transitionTo(target, NO_ACTION);
    }

    /// Request a transition to `target`, running `transitionAction` between `onExit` and `onEntry`.
    /// CAS-loss behaviour is identical to [`#transitionTo(FsmState)`].
    public void transitionTo(S target, Runnable transitionAction) {
        if (!fsm.tryAdvance(fromState, target, transitionAction)) {
            fsm.dispatch(event);
        }
    }

    /// Request a transition but abandon silently if the CAS fails (no forward-to-current retry).
    /// Use for idempotent / self-regenerating events such as timer ticks: the new state's own
    /// timer will re-fire, so losing a stale tick is correct.
    public void transitionToOrDrop(S target) {
        transitionToOrDrop(target, NO_ACTION);
    }

    public void transitionToOrDrop(S target, Runnable transitionAction) {
        fsm.tryAdvance(fromState, target, transitionAction);
    }

    /// Explicitly mark the event as ignored by this state. Records through the observer; no
    /// state change. Use for intentionally-dropped branches (idempotent replays, stale events,
    /// default fall-through) when you want them visible in metrics.
    public void ignore() {
        fsm.recordIgnored(fromState, event);
    }

    /// Handle the event via a side effect without changing state. Distinct from `ignore` —
    /// `handle` says "the event was consumed at the side-effect layer; no transition needed".
    /// Generates `fsm.events.handled` observability instead of `fsm.events.ignored`.
    ///
    /// The action MUST NOT dispatch events (reentrancy) and MUST NOT throw (JBCT).
    public void handle(Runnable action) {
        action.run();
        fsm.recordHandled(fromState, event);
    }

    private static final Runnable NO_ACTION = () -> {};
}
