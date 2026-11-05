// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.state.kvstore;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.FatalEscape;
import org.pragmatica.lang.FatalEscapeHandler;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.concurrent.TimeUnit.MILLISECONDS;


/// The bridge from core's process-wide fatal-escape hook (KVStore dispatcher design §8.1) to a [DispatcherFailureSink]:
/// every `Error` that escapes a continuation, on any task, stops the node that owns the sink, exactly as a failure of
/// the dispatcher itself does. A host installs it once, first thing in its entry point, with
/// `Promise.installFatalEscapeHandler(FatalEscapeRelay.fatalEscapeRelay(sink))`; `sink` should be the same
/// [DispatcherFailureSink#once] instance the node's store gets, so the hook and the store stop the node once between them.
///
/// The escaping thread may be deep in a `StackOverflowError` or short of heap, so the handler does almost nothing there:
/// it hands the escape to a **pre-started** platform thread, which runs the sink on a fresh stack, and the escaping thread
/// parks until the sink returns (bounded: a production sink halts the JVM, an in-JVM sink returns quickly).
public interface FatalEscapeRelay {
    /// Reason the sink is called with, for the hook path (the store's own failure path says `kv-dispatcher-failed`).
    String REASON = "fatal-escape";
    /// How long the escaping thread waits for the sink; a halting sink never lets the wait run out.
    long PARK_BOUND_MILLIS = 15_000L;

    /// A relay that stops the node through `sink`. Starts its relay thread now, before any escape can need it.
    static FatalEscapeHandler fatalEscapeRelay(DispatcherFailureSink sink) {
        return Relay.relay(sink);
    }

    record Handoff(FatalEscape escape, CountDownLatch done) {
        static Handoff handoff(FatalEscape escape) {
            return new Handoff(escape, new CountDownLatch(1));
        }
    }

    record Relay(DispatcherFailureSink sink, BlockingQueue<Handoff> queue) implements FatalEscapeHandler {
        private static final Logger log = LoggerFactory.getLogger(FatalEscapeRelay.class);

        static Relay relay(DispatcherFailureSink sink) {
            var relay = new Relay(sink, new LinkedBlockingQueue<>());

            relay.start();

            return relay;
        }

        @Override
        public Unit onFatalEscape(FatalEscape escape) {
            var handoff = Handoff.handoff(escape);

            return queue.offer(handoff)
                   ? park(handoff.done())
                   : Unit.unit();
        }

        @Contract
        private void start() {
            Thread.ofPlatform().daemon().name("kv-fatal-escape-relay").start(this::serve);
        }

        @Contract
        private void serve() {
            while (take().map(this::stopThroughSink).isSuccess()) {
                continue;
            }
        }

        private Result<Handoff> take() {
            return Result.lift(Causes::fromThrowable, queue::take);
        }

        // FER: a sink that throws leaves nothing further to do for this escape (core logged it at ERROR, the sink's own
        // failure is logged here); the parked thread is released by `onResultRun` either way, and the loop keeps serving.
        private Unit stopThroughSink(Handoff handoff) {
            return Result.lift(Causes::fromThrowable,
                               () -> sink.stopNode(REASON,
                                                   handoff.escape().error()))
                         .onFailure(cause -> log.error("The fatal-escape sink failed: {}",
                                                       cause.message()))
                         .onResultRun(handoff.done()::countDown)
                         .or(Unit.unit());
        }

        // FER: an interrupted or timed-out park only releases the escaping thread, whose escape core already logged and
        // will propagate as it does today; the relay thread still runs the sink.
        private static Unit park(CountDownLatch done) {
            return Result.lift(Causes::fromThrowable,
                               () -> done.await(PARK_BOUND_MILLIS, MILLISECONDS))
                         .mapToUnit()
                         .or(Unit.unit());
        }
    }
}
