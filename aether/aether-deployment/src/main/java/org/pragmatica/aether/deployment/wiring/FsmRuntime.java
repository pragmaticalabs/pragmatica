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
import org.pragmatica.lang.Promise;
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
/// snapshot (the snapshot already contains it). Both are counted `inputs_total{result=dropped_replay|dropped_revision}`. A worker's interpreter
/// runs the [ReplayRule#WORKER] rule instead: a replay-flagged input is a fact, deduplicated by the same revision (design D8).
///
/// **A worker's install resets the watermark (CTO ruling D8, know `5b4db3a518`).** On a worker every replica snapshot install (an incarnation
/// change, `AHEAD`, any `restoreCommittedSnapshot`, a `restoreSnapshot` of the metadata channel) is a new snapshot for the node FSM. The install
/// itself signals it ([#installedAt], fed by `KVStore.onReplayStart`, ahead of the burst and also when the burst is empty): the watermark becomes the
/// install's revision, whether it is above or below the old one, and the burst, the replay-flagged inputs that follow, is stepped as the
/// authoritative diff. The burst stays open until the next input that is not replay-flagged or the next entry snapshot. A later install signal at
/// the revision of the last install read (or of the entry snapshot) is that install again and changes nothing; a replay-flagged input outside an
/// open burst is judged by the watermark like any other. Named limits: a different install at the same revision as the last one read is taken for
/// it, because the signal carries no incarnation tag; and a same-incarnation install below what the worker already stepped (a lagging core) steps a
/// regressed burst, #2776, as LEGACY does.
abstract class FsmRuntime<I, K, A> {
    private static final Logger log = LoggerFactory.getLogger(FsmRuntime.class);
    /// How long a stop waits for an interpreter (design 7, 2b; the cluster's stop bound): `[guess: 10 s; settled by E-07]`.
    public static final long STOP_WAIT_MS = 10_000L;
    /// How long the mailbox is joined once the consumer has reached `Close`: it exits at once, so this only bounds a consumer that stalls after it.
    private static final long JOIN_MS = 5_000L;
    /// No install has been read yet: no revision is negative.
    private static final long NO_INSTALL = -1L;

    private final String name;
    private final long stopWaitMs;
    private final FsmMailbox<FsmItem<I, K>> mailbox;
    private final FsmTimers<K, A> timers;
    private final AtomicBoolean shutDown = new AtomicBoolean();
    private final AtomicBoolean closeRequested = new AtomicBoolean();
    /// Counted down when the shutdown has run, by whoever ran it; every `close()` caller waits on this one completion.
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final ReplayRule replayRule;
    private long watermark;
    /// The revision of the last install the FSM has read: the open burst's, or the entry snapshot's. A worker's only (design D8).
    private long installRevision = NO_INSTALL;
    /// Whether the burst of the install at [#installRevision] is still being delivered (a worker's only).
    private boolean burstOpen;

    FsmRuntime(String threadName, FsmTimers.TimerService clock) {
        this(threadName, clock, STOP_WAIT_MS);
    }

    FsmRuntime(String threadName, FsmTimers.TimerService clock, long stopWaitMs) {
        this(threadName, clock, stopWaitMs, ReplayRule.CORE);
    }

    FsmRuntime(String threadName, FsmTimers.TimerService clock, long stopWaitMs, ReplayRule replayRule) {
        this.name = threadName;
        this.replayRule = replayRule;
        this.stopWaitMs = stopWaitMs;
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

    /// Enqueues a worker's replica snapshot install at `revision`, ahead of the replay-flagged inputs of its burst (design D8). Called by the store's
    /// delivering thread at the start of every replay item, so the order in the mailbox is the order of delivery.
    public boolean installedAt(long revision) {
        return offered(mailbox.offer(new FsmItem.Install<>(revision)), "SnapshotInstall");
    }

    /// Asks the consumer to enter: the consumer snapshots at a revision and steps the entry input, but only while not yet active.
    public boolean requestEntry(boolean reconcile) {
        return offered(mailbox.offer(new FsmItem.Entry<>(reconcile)), "EnterRequested");
    }

    /// Design 7 (2a): resolves once the consumer has handled everything enqueued before this call and [#drained()] holds. Already resolved when
    /// the interpreter is closed (nothing is left to wait for; the offer is counted `result=closed`), and when the consumer was never started
    /// (#3173): a node refused or stopped before `start()` has no consumer to take the marker, so a marker would only wait out the stop bound
    /// while the node lingers past the cluster's own stop bound. Nothing was stepped, so no local op is owed either and [#drained()] is skipped.
    public Promise<Unit> drain() {
        if (!mailbox.started()) {
            return Promise.unitPromise();
        }

        var reached = Promise.<Unit> promise();

        return offered(mailbox.offer(new FsmItem.Barrier<>(reached)), "Drain")
               ? reached.flatMap(_ -> drained())
               : Promise.unitPromise();
    }

    /// What must also hold when the consumer has reached a [#drain()] marker. The node interpreter adds that its local ops have run.
    Promise<Unit> drained() {
        return Promise.unitPromise();
    }

    /// Design 4.4, B14: the committed snapshot was restored under this interpreter.
    @Contract
    public boolean stateRestored() {
        return offered(mailbox.offer(new FsmItem.Restored<>()), "StateRestored");
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
    ///
    /// **Bounded (2b, W-27).** The wait for the consumer to reach `Close` is `stopWaitMs`, and that is the whole bound: a consumer that did not
    /// reach it is stuck in an input (an inline `Route` subscriber, P3-Q19), so the mailbox is not joined a second time.
    @Contract
    public void close() {
        if (mailbox.onConsumerThread() || mailbox.retireIfConsumerless()) {
            shutdown();
            mailbox.close();
        } else {
            mailbox.closeWithin(requestShutdown()
                                ? JOIN_MS
                                : 0L);
        }
    }

    // -- the consumer's side ----------------------------------------------------------------------------------------
    /// One item, on the consumer thread.
    private void handle(FsmItem<I, K> item) {
        switch (item) {
            case FsmItem.Input<I, K> input -> step(input.input());
            case FsmItem.Kv<I, K> kv -> keyValue(kv);
            case FsmItem.Install<I, K> install -> installSignal(install.revision());
            case FsmItem.Tick<I, K> tick -> tick(tick.tick());
            case FsmItem.Entry<I, K> entry -> entry(entry.reconcile());
            case FsmItem.Barrier<I, K> barrier -> barrier.reached().succeed(Unit.unit());
            case FsmItem.Restored<I, K>_ -> restored();
            case FsmItem.Close<I, K> close -> {
                shutdown();
                mailbox.close();
                close.done().countDown();
            }
        }
    }

    private void keyValue(FsmItem.Kv<I, K> kv) {
        burstOpen = burstOpen && kv.replay();
        if (kv.replay() && replayRule == ReplayRule.CORE) {
            count(inputName(kv.input()), "dropped_replay");
        } else if (kv.replay() && burstOpen) {
            step(kv.input());
        } else if (kv.revision() <= watermark) {
            count(inputName(kv.input()), "dropped_revision");
        } else {
            step(kv.input());
        }
    }

    /// A worker's replica install at `revision` (design D8): its burst is the authoritative diff, and what follows dedupes against `revision`. The
    /// install already read (the last one's, or the entry snapshot's) is not opened again. A core never installs under an ACTIVE interpreter, and
    /// the store's listener is not registered there.
    private void installSignal(long revision) {
        if (replayRule == ReplayRule.WORKER && revision != installRevision) {
            watermark = revision;
            installRevision = revision;
            burstOpen = true;
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
    private boolean requestShutdown() {
        if (closeRequested.compareAndSet(false, true)) {
            mailbox.offer(new FsmItem.Close<>(stopped));
        }

        return Result.lift(Causes::fromThrowable,
                           () -> stopped.await(stopWaitMs, TimeUnit.MILLISECONDS))
                     .onFailure(cause -> log.warn("Interrupted while closing {}: {}",
                                                  name,
                                                  cause.message()))
                     .onSuccess(reached -> warnIfExpired(reached))
                     .or(false);
    }

    /// The wait is bounded (design 7, 2b): on expiry the consumer is still inside an input; it shuts down as it exits.
    private void warnIfExpired(boolean reached) {
        if (!reached) {
            log.warn("deployment-fsm-stop-timeout {} did not reach Close within {} ms; it shuts down when its current input returns",
                     name,
                     stopWaitMs);
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

    /// The snapshot at `revision` was taken: everything stamped at or below it is already in it. On a worker it is also the install the FSM has
    /// read, so a burst at `revision` that follows it is that install's, and deduplicated.
    final Unit snapshotTakenAt(long revision) {
        watermark = Math.max(watermark, revision);
        installRevision = revision;
        burstOpen = false;

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

    /// The committed snapshot was restored (B14). The node FSM needs nothing: a restore is bracketed by the genuine quorum edges, and its replay
    /// burst is dropped by the core rule (design 4.4).
    Unit restored() {
        return count("StateRestored", "ignored");
    }

    /// Counts an input the interpreter dropped before the FSM saw it: `inputs_total{input, result}`.
    abstract Unit count(String input, String result);
    /// The FSM is being closed; its pure state needs no further step unless the subclass says so.
    abstract Unit closing();
}
