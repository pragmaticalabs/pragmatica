/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package org.pragmatica.statemachine;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.OwnerLifetime;

import static org.assertj.core.api.Assertions.assertThat;

/// #1191 at the Fsm level: a resource armed by a state's `onEntry` never outlives that state's exit, whatever order the
/// hooks of two successive winners run in. The CAS decides which transition wins; nothing serialises the winners'
/// hooks, so the first winner's `onEntry` can still be running when the second winner has already left the state and
/// run its own hooks. The Fsm enforces the bound through the state's lifetime: ended by the successor's CAS winner
/// before the predecessor's `onExit`, refusing every registration made after.
///
/// Every race here is driven, not awaited: the first winner's `onEntry` is parked on a latch, the second transition
/// runs to completion on the test thread, and only then is the hook released.
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class StateLifetimeOvertakeTest {
    private static final TimeSpan HOUR = TimeSpan.timeSpan(1).hours();

    enum Ev { GO, STOP, AGAIN }

    /// One test state. `armed` is what `onEntry` got back from its registration; `entryLife` and `exitSawEnded` record
    /// what the Fsm handed the hooks. `parkInEntry` (when set) holds `onEntry` BEFORE it arms, exactly where the
    /// reported interleaving has it.
    static final class St implements FsmState<St, Ev> {
        final String name;
        final AtomicReference<St> goTo = new AtomicReference<>();
        final AtomicReference<St> stopTo = new AtomicReference<>();
        final AtomicReference<OwnerLifetime> entryLife = new AtomicReference<>();
        final AtomicReference<OwnerLifetime.Handle> armed = new AtomicReference<>();
        final AtomicBoolean exitSawEnded = new AtomicBoolean(false);
        final AtomicBoolean exitRan = new AtomicBoolean(false);
        final CountDownLatch inEntry = new CountDownLatch(1);
        final AtomicReference<CountDownLatch> parkInEntry = new AtomicReference<>();
        final AtomicReference<Runnable> entryHook = new AtomicReference<>();
        final List<String> log;

        St(String name) {
            this(name, new CopyOnWriteArrayList<>());
        }

        St(String name, List<String> log) {
            this.name = name;
            this.log = log;
        }

        @Override
        public void onEntry(OwnerLifetime life) {
            entryLife.set(life);
            inEntry.countDown();
            awaitRelease();
            armed.set(life.schedule(_ -> {}, HOUR));
            runEntryHookOnce();
        }

        private void runEntryHookOnce() {
            var hook = entryHook.getAndSet(null);

            if (hook != null) {
                hook.run();
            }
        }

        private void awaitRelease() {
            var park = parkInEntry.get();

            if (park != null) {
                await(park);
            }
        }

        @Override
        public void onExit() {
            exitRan.set(true);
            exitSawEnded.set(entryLife.get() != null && entryLife.get().isEnded());
        }

        @Override
        public void handle(Ev event, TransitionRequest<St, Ev> tx) {
            log.add(name + ":" + event);
            switch (event) {
                case GO -> transition(tx, goTo.get());
                case STOP -> transition(tx, stopTo.get());
                case AGAIN -> tx.transitionTo(this);
            }
        }

        private static void transition(TransitionRequest<St, Ev> tx, St target) {
            if (target != null) {
                tx.transitionTo(target);
            } else {
                tx.ignore();
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(20, TimeUnit.SECONDS)).as("latch released in time").isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }

    /// The failure input of #1191. `retry` wins its CAS on a worker thread and parks before it arms; `stopped` wins next
    /// on the test thread and completes (exit of `retry`, entry of `stopped`); only then does `retry.onEntry` arm.
    @Test
    void entryThatArmsAfterTheNextWinnerLeftTheState_isRefusedAndCancelled() throws Exception {
        var begin = new St("begin");
        var retry = new St("retry");
        var stopped = new St("stopped");
        var release = new CountDownLatch(1);

        begin.goTo.set(retry);
        retry.stopTo.set(stopped);
        retry.parkInEntry.set(release);
        var fsm = Fsm.fsm("overtake", begin);
        var first = Thread.ofVirtual().start(() -> fsm.dispatch(Ev.GO));

        await(retry.inEntry);
        fsm.dispatch(Ev.STOP);
        assertThat(fsm.current()).as("precondition: the second winner has completed").isSameAs(stopped);
        assertThat(retry.armed.get()).as("precondition: the first winner's hook has not armed yet").isNull();
        release.countDown();
        first.join();

        var late = retry.armed.get();

        assertThat(late).as("onEntry did register, after the exit").isNotNull();
        assertThat(late.isCancelled()).as("#1191: a registration after the state's exit is refused (cancelled)").isTrue();
        assertThat(retry.entryLife.get().size()).as("#1191: nothing armed by the exited state is live").isZero();
    }

    /// The lifetime ends BEFORE the predecessor's `onExit`, so an `onExit` that runs while the first winner's `onEntry`
    /// is still pending already sees the state closed to registrations.
    @Test
    void predecessorLifetimeEnds_beforeItsOnExitRuns() {
        var begin = new St("begin");
        var next = new St("next");

        begin.goTo.set(next);
        var fsm = Fsm.fsm("order", begin);

        fsm.dispatch(Ev.GO);
        assertThat(begin.exitRan.get()).as("precondition: begin.onExit ran").isTrue();
        assertThat(next.entryLife.get().isEnded()).as("the successor's own lifetime is live").isFalse();

        next.stopTo.set(new St("end"));
        fsm.dispatch(Ev.STOP);
        assertThat(next.exitRan.get()).as("precondition: next.onExit ran").isTrue();
        assertThat(next.exitSawEnded.get()).as("#1191: the lifetime had ended when onExit ran").isTrue();
    }

    @Test
    void timerArmedInTime_isCancelledWhenTheStateIsLeft() {
        var begin = new St("begin");
        var live = new St("live");
        var end = new St("end");

        begin.goTo.set(live);
        live.stopTo.set(end);
        var fsm = Fsm.fsm("armed-in-time", begin);

        fsm.dispatch(Ev.GO);
        assertThat(live.armed.get().isCancelled()).as("precondition: armed and live while current").isFalse();
        assertThat(live.entryLife.get().size()).as("precondition: one live registration").isEqualTo(1);

        fsm.dispatch(Ev.STOP);

        assertThat(live.armed.get().isCancelled()).as("leaving the state cancels what its entry armed").isTrue();
        assertThat(live.entryLife.get().size()).isZero();
    }

    @Test
    void eachEntryOwnsAFreshLifetime_evenWhenTheSameSingletonIsReEntered() {
        var begin = new St("begin");
        var single = new St("single");

        begin.goTo.set(single);
        var fsm = Fsm.fsm("re-entry", begin);

        fsm.dispatch(Ev.GO);
        var first = single.entryLife.get();

        fsm.dispatch(Ev.AGAIN);
        var second = single.entryLife.get();

        assertThat(second).as("a new entry, a new lifetime").isNotSameAs(first);
        assertThat(first.isEnded()).as("the old entry's lifetime ended").isTrue();
        assertThat(second.isEnded()).as("the new entry's lifetime is live").isFalse();
        assertThat(fsm.entered().lifetime()).as("entered() pairs the state with its own entry's lifetime").isSameAs(second);
        assertThat(fsm.entered().state()).isSameAs(single);
    }

    @Test
    void casLoser_endsNothing() {
        var begin = new St("begin");
        var winner = new St("winner");
        var loserTarget = new St("loserTarget");

        begin.goTo.set(winner);
        var fsm = Fsm.fsm("loser", begin);

        fsm.dispatch(Ev.GO);
        var winnerLife = fsm.entered().lifetime();
        var advanced = fsm.tryAdvance(begin, loserTarget, () -> {});

        assertThat(advanced).as("precondition: the stale expectation loses the CAS").isFalse();
        assertThat(winnerLife.isEnded()).as("a CAS loss ends no lifetime: the winner's state is still current").isFalse();
        assertThat(fsm.entered().lifetime()).isSameAs(winnerLife);
        assertThat(loserTarget.entryLife.get()).as("a loser runs no hooks").isNull();
    }

    /// The re-entrant case the lifetime must not disturb: `onEntry` decides two follow-ups; the first re-enters the same
    /// singleton (a fresh entry, a fresh lifetime), the second leaves it. Both are still delivered, FIFO, by the one
    /// dispatch that ran the hook.
    @Test
    void followUpsDecidedInsideOnEntry_stillDrainFifo() {
        var log = new CopyOnWriteArrayList<String>();
        var begin = new St("begin", log);
        var entering = new St("entering", log);
        var stopped = new St("stopped", log);

        begin.goTo.set(entering);
        entering.stopTo.set(stopped);
        var fsm = Fsm.fsm("fifo", begin);

        entering.entryHook.set(() -> decideAgainThenStop(fsm));
        fsm.dispatch(Ev.GO);

        assertThat(log).as("GO, then the hook's follow-ups in the order decided").containsExactly("begin:GO",
                                                                                                    "entering:AGAIN",
                                                                                                    "entering:STOP");
        assertThat(fsm.current()).isSameAs(stopped);
    }

    private static void decideAgainThenStop(Fsm<St, Ev> fsm) {
        fsm.followUp(Ev.AGAIN);
        fsm.followUp(Ev.STOP);
    }
}
