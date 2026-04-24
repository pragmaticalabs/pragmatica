/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */

package org.pragmatica.statemachine;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/// Test utility for FSMs built with [`Fsm`]. Wraps an `Fsm` with a recording observer and exposes
/// assertions over the observed transitions, CAS losses, and ignored events.
///
/// Published as a `test-jar` from `integrations/statemachine`: downstream modules add
/// `<classifier>tests</classifier>` on the dependency to consume it.
public final class FsmTestHarness<S extends FsmState<S, E>, E> {
    private final Fsm<S, E> fsm;
    private final RecordingObserver<S, E> observer;

    private FsmTestHarness(Fsm<S, E> fsm, RecordingObserver<S, E> observer) {
        this.fsm = fsm;
        this.observer = observer;
    }

    public static <S extends FsmState<S, E>, E> FsmTestHarness<S, E> harness(String name, S initial) {
        var observer = new RecordingObserver<S, E>();
        var fsm = Fsm.fsm(name, initial, observer);
        return new FsmTestHarness<>(fsm, observer);
    }

    public static <S extends FsmState<S, E>, E> FsmTestHarness<S, E> harness(String name,
                                                                             Function<Fsm<S, E>, S> initialStateFactory) {
        var observer = new RecordingObserver<S, E>();
        var fsm = Fsm.fsm(name, initialStateFactory, observer);
        return new FsmTestHarness<>(fsm, observer);
    }

    public Fsm<S, E> fsm() {
        return fsm;
    }

    public S state() {
        return fsm.current();
    }

    public void dispatch(E event) {
        fsm.dispatch(event);
    }

    /// Dispatch multiple events concurrently to exercise CAS contention. Returns after all
    /// dispatching threads have completed.
    public void dispatchConcurrently(List<E> events) throws InterruptedException {
        var ready = new CountDownLatch(events.size());
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(events.size());
        var threads = new ArrayList<Thread>(events.size());
        for (var event : events) {
            var thread = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    fsm.dispatch(event);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            threads.add(thread);
            thread.start();
        }
        ready.await();
        start.countDown();
        if (!done.await(10, TimeUnit.SECONDS)) {
            throw new AssertionError("dispatchConcurrently timed out");
        }
    }

    public List<Transition<S>> transitions() {
        return observer.transitions();
    }

    public List<CasLoss<S>> casLosses() {
        return observer.casLosses();
    }

    public List<Ignored<S, E>> ignored() {
        return observer.ignored();
    }

    public List<Handled<S, E>> handled() {
        return observer.handled();
    }

    public record Transition<S>(S from, S to) {}

    public record CasLoss<S>(S expected, S actual) {}

    public record Ignored<S, E>(S state, E event) {}

    public record Handled<S, E>(S state, E event) {}

    private static final class RecordingObserver<S, E> implements FsmObserver<S, E> {
        private final List<Transition<S>> transitions = new CopyOnWriteArrayList<>();
        private final List<CasLoss<S>> casLosses = new CopyOnWriteArrayList<>();
        private final List<Ignored<S, E>> ignored = new CopyOnWriteArrayList<>();
        private final List<Handled<S, E>> handled = new CopyOnWriteArrayList<>();

        @Override
        public void onTransition(FsmTags tags, S from, S to) {
            transitions.add(new Transition<>(from, to));
        }

        @Override
        public void onCasLost(FsmTags tags, S expected, S actual) {
            casLosses.add(new CasLoss<>(expected, actual));
        }

        @Override
        public void onEventIgnored(FsmTags tags, S state, E event) {
            ignored.add(new Ignored<>(state, event));
        }

        @Override
        public void onHandled(FsmTags tags, S state, E event) {
            handled.add(new Handled<>(state, event));
        }

        List<Transition<S>> transitions() {
            return List.copyOf(transitions);
        }

        List<CasLoss<S>> casLosses() {
            return List.copyOf(casLosses);
        }

        List<Ignored<S, E>> ignored() {
            return List.copyOf(ignored);
        }

        List<Handled<S, E>> handled() {
            return List.copyOf(handled);
        }
    }
}
