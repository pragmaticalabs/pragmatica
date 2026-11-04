// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.utility.react;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.lang.Unit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// One component's serial react executor (KVStore dispatcher design §3.5 R5, §3.5.1 "Executors", §6.1).
///
/// A KV notification handler keeps its state step in absorb (on the dispatcher) and hands only its side-effect call
/// here, with every value the call reads captured at absorb (U3). Tasks of one executor run one at a time, in
/// submission order, so a call and its undo keep the order absorb gave them (U1).
///
/// - [#react] submits a **side-effect** reaction: when it runs, the [ReactGate] is read; if it is closed the reaction
///   is dropped, counted in [#droppedEngineStopped], and logged once per component per closed period.
/// - [#undo] submits an **undo** reaction (a cancel, a deactivate): never gated, so an engine stop cannot drop a cancel
///   and leak what it would have cancelled (U1).
///
/// Queues are unbounded (owner ruling 2); [#queueDepth] and [#highWater] measure them (ruling 7: no alarm, no overflow
/// policy). Every reaction runs once per submission (ruling 4: no coalescing). A `RuntimeException` from a reaction is
/// logged and isolated to that reaction; an `Error` goes to the [ReactFailureSink], which stops the node (ruling 6).
///
/// Guarantee per operation: `react`/`undo` never block the caller (unbounded queue) and return before the reaction
/// runs, except for an [#inline] executor, which runs the reaction on the caller's thread. After [#close], a submission
/// is refused: a refused side effect is counted as dropped, a refused undo runs on the caller's thread (an undo is never
/// lost to a stop).
public interface ReactExecutor {
    Unit react(String reaction, Runnable call);
    Unit undo(String reaction, Runnable call);
    String component();
    /// Reactions submitted and not yet started.
    long queueDepth();
    /// The largest [#queueDepth] observed since construction.
    long highWater();
    /// Side-effect reactions dropped because the gate was closed when they ran (or the executor was closed).
    long droppedEngineStopped();
    Unit close();

    static ReactExecutor reactExecutor(String component, ReactGate gate, ReactFailureSink failureSink) {
        return new Serial(component,
                          gate,
                          failureSink,
                          Executors.newSingleThreadExecutor(Thread.ofPlatform()
                                                                  .daemon()
                                                                  .name("react-" + component)
                                                                  .factory()),
                          new Counters());
    }

    /// Runs every reaction on the caller's thread, ungated: the behaviour a component had before D1-react, kept for
    /// hosts that wire no engine (component unit tests, standalone use). A node wires [#reactExecutor].
    static ReactExecutor inline(String component) {
        return new Inline(component);
    }

    final class Counters {
        final AtomicLong depth = new AtomicLong();
        final AtomicLong highWater = new AtomicLong();
        final AtomicLong dropped = new AtomicLong();
        final AtomicBoolean dropLogged = new AtomicBoolean();
    }

    record Serial(String component,
                  ReactGate gate,
                  ReactFailureSink failureSink,
                  ExecutorService executor,
                  Counters counters) implements ReactExecutor {
        private static final Logger log = LoggerFactory.getLogger(ReactExecutor.class);

        @Override
        public Unit react(String reaction, Runnable call) {
            return submit(reaction, () -> runGated(reaction, call), () -> dropRefused(reaction));
        }

        @Override
        public Unit undo(String reaction, Runnable call) {
            return submit(reaction, () -> runGuarded(reaction, call), () -> runGuarded(reaction, call));
        }

        @Override
        public long queueDepth() {
            return counters.depth.get();
        }

        @Override
        public long highWater() {
            return counters.highWater.get();
        }

        @Override
        public long droppedEngineStopped() {
            return counters.dropped.get();
        }

        @Override
        public Unit close() {
            executor.shutdown();

            return Unit.unit();
        }

        /// The one boundary where the JDK executor's refusal is converted: a closed executor refuses the task, and
        /// the refusal runs `onRefused` instead (count a side effect as dropped, run an undo inline).
        @SuppressWarnings("JBCT-EX-03")
        private Unit submit(String reaction, Runnable task, Runnable onRefused) {
            raiseDepth();
            try {
                executor.execute(() -> started(task));
            } catch (RejectedExecutionException refused) {
                counters.depth.decrementAndGet();
                log.debug("React executor {} closed; reaction {} refused", component, reaction);
                onRefused.run();
            }

            return Unit.unit();
        }

        private void raiseDepth() {
            var depth = counters.depth.incrementAndGet();

            counters.highWater.accumulateAndGet(depth, Math::max);
        }

        private void started(Runnable task) {
            counters.depth.decrementAndGet();
            task.run();
        }

        private void runGated(String reaction, Runnable call) {
            if (gate.isOpen()) {
                counters.dropLogged.set(false);
                runGuarded(reaction, call);
            } else {
                drop(reaction);
            }
        }

        private void dropRefused(String reaction) {
            drop(reaction);
        }

        private void drop(String reaction) {
            counters.dropped.incrementAndGet();
            if (counters.dropLogged.compareAndSet(false, true)) {
                log.warn("Engine stopped: react executor {} drops side-effect reactions (first dropped: {})",
                         component,
                         reaction);
            }
        }

        /// §6.1 react wrapper: a `RuntimeException` is logged and isolated per reaction; any other `Throwable` (an
        /// `Error`) goes to the failure sink, which stops the node.
        @SuppressWarnings("JBCT-EX-03")
        private void runGuarded(String reaction, Runnable call) {
            try {
                call.run();
            } catch (RuntimeException e) {
                log.warn("React executor {}: reaction {} failed", component, reaction, e);
            } catch (Throwable fatal) {
                failureSink.fatal(component, reaction, fatal);
            }
        }
    }

    record Inline(String component) implements ReactExecutor {
        @Override
        public Unit react(String reaction, Runnable call) {
            call.run();

            return Unit.unit();
        }

        @Override
        public Unit undo(String reaction, Runnable call) {
            call.run();

            return Unit.unit();
        }

        @Override
        public long queueDepth() {
            return 0;
        }

        @Override
        public long highWater() {
            return 0;
        }

        @Override
        public long droppedEngineStopped() {
            return 0;
        }

        @Override
        public Unit close() {
            return Unit.unit();
        }
    }
}
