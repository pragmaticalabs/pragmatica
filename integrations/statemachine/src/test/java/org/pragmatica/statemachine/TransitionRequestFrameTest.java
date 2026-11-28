/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package org.pragmatica.statemachine;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.lang.utils.OwnerLifetime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/// #3846 K20: a [TransitionRequest] is built for one dispatch frame, and a handler may keep it. The rule (lane S): every
/// method of it checks, at entry and before any effect, that the calling thread is inside the dispatch that built it;
/// a call from a foreign thread or after the dispatch returned logs one ERROR line, counts in `frameBreaches` and
/// throws [IllegalStateException], and the state is unchanged. The control keeps the rule from being "always throw".
/// The ERROR line itself is not asserted: statemachine tests carry no log binding, so the count is its observable
/// twin (both are written in the same method, `Fsm#frameBreach`).
class TransitionRequestFrameTest {
    enum Ev { KEEP, GO, USE }

    /// A toy FSM with two states. `kept` is the request the idle state's handler keeps on `KEEP` (it ignores the event,
    /// so the state stays); `GO` moves idle to active inside its own frame.
    static final class Toy {
        final AtomicReference<TransitionRequest<St, Ev>> kept = new AtomicReference<>();
        final AtomicReference<Throwable> thrownInLaterFrame = new AtomicReference<>();
        final St idle = new St(this, "IDLE");
        final St active = new St(this, "ACTIVE");
        final Fsm<St, Ev> fsm = Fsm.fsm("toy", idle);
    }

    record St(Toy toy, String name) implements FsmState<St, Ev> {
        @Override
        public void handle(Ev event, TransitionRequest<St, Ev> tx) {
            switch (event) {
                case KEEP -> keepAndIgnore(tx);
                case GO -> tx.transitionTo(toy.active);
                case USE -> useKeptRequest();
            }
        }

        /// A handler of a LATER dispatch (its own frame) calls the request kept from an earlier one.
        private void useKeptRequest() {
            toy.thrownInLaterFrame.set(catchThrowable(() -> toy.kept.get().transitionTo(toy.active)));
        }

        private void keepAndIgnore(TransitionRequest<St, Ev> tx) {
            toy.kept.set(tx);
            tx.ignore();
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /// Control, ENABLED at every head: a request used inside its own frame acts, so a rule that throws for every call
    /// cannot satisfy the twin.
    @Test
    void k20Control_requestUsedInsideItsFrame_movesTheState() {
        var toy = new Toy();

        toy.fsm.dispatch(Ev.GO);

        assertThat(toy.fsm.current()).isSameAs(toy.active);
    }

    /// The rule: the call from a foreign thread throws, is counted once, and the state is unchanged (K20).
    @Test
    void k20_keptRequestCalledFromAForeignThread_underInvE_throwsAndTheStateIsUnchanged() {
        var toy = keptByIdle();

        var thrown = onForeignThread(() -> toy.kept.get().transitionTo(toy.active));

        assertThat(thrown).as("the call from outside the frame throws").isInstanceOf(IllegalStateException.class);
        assertThat(toy.fsm.current()).as("the state is unchanged").isSameAs(toy.idle);
        assertThat(toy.fsm.frameBreachesForTest()).as("the breach is counted once").isEqualTo(1);
    }

    /// The same thread, after the dispatch returned, is outside the frame too: confinement is to the dispatch, not to the thread.
    @Test
    void keptRequestCalledOnTheDispatchingThreadAfterTheDispatchReturned_throwsAndTheStateIsUnchanged() {
        var toy = keptByIdle();

        var thrown = catchThrowable(() -> toy.kept.get().transitionTo(toy.active));

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(toy.fsm.current()).isSameAs(toy.idle);
        assertThat(toy.fsm.frameBreachesForTest()).isEqualTo(1);
    }

    /// A request kept from one dispatch and called inside a LATER dispatch on the same thread belongs to a frame that is
    /// not the current one: refused. (A check that only asked "is any frame open" would let this through.)
    @Test
    void keptRequestCalledInsideALaterDispatch_throwsAndTheStateIsUnchanged() {
        var toy = keptByIdle();

        toy.fsm.dispatch(Ev.USE);

        assertThat(toy.thrownInLaterFrame.get()).isInstanceOf(IllegalStateException.class);
        assertThat(toy.fsm.current()).isSameAs(toy.idle);
        assertThat(toy.fsm.frameBreachesForTest()).isEqualTo(1);
    }

    /// Every public method refuses before any effect: each of the seven is called once from outside the frame; seven
    /// breaches, no `handle` action run, no transition, no observer record.
    @Test
    void everyPublicMethod_outsideTheFrame_isRefusedBeforeAnyEffect() {
        var toy = keptByIdle();
        var tx = toy.kept.get();
        var actionRan = new AtomicBoolean(false);
        var calls = List.<Runnable>of(() -> tx.transitionTo(toy.active),
                                      () -> tx.transitionTo(toy.active, () -> actionRan.set(true)),
                                      () -> tx.transitionToOrDrop(toy.active),
                                      () -> tx.ignore(),
                                      () -> tx.handle(() -> actionRan.set(true)),
                                      () -> tx.lifetime(),
                                      () -> tx.fromState());

        calls.forEach(call -> assertThat(catchThrowable(call::run)).isInstanceOf(IllegalStateException.class));
        assertThat(catchThrowable(tx::event)).isInstanceOf(IllegalStateException.class);

        assertThat(toy.fsm.frameBreachesForTest()).as("eight calls, eight breaches").isEqualTo(8);
        assertThat(actionRan).as("no action ran").isFalse();
        assertThat(toy.fsm.current()).as("no transition").isSameAs(toy.idle);
    }

    /// A nested dispatch restores the enclosing frame: the handler's request is valid after the nested dispatch returned,
    /// and in-frame `lifetime()` is the lifetime of the entry the dispatch observed.
    @Test
    void requestUsedAfterANestedDispatchReturned_stillActsAndKeepsTheObservedLifetime() {
        var probe = new Probe();
        var fsm = Fsm.<Probe, Ev>fsm("probe", probe);

        probe.fsm = fsm;
        fsm.dispatch(Ev.GO);

        assertThat(probe.afterNested).as("the outer request acted after the nested dispatch").isTrue();
        assertThat(probe.lifetime).as("lifetime of the observed entry").isSameAs(fsm.entered().lifetime());
        assertThat(fsm.frameBreachesForTest()).isZero();
    }

    /// `GO` dispatches `KEEP` to itself from inside its handler, then uses its own request.
    static final class Probe implements FsmState<Probe, Ev> {
        Fsm<Probe, Ev> fsm;
        boolean afterNested;
        OwnerLifetime lifetime;

        @Override
        public void handle(Ev event, TransitionRequest<Probe, Ev> tx) {
            switch (event) {
                case KEEP, USE -> tx.ignore();
                case GO -> nestThenUse(tx);
            }
        }

        private void nestThenUse(TransitionRequest<Probe, Ev> tx) {
            fsm.dispatch(Ev.KEEP);
            lifetime = tx.lifetime();
            tx.ignore();
            afterNested = true;
        }
    }

    /// An FSM in `IDLE` whose handler has kept the request of a `KEEP` dispatch that has returned.
    private static Toy keptByIdle() {
        var toy = new Toy();

        toy.fsm.dispatch(Ev.KEEP);
        assertThat(toy.kept.get()).as("the instrument: the handler kept the request").isNotNull();
        assertThat(toy.fsm.current()).as("the instrument: KEEP leaves the state in place").isSameAs(toy.idle);
        return toy;
    }

    /// Runs `action` on a thread that is no dispatch's: what it throws, or null when it returns.
    private static Throwable onForeignThread(Runnable action) {
        var thrown = new AtomicReference<Throwable>();
        var thread = new Thread(() -> thrown.set(catchThrowable(action::run)), "k20-foreign");

        thread.start();
        joinOrFail(thread);
        return thrown.get();
    }

    private static void joinOrFail(Thread thread) {
        try {
            thread.join(TimeUnit.SECONDS.toMillis(10));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for the foreign thread", e);
        }
        assertThat(thread.isAlive()).as("the foreign thread finished").isFalse();
    }
}
