// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.wiring;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.concurrent.locks.LockSupport;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// The mailbox of one deployment FSM instance (phase-3 design 3.1, spec R-3.1.1): an unbounded multi-producer queue and **one**
/// dedicated consumer thread. A producer only enqueues; the handler (the interpreter's `step`-and-dispatch) runs on the consumer thread
/// and nowhere else, so no producer ever steps the FSM or blocks behind it.
///
/// **Unbounded, and why.** Dropping an input loses a fact, and blocking a producer stalls consensus (the KV producer is the apply thread
/// holding the store's mutation lock, #2143's `SLOW-APPLY`). So `offer` never blocks and never drops; the bound is upstream, in the commit
/// rate.
///
/// **Coalescing.** Only an input the caller names is coalesced, by the rule *drop the older, append the newer*
/// ([#offerCoalescing]): a newer sample never moves ahead of an input enqueued before it (review M1: a sample that already lacks X must
/// not overtake `NodeRemoved(X)`).
///
/// **Failure of the handler.** The consumer loop outlives a failing handler: the failure is logged and the next item is taken. A mailbox
/// whose consumer died on one bad input would leave the FSM deaf, with every producer still enqueueing.
public final class FsmMailbox<I> {
    private static final Logger log = LoggerFactory.getLogger(FsmMailbox.class);
    private static final long CLOSE_JOIN_MS = 5_000L;

    private final String name;
    private final Consumer<? super I> handler;
    private final Runnable onStop;
    private final ConcurrentLinkedDeque<I> queue = new ConcurrentLinkedDeque<>();
    private final AtomicInteger depth = new AtomicInteger();
    private final Object coalescing = new Object();
    private volatile Thread consumer;
    private volatile boolean closed;

    private FsmMailbox(String name, Consumer<? super I> handler, Runnable onStop) {
        this.name = name;
        this.handler = handler;
        this.onStop = onStop;
    }

    /// A mailbox whose consumer thread, once started, is called `name` and hands each item to `handler`.
    public static <I> FsmMailbox<I> fsmMailbox(String name, Consumer<? super I> handler) {
        return new FsmMailbox<>(name,
                                handler,
                                () -> {});
    }

    /// As [#fsmMailbox(String, Consumer)], and the consumer runs `onStop` as the last thing it does, after [#close()] ended its loop.
    public static <I> FsmMailbox<I> fsmMailbox(String name, Consumer<? super I> handler, Runnable onStop) {
        return new FsmMailbox<>(name, handler, onStop);
    }

    /// Starts the consumer thread: a daemon platform thread, so a node that is torn down without a `close()` cannot hold the JVM open.
    @Contract
    public synchronized void start() {
        if (Option.option(consumer).isEmpty() && !closed) {
            var thread = new Thread(this::run, name);

            thread.setDaemon(true);
            consumer = thread;
            thread.start();
        }
    }

    /// Appends `item`. Never blocks and never drops; `false` only after [#close()], when no consumer remains to take it.
    public boolean offer(I item) {
        if (closed) {
            return false;
        }

        depth.incrementAndGet();
        queue.addLast(item);
        wake();

        return true;
    }

    /// Appends `item` after removing every queued item `superseded` matches: *drop the older, append the newer*. The newer item
    /// therefore stays behind everything enqueued before it; replacing the older one in place would let it overtake them.
    public boolean offerCoalescing(I item, Predicate<? super I> superseded) {
        if (closed) {
            return false;
        }

        synchronized (coalescing) {
            depth.addAndGet(-removeSuperseded(superseded));
            depth.incrementAndGet();
            queue.addLast(item);
        }

        wake();

        return true;
    }

    /// Items waiting for the consumer: the `aether_deployment_fsm_mailbox_depth` gauge.
    public int depth() {
        return depth.get();
    }

    /// True while a consumer thread is alive and the mailbox is open: someone is there to take a `Close` request.
    public boolean running() {
        return ! closed && Option.option(consumer)
                                 .map(Thread::isAlive)
                                 .or(false);
    }

    /// True on the consumer thread (a confinement check for the state only that thread may touch).
    public boolean onConsumerThread() {
        return Thread.currentThread() == consumer;
    }

    /// Stops the consumer and refuses further items. Items still queued are dropped: after a `Close` nobody is left to step them.
    @Contract
    public void close() {
        closed = true;
        wake();
        Option.option(consumer).filter(thread -> thread != Thread.currentThread()).onPresent(this::awaitStopped);
    }

    private void awaitStopped(Thread thread) {
        Result.lift(Causes::fromThrowable,
                    () -> thread.join(CLOSE_JOIN_MS))
              .onFailure(cause -> log.warn("Interrupted while stopping {}: {}",
                                           name,
                                           cause.message()));
    }

    /// Removes the queued items `superseded` matches and counts the ones this call took out, so the depth gauge stays exact when the consumer
    /// polls an item concurrently.
    private int removeSuperseded(Predicate<? super I> superseded) {
        return (int) queue.stream()
                          .filter(superseded)
                          .toList()
                          .stream()
                          .filter(queue::removeFirstOccurrence)
                          .count();
    }

    private void wake() {
        Option.option(consumer).onPresent(LockSupport::unpark);
    }

    private void run() {
        while (!closed) {
            Option.option(queue.pollFirst()).onPresent(this::taken).onEmpty(this::idle);
        }

        onStop.run();
    }

    private void idle() {
        LockSupport.park(this);
    }

    private void taken(I item) {
        depth.decrementAndGet();
        handle(item);
    }

    /// FER (degrade forward): a handler that fails is logged and the loop takes the next item. The guarantee earned is that the consumer
    /// survives; the failed input itself is lost. The mechanism that repairs the loss is the FSMs' own level-based inputs (the periodic
    /// reconcile, the timers), which re-derive from state.
    private void handle(I item) {
        Result.lift(Causes::fromThrowable,
                    () -> handler.accept(item))
              .onFailure(cause -> log.error("{} failed on an input and continues: {}",
                                            name,
                                            cause.message()));
    }
}
