/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package org.pragmatica.statemachine;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/// #3846 K20: a [TransitionRequest] is built for one dispatch frame, yet nothing stops a handler from keeping it and a
/// foreign thread from calling it after the frame is gone. Each row states what happens TODAY: the ENABLED tripwire
/// asserts today's behaviour and names its lane in the failure message; the `@Disabled` twin asserts the rule (the
/// call throws and the state is unchanged). The rule's ERROR line and `frameBreaches` count are lane S's: neither
/// exists at this head, so lane S adds them to the twin's assertions.
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class TransitionRequestFrameTest {
    enum Ev { KEEP, GO }

    /// A toy FSM with two states. `kept` is the request the idle state's handler keeps on `KEEP` (it ignores the event,
    /// so the state stays); `GO` moves idle to active inside its own frame.
    static final class Toy {
        final AtomicReference<TransitionRequest<St, Ev>> kept = new AtomicReference<>();
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
            }
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

    /// ENABLED tripwire (flipped in lane S): a request kept from an earlier frame and called from a foreign thread acts:
    /// nothing throws and the state moves.
    @Test
    void k20_keptRequestCalledFromAForeignThread_atHead_movesTheState() {
        var toy = keptByIdle();

        var thrown = onForeignThread(() -> toy.kept.get().transitionTo(toy.active));

        assertThat(thrown).as("K20 no longer acts: lane S landed the frame check. Enable "
                              + "k20_keptRequestCalledFromAForeignThread_underInvE_throwsAndTheStateIsUnchanged "
                              + "and delete this method")
                          .isNull();
        assertThat(toy.fsm.current()).as("K20 no longer acts: the state did not move").isSameAs(toy.active);
    }

    /// The rule, `@Disabled` until lane S: the call from outside the frame throws and the state is unchanged.
    @Disabled("lane S: enable when TransitionRequest methods check their dispatch frame (K20)")
    @Test
    void k20_keptRequestCalledFromAForeignThread_underInvE_throwsAndTheStateIsUnchanged() {
        var toy = keptByIdle();

        var thrown = onForeignThread(() -> toy.kept.get().transitionTo(toy.active));

        assertThat(thrown).as("the call from outside the frame throws").isNotNull();
        assertThat(toy.fsm.current()).as("the state is unchanged").isSameAs(toy.idle);
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
