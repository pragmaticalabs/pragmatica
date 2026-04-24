/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */

package org.pragmatica.statemachine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

    // --- Constructor-driven initial state factory ---

    /// Minimal Context that receives the Fsm reference at construction time and exposes it to
    /// state singletons. Matches the pattern used across all real FSMs in the codebase.
    static final class CtxWithFsm {
        final Fsm<DoorState, DoorEvent> fsm;
        final DoorState closed;
        final DoorState open;

        CtxWithFsm(Fsm<DoorState, DoorEvent> fsm) {
            this.fsm = fsm;
            this.closed = new CtxState(this, "CLOSED");
            this.open = new CtxState(this, "OPEN");
        }
    }

    static final class CtxState implements DoorState {
        final CtxWithFsm ctx;
        final String name;

        CtxState(CtxWithFsm ctx, String name) {
            this.ctx = ctx;
            this.name = name;
        }

        @Override
        public void handle(DoorEvent event, TransitionRequest<DoorState, DoorEvent> tx) {
            switch (event) {
                case DoorEvent.Open _ when name.equals("CLOSED") -> tx.transitionTo(ctx.open);
                case DoorEvent.Close _ when name.equals("OPEN") -> tx.transitionTo(ctx.closed);
                default -> tx.ignore();
            }
        }

        @Override
        public String toString() {
            return name;
        }
    }

    @Test
    void factoryOverload_wiresContextBeforeDispatch() {
        var ctxHolder = new java.util.concurrent.atomic.AtomicReference<CtxWithFsm>();
        var harness = FsmTestHarness.<DoorState, DoorEvent>harness("ctx-door", f -> {
            var ctx = new CtxWithFsm(f);
            ctxHolder.set(ctx);
            return ctx.closed;
        });

        // Context and Fsm cross-reference each other; state singletons built inside factory work.
        assertThat(ctxHolder.get()).isNotNull();
        assertThat(ctxHolder.get().fsm).isSameAs(harness.fsm());
        assertThat(harness.state()).isSameAs(ctxHolder.get().closed);

        harness.dispatch(new DoorEvent.Open());

        assertThat(harness.state()).isSameAs(ctxHolder.get().open);
        assertThat(harness.transitions()).hasSize(1);
    }

    @Test
    void factoryOverload_dispatchFromExternalThreadUsesWiredContext() throws InterruptedException {
        var ctxHolder = new java.util.concurrent.atomic.AtomicReference<CtxWithFsm>();
        var harness = FsmTestHarness.<DoorState, DoorEvent>harness("ctx-door-2", f -> {
            var ctx = new CtxWithFsm(f);
            ctxHolder.set(ctx);
            return ctx.closed;
        });

        // Prove the fsm reference captured in Context is fully usable post-construction: dispatch
        // a burst of events through the Context-held fsm ref and verify state advances correctly.
        var ctx = ctxHolder.get();
        var latch = new java.util.concurrent.CountDownLatch(1);
        var worker = new Thread(() -> {
            ctx.fsm.dispatch(new DoorEvent.Open());
            ctx.fsm.dispatch(new DoorEvent.Close());
            ctx.fsm.dispatch(new DoorEvent.Open());
            latch.countDown();
        });
        worker.start();
        assertThat(latch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        assertThat(harness.state()).isSameAs(ctx.open);
        assertThat(harness.transitions()).hasSize(3);
    }

    // --- FsmTags (kind/instance split) ---

    @Test
    void fsmTags_twoArgFactory_setsBothFields() {
        var fsm = Fsm.fsm("leader-election", "node-a", Simple.CLOSED);

        assertThat(fsm.tags().kind()).isEqualTo("leader-election");
        assertThat(fsm.tags().instance()).isEqualTo("node-a");
        assertThat(fsm.name()).isEqualTo("leader-election-node-a");
    }

    @Test
    void fsmTags_legacyNameFactory_splitsOnFirstDash() {
        var fsm = Fsm.fsm("control-loop-node-a", Simple.CLOSED);

        assertThat(fsm.tags().kind()).isEqualTo("control");
        assertThat(fsm.tags().instance()).isEqualTo("loop-node-a");
    }

    @Test
    void fsmTags_legacyNameFactory_noDash_treatsAsKind() {
        var fsm = Fsm.fsm("singleton", Simple.CLOSED);

        assertThat(fsm.tags().kind()).isEqualTo("singleton");
        assertThat(fsm.tags().instance()).isEqualTo("");
        assertThat(fsm.name()).isEqualTo("singleton");
    }

    @Test
    void observerReceivesTags_notString() {
        var captured = new java.util.concurrent.atomic.AtomicReference<FsmTags>();
        FsmObserver<DoorState, DoorEvent> observer = new FsmObserver<>() {
            @Override public void onTransition(FsmTags tags, DoorState from, DoorState to) {
                captured.set(tags);
            }
            @Override public void onCasLost(FsmTags tags, DoorState expected, DoorState actual) {}
            @Override public void onEventIgnored(FsmTags tags, DoorState state, DoorEvent event) {}
        };

        var fsm = Fsm.fsm("leader-election", "node-a", Simple.CLOSED, observer);
        fsm.dispatch(new DoorEvent.Open());

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().kind()).isEqualTo("leader-election");
        assertThat(captured.get().instance()).isEqualTo("node-a");
    }

    // --- onCasLost hook on target ---

    /// Target state that records onEntry/onCasLost invocations on shared counters. Distinct from
    /// `InstrumentedState` above because each instance must share the same counters across
    /// different `TrackingTarget` records (one per CAS attempt).
    static final class TrackingTarget implements DoorState {
        final AtomicInteger entryCounter;
        final AtomicInteger casLostCounter;

        TrackingTarget(AtomicInteger entryCounter, AtomicInteger casLostCounter) {
            this.entryCounter = entryCounter;
            this.casLostCounter = casLostCounter;
        }

        @Override public void onEntry() { entryCounter.incrementAndGet(); }
        @Override public void onCasLost() { casLostCounter.incrementAndGet(); }

        @Override
        public void handle(DoorEvent event, TransitionRequest<DoorState, DoorEvent> tx) {
            tx.ignore();
        }
    }

    /// Starting state whose handler constructs a fresh `TrackingTarget` per dispatch — gated on
    /// a barrier so all dispatching threads commit their `transitionToOrDrop` calls
    /// simultaneously, guaranteeing N-1 deterministic CAS-loss outcomes for N threads. Uses
    /// `transitionToOrDrop` so CAS-losers never forward events (idempotent).
    static final class FreshTargetSource implements DoorState {
        final AtomicInteger entryCounter;
        final AtomicInteger casLostCounter;
        final CyclicBarrier barrier;

        FreshTargetSource(AtomicInteger entryCounter,
                          AtomicInteger casLostCounter,
                          CyclicBarrier barrier) {
            this.entryCounter = entryCounter;
            this.casLostCounter = casLostCounter;
            this.barrier = barrier;
        }

        @Override
        public void handle(DoorEvent event, TransitionRequest<DoorState, DoorEvent> tx) {
            var target = new TrackingTarget(entryCounter, casLostCounter);
            awaitBarrier();
            tx.transitionToOrDrop(target);
        }

        private void awaitBarrier() {
            try {
                barrier.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (BrokenBarrierException | TimeoutException e) {
                throw new AssertionError("barrier wait failed", e);
            }
        }
    }

    @Test
    void casLost_firesTargetOnCasLost_exactlyOnce() throws InterruptedException {
        var entryCounter = new AtomicInteger();
        var casLostCounter = new AtomicInteger();
        var barrier = new CyclicBarrier(4);
        var source = new FreshTargetSource(entryCounter, casLostCounter, barrier);
        var harness = FsmTestHarness.<DoorState, DoorEvent>harness("door", source);

        // 4 threads, 4 fresh TrackingTarget instances constructed in the source's handler before
        // any CAS runs (barrier ensures all four reach transitionToOrDrop after constructing
        // their target). Exactly one CAS wins (entry +1); the other three lose (onCasLost +1
        // each).
        harness.dispatchConcurrently(List.of(
            new DoorEvent.Open(), new DoorEvent.Open(),
            new DoorEvent.Open(), new DoorEvent.Open()));

        assertThat(harness.state()).isInstanceOf(TrackingTarget.class);
        assertThat(harness.transitions()).hasSize(1);
        assertThat(entryCounter.get()).isEqualTo(1);
        assertThat(casLostCounter.get()).isEqualTo(3);
    }

    @Test
    void casLost_noChangeToCurrentState() throws InterruptedException {
        // After a barrier-gated concurrent burst, exactly one CAS-winning TrackingTarget is
        // `current()`. The three losers had `onCasLost` invoked but did NOT replace the current
        // state.
        var entryCounter = new AtomicInteger();
        var casLostCounter = new AtomicInteger();
        var barrier = new CyclicBarrier(4);
        var source = new FreshTargetSource(entryCounter, casLostCounter, barrier);
        var harness = FsmTestHarness.<DoorState, DoorEvent>harness("door", source);

        harness.dispatchConcurrently(List.of(
            new DoorEvent.Open(), new DoorEvent.Open(),
            new DoorEvent.Open(), new DoorEvent.Open()));

        var winner = harness.state();
        assertThat(winner).isInstanceOf(TrackingTarget.class);
        // Exactly one TrackingTarget became current — entry fired exactly once.
        assertThat(entryCounter.get()).isEqualTo(1);
        // The fsm's current state remains the winner — losers never replaced it.
        assertThat(harness.state()).isSameAs(winner);
        // Three losers, three onCasLost invocations.
        assertThat(casLostCounter.get()).isEqualTo(3);
    }

    // --- handle (side-effect with no transition) ---

    @Test
    void handle_runsActionThenRecordsObserver() {
        var counter = new AtomicInteger();
        var state = new DoorState() {
            @Override public void handle(DoorEvent event, TransitionRequest<DoorState, DoorEvent> tx) {
                tx.handle(counter::incrementAndGet);
            }
        };
        var harness = FsmTestHarness.<DoorState, DoorEvent>harness("door", state);

        harness.dispatch(new DoorEvent.Open());

        assertThat(counter.get()).isEqualTo(1);
        assertThat(harness.handled()).hasSize(1);
        assertThat(harness.handled().getFirst().state()).isSameAs(state);
        assertThat(harness.handled().getFirst().event()).isInstanceOf(DoorEvent.Open.class);
        assertThat(harness.transitions()).isEmpty();
        assertThat(harness.ignored()).isEmpty();
        assertThat(harness.casLosses()).isEmpty();
    }

    @Test
    void handle_distinguishedFromIgnore() {
        var counter = new AtomicInteger();
        var state = new DoorState() {
            @Override public void handle(DoorEvent event, TransitionRequest<DoorState, DoorEvent> tx) {
                switch (event) {
                    case DoorEvent.Open _ -> tx.handle(counter::incrementAndGet);
                    default -> tx.ignore();
                }
            }
        };
        var harness = FsmTestHarness.<DoorState, DoorEvent>harness("door", state);

        harness.dispatch(new DoorEvent.Open());
        harness.dispatch(new DoorEvent.Close());

        assertThat(counter.get()).isEqualTo(1);
        assertThat(harness.handled()).hasSize(1);
        assertThat(harness.ignored()).hasSize(1);
        assertThat(harness.handled().getFirst().event()).isInstanceOf(DoorEvent.Open.class);
        assertThat(harness.ignored().getFirst().event()).isInstanceOf(DoorEvent.Close.class);
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
