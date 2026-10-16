// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.wiring;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// What the two deployment FSM interpreters share (phase-3 design 3): one mailbox, one consumer thread, the timer map that thread owns, the
/// revision watermark of the last snapshot, and the `Close` protocol. The interpreters compose; they decide nothing: every spec rule stays
/// in the pure FSMs (`M/`, `F2/`).
///
/// **Who runs what.** Producers only enqueue ([#deliver], [#deliverKv], [#requestEntry]); [#handle] runs on the consumer thread and
/// nowhere else. A completion callback (a settled write, a finished local op) enqueues a settlement and never calls `step`.
///
/// **The core drop rule (design 4.4).** A KV-sourced input is dropped if it is replay-flagged (a restore never runs while the engine is
/// ACTIVE, so the burst carries install-time values and would regress keys) or if its revision is at or below the revision of the last
/// snapshot (the snapshot already contains it). Both are counted `inputs_total{result=dropped_replay|dropped_revision}`.
abstract class FsmRuntime<I, K, A> {
    private static final Logger log = LoggerFactory.getLogger(FsmRuntime.class);
    private static final long CLOSE_WAIT_SECONDS = 5L;

    private final String name;
    private final FsmMailbox<FsmItem<I, K>> mailbox;
    private final FsmTimers<K, A> timers;
    private final AtomicBoolean shutDown = new AtomicBoolean();
    private final AtomicBoolean closeRequested = new AtomicBoolean();
    /// Counted down when the shutdown has run, by whoever ran it; every `close()` caller waits on this one completion.
    private final CountDownLatch stopped = new CountDownLatch(1);
    private long watermark;

    FsmRuntime(String threadName, FsmTimers.TimerService clock) {
        this.name = threadName;
        this.mailbox = FsmMailbox.fsmMailbox(threadName, this::handle, this::shutdown);
        this.timers = FsmTimers.fsmTimers(clock, tick -> offered(mailbox.offer(new FsmItem.Tick<>(tick)), "Timer"));
    }

    // -- the producers' side ----------------------------------------------------------------------------------------
    @Contract
    public void start() {
        mailbox.start();
    }

    /// Enqueues `input`. `false` only after [#close()].
    public boolean deliver(I input) {
        return offered(mailbox.offer(new FsmItem.Input<>(input)), inputName(input));
    }

    /// Enqueues `input` after removing every queued input `superseded` matches: *drop the older, append the newer* (design 3.1, review M1). The
    /// newer input stays behind everything enqueued before it, so a sample that already lacks a node never overtakes that node's removal.
    public boolean deliverCoalescing(I input, Predicate<I> superseded) {
        return offered(mailbox.offerCoalescing(new FsmItem.Input<>(input), item -> supersedes(item, superseded)),
                       inputName(input));
    }

    private static <I, K> boolean supersedes(FsmItem<I, K> item, Predicate<I> superseded) {
        return item instanceof FsmItem.Input<I, K> queued && superseded.test(queued.input());
    }

    /// Enqueues a KV-sourced `input` stamped by the delivering thread with `KVStore.notificationRevision()` and `KVStore.isReplaying()`.
    public boolean deliverKv(I input, long revision, boolean replay) {
        return offered(mailbox.offer(new FsmItem.Kv<>(input, revision, replay)), inputName(input));
    }

    /// Asks the consumer to enter: the consumer snapshots at a revision and steps the entry input, but only while not yet active.
    public boolean requestEntry(boolean reconcile) {
        return offered(mailbox.offer(new FsmItem.Entry<>(reconcile)), "EnterRequested");
    }

    /// Design 7 (2c): an offer after Close is discarded and counted `inputs_total{result=closed}`.
    private boolean offered(boolean accepted, String input) {
        if (!accepted) {
            count(input, "closed");
        }

        return accepted;
    }

    /// How many timers are armed; read on a quiescent interpreter (tests), since the map belongs to the consumer.
    int armedTimers() {
        return timers.size();
    }

    /// `aether_deployment_fsm_mailbox_depth{fsm}`.
    public int mailboxDepth() {
        return mailbox.depth();
    }

    /// `Close` only (design 7, 2c): cancels every timer and stops the consumer. A write in flight is failed by the engine at its own stop;
    /// its settlement arrives at a closed mailbox and is dropped, because no consumer remains to step it.
    @Contract
    public void close() {
        if (mailbox.onConsumerThread() || mailbox.retireIfConsumerless()) {
            shutdown();
        } else {
            requestShutdown();
        }

        mailbox.close();
    }

    // -- the consumer's side ----------------------------------------------------------------------------------------
    /// One item, on the consumer thread.
    private void handle(FsmItem<I, K> item) {
        switch (item) {
            case FsmItem.Input<I, K> input -> step(input.input());
            case FsmItem.Kv<I, K> kv -> keyValue(kv);
            case FsmItem.Tick<I, K> tick -> tick(tick.tick());
            case FsmItem.Entry<I, K> entry -> entry(entry.reconcile());
            case FsmItem.Close<I, K> close -> {
                shutdown();
                mailbox.close();
                close.done().countDown();
            }
        }
    }

    private void keyValue(FsmItem.Kv<I, K> kv) {
        if (kv.replay()) {
            count(inputName(kv.input()), "dropped_replay");
        } else if (kv.revision() <= watermark) {
            count(inputName(kv.input()), "dropped_revision");
        } else {
            step(kv.input());
        }
    }

    private void tick(FsmTimers.Tick<K> tick) {
        timers.claim(tick)
              .onPresent(attachment -> fired(tick.id(),
                                             attachment))
              .onEmpty(() -> count("Timer", "dropped_superseded"));
    }

    /// Once: on the consumer at `Close`, on the consumer as it exits after a `close()` whose wait expired, or on the caller when no consumer is
    /// alive. Never on a caller while the consumer thread is alive: the timer map and the FSM state belong to the consumer.
    private void shutdown() {
        if (shutDown.compareAndSet(false, true)) {
            closing();
            timers.close();
            stopped.countDown();
        }
    }

    /// The first caller enqueues the `Close` marker; every caller waits on the same completion, so concurrent callers neither wait out the timeout
    /// for a marker nobody will take nor log a stop timeout the first caller did not have.
    ///
    /// FER: a wait that is interrupted is logged and `close` goes on to stop the consumer anyway; the guarantee is that `close` always
    /// returns, the cost is that timers may not have been cancelled by the consumer before it stops (the map is dropped with the runtime).
    private void requestShutdown() {
        if (closeRequested.compareAndSet(false, true)) {
            mailbox.offer(new FsmItem.Close<>(stopped));
        }

        Result.lift(Causes::fromThrowable,
                    () -> stopped.await(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS))
              .onFailure(cause -> log.warn("Interrupted while closing {}: {}",
                                           name,
                                           cause.message()))
              .onSuccess(reached -> warnIfExpired(reached));
    }

    /// The wait is bounded (design 7, 2b): on expiry the consumer is still inside an input; it shuts down as it exits.
    private void warnIfExpired(boolean reached) {
        if (!reached) {
            log.warn("deployment-fsm-stop-timeout {} did not reach Close within {} s; it shuts down when its current input returns",
                     name,
                     CLOSE_WAIT_SECONDS);
        }
    }

    // -- for the subclasses (consumer thread only) -------------------------------------------------------------------
    final FsmTimers<K, A> timers() {
        return timers;
    }

    final Unit offerLast(I input) {
        mailbox.offer(new FsmItem.Input<>(input));

        return Unit.unit();
    }

    /// The snapshot at `revision` was taken: everything stamped at or below it is already in it.
    final Unit snapshotTakenAt(long revision) {
        watermark = Math.max(watermark, revision);

        return Unit.unit();
    }

    static String inputName(Object input) {
        return input.getClass()
                    .getSimpleName();
    }

    /// Steps `input` and dispatches its effects.
    abstract Unit step(I input);
    /// The consumer was asked to enter.
    abstract Unit entry(boolean reconcile);
    /// A timer the FSM armed fired and is still the live one.
    abstract Unit fired(K id, A attachment);
    /// Counts an input the interpreter dropped before the FSM saw it: `inputs_total{input, result}`.
    abstract Unit count(String input, String result);
    /// The FSM is being closed; its pure state needs no further step unless the subclass says so.
    abstract Unit closing();
}
