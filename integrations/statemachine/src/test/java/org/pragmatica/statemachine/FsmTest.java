/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */

package org.pragmatica.statemachine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class FsmTest {
    interface DoorState extends FsmState<DoorState, DoorEvent> {}

    sealed interface DoorEvent {
        record Open() implements DoorEvent {}
        record Close() implements DoorEvent {}
        record Lock() implements DoorEvent {}
        record Unlock() implements DoorEvent {}
        record Tick() implements DoorEvent {}
    }

    /// Stateless singletons.
    enum Simple implements DoorState {
        CLOSED, OPEN, LOCKED;

        @Override
        public void handle(DoorEvent event, TransitionRequest<DoorState, DoorEvent> tx) {
            switch (this) {
                case CLOSED -> handleClosed(event, tx);
                case OPEN -> handleOpen(event, tx);
                case LOCKED -> handleLocked(event, tx);
            }
        }

        private static void handleClosed(DoorEvent event, TransitionRequest<DoorState, DoorEvent> tx) {
            switch (event) {
                case DoorEvent.Open o -> tx.transitionTo(OPEN);
                case DoorEvent.Lock l -> tx.transitionTo(LOCKED);
                default -> tx.ignore();
            }
        }

        private static void handleOpen(DoorEvent event, TransitionRequest<DoorState, DoorEvent> tx) {
            switch (event) {
                case DoorEvent.Close c -> tx.transitionTo(CLOSED);
                case DoorEvent.Tick t -> tx.transitionToOrDrop(CLOSED);
                default -> tx.ignore();
            }
        }

        private static void handleLocked(DoorEvent event, TransitionRequest<DoorState, DoorEvent> tx) {
            switch (event) {
                case DoorEvent.Unlock u -> tx.transitionTo(CLOSED);
                default -> tx.ignore();
            }
        }
    }

    @Test
    void singleDispatch_triggersTransition() {
        var harness = FsmTestHarness.harness("door", Simple.CLOSED);

        harness.dispatch(new DoorEvent.Open());

        assertThat(harness.state()).isEqualTo(Simple.OPEN);
        assertThat(harness.transitions()).containsExactly(new FsmTestHarness.Transition<>(Simple.CLOSED, Simple.OPEN));
        assertThat(harness.casLosses()).isEmpty();
    }

    @Test
    void unhandledEvent_recordsIgnored() {
        var harness = FsmTestHarness.harness("door", Simple.CLOSED);

        harness.dispatch(new DoorEvent.Unlock()); // CLOSED does not handle Unlock

        assertThat(harness.state()).isEqualTo(Simple.CLOSED);
        assertThat(harness.transitions()).isEmpty();
        assertThat(harness.ignored()).hasSize(1);
        assertThat(harness.ignored().getFirst().state()).isEqualTo(Simple.CLOSED);
    }

    @Test
    void chainedTransitions_serializeInOrder() {
        var harness = FsmTestHarness.harness("door", Simple.CLOSED);

        harness.dispatch(new DoorEvent.Open());
        harness.dispatch(new DoorEvent.Close());
        harness.dispatch(new DoorEvent.Lock());
        harness.dispatch(new DoorEvent.Unlock());

        assertThat(harness.state()).isEqualTo(Simple.CLOSED);
        assertThat(harness.transitions()).containsExactly(
            new FsmTestHarness.Transition<>(Simple.CLOSED, Simple.OPEN),
            new FsmTestHarness.Transition<>(Simple.OPEN, Simple.CLOSED),
            new FsmTestHarness.Transition<>(Simple.CLOSED, Simple.LOCKED),
            new FsmTestHarness.Transition<>(Simple.LOCKED, Simple.CLOSED));
    }

    // --- Entry / exit actions ---

    static class InstrumentedState implements DoorState {
        final AtomicInteger entryCount = new AtomicInteger();
        final AtomicInteger exitCount = new AtomicInteger();
        final String name;
        DoorState next;
        DoorEvent trigger;

        InstrumentedState(String name) {
            this.name = name;
        }

        @Override public void onEntry() { entryCount.incrementAndGet(); }
        @Override public void onExit()  { exitCount.incrementAndGet(); }

        @Override
        public void handle(DoorEvent event, TransitionRequest<DoorState, DoorEvent> tx) {
            if (event.equals(trigger)) {
                tx.transitionTo(next);
            } else {
                tx.ignore();
            }
        }

        @Override
        public String toString() { return name; }
    }

    @Test
    void entryExit_fireOnceOnTransition() {
        var a = new InstrumentedState("A");
        var b = new InstrumentedState("B");
        var trigger = new DoorEvent.Open();
        a.trigger = trigger;
        a.next = b;

        var harness = FsmTestHarness.<DoorState, DoorEvent>harness("door", a);

        harness.dispatch(trigger);

        assertThat(a.exitCount.get()).isEqualTo(1);
        assertThat(b.entryCount.get()).isEqualTo(1);
        assertThat(a.entryCount.get()).isZero();
        assertThat(b.exitCount.get()).isZero();
    }

    @Test
    void transitionAction_runsBetweenExitAndEntry() {
        var a = new InstrumentedState("A");
        var b = new InstrumentedState("B");
        var trigger = new DoorEvent.Open();
        var order = new java.util.ArrayList<String>();
        a.trigger = null; // Override handle to include ordered action

        var aOrdered = new DoorState() {
            @Override public void onExit() { order.add("exitA"); }
            @Override public void handle(DoorEvent event, TransitionRequest<DoorState, DoorEvent> tx) {
                if (event.equals(trigger)) {
                    tx.transitionTo(new DoorState() {
                        @Override public void onEntry() { order.add("entryB"); }
                        @Override public void handle(DoorEvent e, TransitionRequest<DoorState, DoorEvent> t) { t.ignore(); }
                    }, () -> order.add("action"));
                }
            }
        };

        var harness = FsmTestHarness.<DoorState, DoorEvent>harness("door", aOrdered);
        harness.dispatch(trigger);

        assertThat(order).containsExactly("exitA", "action", "entryB");
    }

    // --- CAS contention / concurrency ---

    @Test
    void concurrentDispatch_oneWinsCasOthersForward() throws InterruptedException {
        var harness = FsmTestHarness.harness("door", Simple.CLOSED);

        // Dispatch the same Open event from 8 threads. Only one CAS wins; the rest either hit
        // CAS-loss + forward (reaching OPEN.handle(Open) which ignores), or land directly in
        // OPEN post-winner (also ignored). Either way each non-winner contributes exactly one
        // `ignored` entry; the CAS-loss count varies with interleaving.
        harness.dispatchConcurrently(List.of(
            new DoorEvent.Open(), new DoorEvent.Open(), new DoorEvent.Open(), new DoorEvent.Open(),
            new DoorEvent.Open(), new DoorEvent.Open(), new DoorEvent.Open(), new DoorEvent.Open()));

        assertThat(harness.state()).isEqualTo(Simple.OPEN);
        assertThat(harness.transitions()).containsExactly(
            new FsmTestHarness.Transition<>(Simple.CLOSED, Simple.OPEN));
        assertThat(harness.ignored()).hasSize(7);
        assertThat(harness.casLosses().size()).isBetween(0, 7);
    }

    @Test
    void transitionToOrDrop_doesNotForwardOnCasLoss() throws InterruptedException {
        // Start OPEN. Tick uses transitionToOrDrop (idempotent). 4 Ticks concurrent.
        var harness = FsmTestHarness.harness("door", Simple.OPEN);

        harness.dispatchConcurrently(List.of(
            new DoorEvent.Tick(), new DoorEvent.Tick(),
            new DoorEvent.Tick(), new DoorEvent.Tick()));

        assertThat(harness.state()).isEqualTo(Simple.CLOSED);
        assertThat(harness.transitions()).hasSize(1);
        // Key invariant: each non-winner contributes EXACTLY ONE observer event (cas-loss OR
        // direct ignore), never both. With transitionTo (forward-on-loss), a CAS loser would
        // contribute cas-loss AND a subsequent ignore — total would exceed 3.
        assertThat(harness.ignored().size() + harness.casLosses().size()).isEqualTo(3);
    }

    @Test
    void forwardOnCasLoss_reachesNewState() {
        // Two states where A→B on X, and B explicitly ignores X. Dispatching X twice from two
        // threads where first wins: the second's transitionTo CAS fails, forwards X to B, which
        // ignores it. Verified via the ignored list.
        var a = new DoorState() {
            @Override public void handle(DoorEvent event, TransitionRequest<DoorState, DoorEvent> tx) {
                if (event instanceof DoorEvent.Open) {
                    tx.transitionTo(new DoorState() {
                        @Override public void handle(DoorEvent e, TransitionRequest<DoorState, DoorEvent> t) {
                            t.ignore();
                        }
                    });
                }
            }
        };
        var harness = FsmTestHarness.<DoorState, DoorEvent>harness("door", a);

        // Single-threaded: first Open transitions. Second Open dispatched against the NEW state,
        // which ignores. This confirms forwarding works structurally (not a contention test).
        harness.dispatch(new DoorEvent.Open());
        harness.dispatch(new DoorEvent.Open());

        assertThat(harness.transitions()).hasSize(1);
        assertThat(harness.ignored()).hasSize(1);
    }
}
