// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.state.kvstore;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongConsumer;

import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueGet;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.cluster.state.kvstore.NotificationDelivery.Dispatched;
import org.pragmatica.cluster.state.kvstore.NotificationDelivery.OperatorEvents;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.Message;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.utility.warning.OperatorWarningCode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;


/// The delivery side of a [KVStore] (design §3.1-§3.7): sequence numbers, the FIFO of applied batches, the delivery loop,
/// `awaitDelivered`, parked apply promises, the closed state, the failure path, the read-ahead fence and the lag metrics.
///
/// **Monitor.** Every field marked "monitor" is guarded by the owning store's monitor (`synchronized (store)`), which the
/// store's writers already hold while they apply. The writer side ([#allocate], [#recordWrite], [#recordRemoval],
/// [#batch]) is called with the monitor held; the append ([#append]) is called under the store's mutation guard with the
/// monitor released, so a handler's synchronized `get` never waits on an appending writer (§3.1 step 3).
///
/// **Modes.** [NotificationDelivery.Inline] delivers on the writer's thread inside its mutation guard (the pre-D1 drain);
/// [NotificationDelivery.Dispatched] delivers on one daemon thread per store, started at the first append.
final class NotificationDispatcher {
    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);
    /// The bounded join at teardown: `Main.FLUSH_TIMEOUT`'s value (a guess, taste; design §3.2 Lifecycle).
    static final long JOIN_TIMEOUT_MILLIS = 5_000;
    /// A known-slow notification type dumps its stack at most once per this interval (design §3.6; a guess, taste).
    static final long SLOW_REPORT_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(10);
    /// The watchdog's tick floor: a 5 ms threshold is counted exactly at handler end; the stack of a handler still
    /// running is dumped at this granularity.
    private static final long WATCHDOG_TICK_FLOOR_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    /// No sequence: the store is closed, so the mutation is applied but nothing is allocated or appended (§3.2 step 1).
    static final long NO_SEQ = -1;
    static final String PHASE_LIVE = "live";
    static final String PHASE_REPLAY = "replay";

    /// One applied batch on its way to the subscribers. A replay item carries no notifications: its burst is the diff the
    /// delivery loop computes when it dequeues the item (§3.7).
    record DeliveryBatch(long seq,
                         long revision,
                         List<Message> notifications,
                         boolean replay,
                         long enqueuedNanos,
                         long bytes) {}

    private record Waiter(Fn0<Unit> succeed, Fn1<Unit, Cause> fail) {}

    private record Tombstone(long seq, Object key) {}

    private final KVStore<?, ?> store;
    private final MessageRouter router;
    private final NotificationDelivery delivery;
    private final boolean threaded;
    private final String name;
    private final DispatcherFailureSink sink;
    private final DeliveryHook hook;
    private final OperatorEvents events;
    private final long slowHandlerNanos;
    private final ValueSizeTable sizes = ValueSizeTable.valueSizeTable();
    // monitor
    private long enqueuedSeq;
    // monitor (written); volatile so metrics read it without the monitor
    private volatile long deliveredSeq;
    // monitor
    private boolean closed;
    // monitor
    private final Map<Object, Long> lastModifiedSeq = new HashMap<>();
    // monitor
    private final ArrayDeque<Tombstone> tombstones = new ArrayDeque<>();
    // monitor
    private final TreeMap<Long, List<Waiter>> waiters = new TreeMap<>();
    private final LinkedBlockingQueue<DeliveryBatch> queue = new LinkedBlockingQueue<>();
    private final AtomicLong queueDepth = new AtomicLong();
    private final AtomicLong highWaterLive = new AtomicLong();
    private final AtomicLong highWaterReplay = new AtomicLong();
    private final AtomicLong queueBytes = new AtomicLong();
    private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final Map<String, Boolean> warnedReadAhead = new ConcurrentHashMap<>();
    private final Map<String, Long> slowReportedAt = new ConcurrentHashMap<>();
    private final AtomicLong slowHandlerMaxNanos = new AtomicLong();
    private final AtomicLong closedDropped = new AtomicLong();

    /// Told, on the delivering thread, the revision of each replay item BEFORE its burst is computed and delivered, so an install is signalled even
    /// when its burst is empty. Set once at wiring; the default ignores it.
    private volatile LongConsumer replayStart = _ -> {};

    /// Told, on the delivering thread, the revision of each replay item AFTER its whole burst is delivered (empty burst included), so a subscriber can
    /// act on the caught-up delivered view (#3028). Set once at wiring; the default ignores it.
    private volatile LongConsumer replayEnd = _ -> {};

    private final ThreadLocal<Boolean> replaying = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private final ThreadLocal<Option<DeliveryBatch>> delivering = ThreadLocal.withInitial(Option::none);
    /// Mutation guard holder only (inline mode): true while this thread's drain is running, so a reentrant write appends.
    private boolean draining;
    /// Written under the mutation guard at the first append, BEFORE the thread starts: `Thread.start()` publishes it to the
    /// delivery thread, so the replay refusal it reads there holds from the first batch on. Volatile for [#close] and the
    /// watchdog, which read it on other threads.
    private volatile Option<Thread> thread = Option.none();
    /// Written before [#thread], so a [#close] that sees the thread also sees its watchdog.
    private volatile Option<ScheduledExecutorService> watchdog = Option.none();
    /// Orders the watchdog's slow-handler report against the end of that handler, so a raised
    /// `kv-dispatch-slow-handler` is always followed by its `kv-dispatch-slow-handler-returned`.
    private final Object slowReport = new Object();
    /// Guarded by [#slowReport]: the subject of the slow-handler event raised for the handler now running, if one was.
    private Option<String> slowRaised = Option.none();
    private volatile boolean stopRequested;
    private volatile Option<DeliveryBatch> inFlight = Option.none();
    private volatile Option<Message> currentMessage = Option.none();
    private volatile long handlerStartedNanos;
    private volatile boolean handlerReported;
    private volatile Thread deliveringThread;
    private final Option<ExecutorService> completions;

    NotificationDispatcher(KVStore<?, ?> store, MessageRouter router, NotificationDelivery delivery) {
        this.store = store;
        this.router = router;
        this.delivery = delivery;
        this.threaded = delivery instanceof Dispatched;
        this.name = delivery instanceof Dispatched dispatched
                    ? dispatched.name()
                    : "inline";
        this.sink = delivery instanceof Dispatched dispatched
                    ? dispatched.sink()
                    : DispatcherFailureSink.loggingOnly();
        this.hook = delivery instanceof Dispatched dispatched
                    ? dispatched.hook()
                    : DeliveryHook.none();
        this.events = delivery instanceof Dispatched dispatched
                      ? dispatched.events()
                      : OperatorEvents.none();
        this.slowHandlerNanos = delivery instanceof Dispatched dispatched
                                ? dispatched.slowHandlerNanos()
                                : Long.MAX_VALUE;
        this.completions = threaded
                           ? Option.some(Executors.newVirtualThreadPerTaskExecutor())
                           : Option.none();
    }

    NotificationDelivery.ReplayRank replayRank() {
        return delivery.replayRank();
    }

    boolean threaded() {
        return threaded;
    }

    @Contract
    void onReplayStart(LongConsumer listener) {
        replayStart = listener;
    }

    @Contract
    void onReplayEnd(LongConsumer listener) {
        replayEnd = listener;
    }

    String name() {
        return name;
    }

    // ------------------------------------------------------------------ writer side (store monitor held)
    /// The next `deliverySeq`, or [#NO_SEQ] once closed. Never reset (`reset()` leaves it alone, §3.1).
    long allocate() {
        return closed
               ? NO_SEQ
               : ++enqueuedSeq;
    }

    Unit recordWrite(Object key, long seq) {
        if (seq != NO_SEQ) {
            lastModifiedSeq.put(key, seq);
        }

        return unit();
    }

    /// A removed key keeps its entry as a tombstone until its batch is delivered (§3.2 "Tombstone trim").
    Unit recordRemoval(Object key, long seq) {
        if (seq != NO_SEQ) {
            lastModifiedSeq.put(key, seq);
            tombstones.addLast(new Tombstone(seq, key));
        }

        return unit();
    }

    Option<DeliveryBatch> batch(long seq, long revision, List<Message> notifications, boolean replay) {
        return seq == NO_SEQ
               ? Option.none()
               : Option.some(new DeliveryBatch(seq,
                                               revision,
                                               List.copyOf(notifications),
                                               replay,
                                               System.nanoTime(),
                                               estimate(notifications)));
    }

    private long estimate(List<Message> notifications) {
        return notifications.stream()
                            .mapToLong(this::estimate)
                            .sum();
    }

    /// Old and new value, as the size table estimates them (an estimate, labelled as one: `queue.bytes`, §3.6).
    private long estimate(Message message) {
        return switch (message) {
            case ValuePut<?, ?> put -> sizes.estimate(put.cause().value()) + put.oldValue().map(sizes::estimate).or(0);
            case ValueRemove<?, ?> remove -> remove.value().map(sizes::estimate).or(0);
            default -> 0L;
        };
    }

    // ------------------------------------------------------------------ append (mutation guard held, monitor released)
    /// Never blocks (unbounded FIFO, ruling 3). Inline mode drains here; dispatched mode wakes the delivery thread.
    Unit append(DeliveryBatch batch) {
        queueBytes.addAndGet(batch.bytes());
        var depth = queueDepth.incrementAndGet();

        (batch.replay()
         ? highWaterReplay
         : highWaterLive).accumulateAndGet(depth, Math::max);
        queue.add(batch);

        return threaded
               ? ensureStarted()
               : drainInline();
    }

    private Unit ensureStarted() {
        if (thread.isEmpty() && !stopRequested) {
            hook.beforeThreadStart();
            var created = Thread.ofPlatform().name(threadName()).daemon(true).unstarted(this::runLoop);

            watchdog = Option.some(startWatchdog());
            thread = Option.some(created);
            created.start();
            stopIfClosedMeanwhile();
        }

        return unit();
    }

    /// v-2648 A3-2: [#close] does not take the mutation guard, so it can run whole between the check above and the
    /// watchdog's creation, see neither the thread nor the watchdog, and leave the watchdog running. `stopRequested` is
    /// written by close before it reads [#watchdog], and read here after the watchdog is published (both volatile), so at
    /// least one side shuts it down; a second `shutdownNow` is a no-op. The thread itself ends on its own: its loop reads
    /// `stopRequested` before its first `take()`.
    private void stopIfClosedMeanwhile() {
        if (stopRequested) {
            watchdog.onPresent(ScheduledExecutorService::shutdownNow);
        }
    }

    String threadName() {
        return "kv-dispatcher-" + name;
    }

    private Unit drainInline() {
        if (draining) {
            return unit();
        }

        draining = true;
        try {
            Option.option(queue.poll()).onPresent(this::deliverInlineChain);
        } finally {
            draining = false;
        }

        return unit();
    }

    private void deliverInlineChain(DeliveryBatch first) {
        var next = Option.some(first);

        while (next.isPresent()) {
            next.onPresent(this::deliver);
            next = Option.option(queue.poll());
        }
    }

    // ------------------------------------------------------------------ delivery loop
    @Contract
    private void runLoop() {
        while (!stopRequested) {
            Result.lift(Causes::fromThrowable, queue::take).onSuccess(this::deliverGuarded).onFailure(this::takeFailed);
        }
    }

    /// Only [#close] ends the loop (design §3.2): its interrupt arrives with `stopRequested` already set. Any other
    /// interrupt of an idle loop is a loop failure and takes the failure path, never a silent exit.
    @Contract
    private void takeFailed(Cause cause) {
        if (!stopRequested) {
            stopRequested = true;
            fail("loop body (take)",
                 new IllegalStateException("delivery loop interrupted outside close(): " + cause.message()));
        }
    }

    /// A handler that leaves its thread's interrupt flag set (catches an `InterruptedException` and re-asserts it) would
    /// make the next `take()` throw. The flag is cleared and reported here, so delivery continues; a close's interrupt
    /// (`stopRequested` set) is left for the loop to see.
    private void clearHandlerInterrupt() {
        if (!stopRequested && Thread.interrupted()) {
            log.warn("KV dispatcher {}: a handler left the thread's interrupt flag set; cleared, delivery continues",
                     name);
        }
    }

    /// The per-iteration catch (R2.1): anything escaping one iteration, a `RuntimeException` included, goes to the
    /// failure path, and the loop ends.
    @Contract
    private void deliverGuarded(DeliveryBatch batch) {
        try {
            deliver(batch);
            clearHandlerInterrupt();
        } catch (Throwable t) {
            stopRequested = true;
            fail(currentMessage.map(message -> "handler " + describe(message)).or("loop body"),
                 t);
        }
    }

    @Contract
    private void deliver(DeliveryBatch batch) {
        inFlight = Option.some(batch);
        queueDepth.decrementAndGet();
        hook.beforeDelivery(batch.seq());
        deliveringThread = Thread.currentThread();
        delivering.set(Option.some(batch));
        replaying.set(batch.replay());
        try {
            if (batch.replay()) {
                replayStart.accept(batch.revision());
            }

            var messages = batch.replay()
                           ? store.replayBurst()
                           : batch.notifications();

            messages.forEach(this::deliverOne);
            if (batch.replay()) {
                replayEnd.accept(batch.revision());
            }
        } finally {
            replaying.set(Boolean.FALSE);
            delivering.set(Option.none());
            currentMessage = Option.none();
        }

        publishDelivered(batch);
    }

    @Contract
    private void deliverOne(Message message) {
        currentMessage = Option.some(message);
        handlerReported = false;
        handlerStartedNanos = System.nanoTime();
        try {
            routeIsolated(message);
        } finally {
            noteHandlerDuration(System.nanoTime() - handlerStartedNanos);
            handlerEnded();
        }

        store.trackDelivered(message);
    }

    /// The per-handler catch: the router already isolates a `RuntimeException` per handler (P6); one that escapes the
    /// router is isolated here per notification. An `Error` is never contained: it reaches the iteration catch.
    @Contract
    private void routeIsolated(Message message) {
        if (!threaded) {
            router.route(message);

            return;
        }

        try {
            router.route(message);
        } catch (RuntimeException e) {
            log.warn("KV dispatcher {}: delivery of {} failed and was skipped", name, describe(message), e);
        }
    }

    private void publishDelivered(DeliveryBatch batch) {
        List<Waiter> due;

        synchronized (store) {
            deliveredSeq = batch.seq();
            trimTombstones(batch.seq());
            due = takeWaitersUpTo(batch.seq());
        }

        queueBytes.addAndGet(-batch.bytes());
        inFlight = Option.none();
        due.forEach(this::complete);
    }

    // monitor
    private void trimTombstones(long delivered) {
        while (!tombstones.isEmpty() && tombstones.peekFirst().seq() <= delivered) {
            var tombstone = tombstones.removeFirst();

            lastModifiedSeq.remove(tombstone.key(), tombstone.seq());
        }
    }

    // monitor
    private List<Waiter> takeWaitersUpTo(long delivered) {
        var due = new ArrayList<Waiter>();
        var head = waiters.headMap(delivered, true);

        head.values().forEach(due::addAll);
        head.clear();

        return due;
    }

    /// One wrapped task per completion on the store-owned virtual-thread executor (§3.3, R3.3): dependents never run on
    /// the delivery thread, and two completions never queue behind each other. Inline mode completes on the caller.
    private Unit complete(Waiter waiter) {
        if (!threaded) {
            waiter.succeed().apply();

            return unit();
        }

        return completions.toResult(KVDeliveryError.DeliveryOutcomeUnknown.FACTORY.apply(name, "no completion executor"))
                          .flatMap(executor -> Result.lift(Causes::fromThrowable,
                                                           () -> executor.execute(() -> runCompletion(waiter))))
                          .onFailure(cause -> waiter.fail()
                                                    .apply(outcomeUnknown("completion executor closed: " + cause.message())))
                          .or(unit());
    }

    @Contract
    private void runCompletion(Waiter waiter) {
        try {
            waiter.succeed().apply();
        } catch (Throwable t) {
            fail("completion", t);
        }
    }

    // ------------------------------------------------------------------ waits
    /// Resolves when `deliveredSeq ≥ seq`; fails at once with an outcome-unknown cause once the store is closed (the
    /// closed flag is read under the monitor the close sweeps under, so no waiter slips between them, R2.1 item 2).
    Promise<Unit> awaitDelivered(long seq) {
        synchronized (store) {
            if (closed) {
                return outcomeUnknown("closed").promise();
            }

            if (deliveredSeq >= seq) {
                return Promise.unitPromise();
            }

            var promise = Promise.<Unit> promise();

            park(seq,
                 new Waiter(() -> resolved(promise.succeed(unit())), cause -> resolved(promise.fail(cause))));

            return promise;
        }
    }

    /// Parks an apply promise until every batch enqueued so far, the caller's own included, is delivered (P3, §3.3). The
    /// caller applied its batch before calling, so waiting on the newest seq is never early; a later writer's seq only
    /// makes it later, never wrong.
    <T> Unit completeAfterDelivery(Promise<T> promise, T value) {
        var waiter = new Waiter(() -> resolved(promise.succeed(value)), cause -> resolved(promise.fail(cause)));
        Option<Waiter> now;

        synchronized (store) {
            now = closed || deliveredSeq >= enqueuedSeq
                  ? Option.some(waiter)
                  : park(enqueuedSeq, waiter);
        }

        return now.map(this::completeNow)
                  .or(unit());
    }

    private Unit completeNow(Waiter waiter) {
        return closedNow()
               ? waiter.fail()
                       .apply(outcomeUnknown("closed"))
               : complete(waiter);
    }

    private boolean closedNow() {
        synchronized (store) {
            return closed;
        }
    }

    // monitor
    private Option<Waiter> park(long seq, Waiter waiter) {
        waiters.computeIfAbsent(seq, _ -> new ArrayList<>()).add(waiter);

        return Option.none();
    }

    private static <T> Unit resolved(Promise<T> ignored) {
        return unit();
    }

    // ------------------------------------------------------------------ replay
    boolean isReplaying() {
        return replaying.get();
    }

    /// The batch being delivered on this thread: its revision and seq (R3 captures, FSM2's input order).
    Option<DeliveryBatch> deliveringBatch() {
        return delivering.get();
    }

    boolean onDeliveryThread() {
        return threaded
               ? thread.map(t -> t == Thread.currentThread())
                       .or(false)
               : draining && deliveringThread == Thread.currentThread();
    }

    /// The caller rule of §3.7 (R3.1): a replay from a thread holding the store monitor or the mutation guard, or from
    /// the delivery thread itself, would wait for itself. Dispatched mode only: inline delivers on the caller.
    Option<String> replayRefusal(Object mutationLock) {
        if (!threaded) {
            return Option.none();
        }

        if (Thread.holdsLock(store)) {
            return Option.some("caller holds the store monitor");
        }

        if (Thread.holdsLock(mutationLock)) {
            return Option.some("caller holds the mutation guard");
        }

        return thread.filter(t -> t == Thread.currentThread())
                     .map(_ -> "caller is the dispatcher thread");
    }

    // ------------------------------------------------------------------ fence (store monitor held, delivery thread only)
    Unit onPointRead(Object key) {
        delivering.get().filter(_ -> isDeliveringThread()).onPresent(batch -> checkPointRead(batch, key));

        return unit();
    }

    Unit onSnapshotRead() {
        delivering.get()
                  .filter(_ -> isDeliveringThread())
                  .filter(batch -> enqueuedSeq > batch.seq())
                  .onPresent(batch -> count("readahead.snapshotReads",
                                            phaseOf(batch),
                                            tagNow()));

        return unit();
    }

    private boolean isDeliveringThread() {
        return deliveringThread == Thread.currentThread();
    }

    private void checkPointRead(DeliveryBatch batch, Object key) {
        Option.option(lastModifiedSeq.get(key))
              .filter(written -> written > batch.seq())
              .onPresent(written -> readAhead(batch, key, written));
    }

    /// The first read-ahead per key type raises KV_DISPATCH_READ_AHEAD once per process. Known limit (v-2648 A3-1, accepted
    /// as documented): when a type's first read-ahead happens while a replay burst is delivered, the node sink marks the event
    /// replayed and the aggregator suppresses it, while the mark below is already set, so that type's INFO event is lost for
    /// the rest of the process. The `kv.dispatch.readahead.reads` counter still counts every read.
    private void readAhead(DeliveryBatch batch, Object key, long written) {
        var tag = tagNow();

        count("readahead.reads", phaseOf(batch), tag);
        if (warnedReadAhead.putIfAbsent(tag, Boolean.TRUE) == null) {
            var detail = "handler of " + tag
                       + " read " + key
                       + " while delivering seq " + batch.seq()
                       + "; written by seq " + written;

            log.warn("KV dispatcher {}: first read-ahead for {}", name, detail);
            events.emit(OperatorWarningCode.KV_DISPATCH_READ_AHEAD, tag, detail);
        }
    }

    private String tagNow() {
        return currentMessage.map(NotificationDispatcher::tagOf)
                             .or("unknown");
    }

    private static String phaseOf(DeliveryBatch batch) {
        return batch.replay()
               ? PHASE_REPLAY
               : PHASE_LIVE;
    }

    private void count(String counter, String phase, String tag) {
        counters.computeIfAbsent(counter + "|" + phase + "|" + tag, _ -> new LongAdder()).increment();
    }

    /// The sum of a counter over every tag, for one phase (`kv.dispatch.readahead.reads`, `.snapshotReads`).
    long counter(String counter, String phase) {
        var prefix = counter + "|" + phase + "|";

        return counters.entrySet()
                       .stream()
                       .filter(entry -> entry.getKey()
                                             .startsWith(prefix))
                       .mapToLong(entry -> entry.getValue()
                                                .sum())
                       .sum();
    }

    /// The counter for one tag (a notification's key class, the nearest the store gets to a handler registration).
    long counter(String counter, String phase, String tag) {
        return Option.option(counters.get(counter + "|" + phase + "|" + tag))
                     .map(LongAdder::sum)
                     .or(0L);
    }

    private static String tagOf(Message message) {
        return switch (message) {
            case ValuePut<?, ?> put -> put.cause().key().getClass().getSimpleName();
            case ValueRemove<?, ?> remove -> remove.cause().key().getClass().getSimpleName();
            case ValueGet<?, ?> get -> get.cause().key().getClass().getSimpleName();
            default -> message.getClass().getSimpleName();
        };
    }

    private static String describe(Message message) {
        return message.getClass()
                      .getSimpleName() + " " + tagOf(message);
    }

    // ------------------------------------------------------------------ watchdog (§3.6)
    private ScheduledExecutorService startWatchdog() {
        var executor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform()
                                                                        .name(threadName() + "-watchdog")
                                                                        .daemon(true)
                                                                        .factory());
        var tick = Math.max(slowHandlerNanos, WATCHDOG_TICK_FLOOR_NANOS);

        executor.scheduleAtFixedRate(this::watch, tick, tick, TimeUnit.NANOSECONDS);

        return executor;
    }

    @Contract
    private void watch() {
        synchronized (slowReport) {
            var started = handlerStartedNanos;

            if (started != 0 && !handlerReported && System.nanoTime() - started >= slowHandlerNanos) {
                handlerReported = true;
                currentMessage.onPresent(this::reportSlow);
            }
        }
    }

    /// The end of one handler invocation, ordered against [#watch]: a slow-handler event raised for it is closed here.
    private void handlerEnded() {
        synchronized (slowReport) {
            handlerStartedNanos = 0;
            slowRaised.onPresent(this::reportReturned);
            slowRaised = Option.none();
        }
    }

    // slowReport
    private void reportReturned(String tag) {
        log.info("KV dispatcher {}: the slow handler of {} returned", name, tag);
        events.emit(OperatorWarningCode.KV_DISPATCH_SLOW_HANDLER_RETURNED,
                    tag,
                    "KV store " + name + ": the handler of " + tag + " returned");
    }

    // slowReport
    private void reportSlow(Message message) {
        var tag = describe(message);
        var now = System.nanoTime();
        var last = slowReportedAt.get(tag);

        if (last == null || now - last >= SLOW_REPORT_INTERVAL_NANOS) {
            slowReportedAt.put(tag, now);
            var stack = thread.map(NotificationDispatcher::stackOf).or("");

            log.warn("KV dispatcher {}: handler of {} running longer than {} ms; dispatcher stack:{}",
                     name,
                     tag,
                     TimeUnit.NANOSECONDS.toMillis(slowHandlerNanos),
                     stack);
            events.emit(OperatorWarningCode.KV_DISPATCH_SLOW_HANDLER,
                        tag,
                        "KV store " + name
                       + ": a handler of " + tag
                       + " has run longer than " + TimeUnit.NANOSECONDS.toMillis(slowHandlerNanos)
                       + " ms, holding notification delivery");
            slowRaised = Option.some(tag);
        }
    }

    private void noteHandlerDuration(long elapsed) {
        if (elapsed >= slowHandlerNanos) {
            currentMessage.onPresent(message -> count("slowHandler", PHASE_LIVE, describe(message)));
            slowHandlerMaxNanos.accumulateAndGet(elapsed, Math::max);
        }
    }

    long slowHandlerCount() {
        return counter("slowHandler", PHASE_LIVE);
    }

    long slowHandlerMaxNanos() {
        return slowHandlerMaxNanos.get();
    }

    private static String stackOf(Thread thread) {
        var builder = new StringBuilder();

        for (var frame : thread.getStackTrace()) {
            builder.append("\n\tat ").append(frame);
        }

        return builder.toString();
    }

    // ------------------------------------------------------------------ metrics (§3.6)
    long enqueuedSeq() {
        synchronized (store) {
            return enqueuedSeq;
        }
    }

    long deliveredSeq() {
        return deliveredSeq;
    }

    /// `enqueuedSeq != deliveredSeq` under the monitor (§3.7 "Removed, and replaced").
    boolean hasPending() {
        synchronized (store) {
            return enqueuedSeq != deliveredSeq;
        }
    }

    /// `kv.dispatch.lag.batches`: seq-based, so empty slots and `reset()` move nothing.
    long lagBatches() {
        synchronized (store) {
            return enqueuedSeq - deliveredSeq;
        }
    }

    /// `kv.dispatch.lag.oldestAgeMs`: the age of the oldest undelivered batch (the one in delivery, else the queue head).
    long oldestAgeMillis() {
        var oldest = inFlight.isPresent()
                     ? inFlight
                     : Option.option(queue.peek());

        return oldest.map(batch -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - batch.enqueuedNanos()))
                     .or(0L);
    }

    long queueDepth() {
        return queueDepth.get();
    }

    long highWater(String phase) {
        return PHASE_REPLAY.equals(phase)
               ? highWaterReplay.get()
               : highWaterLive.get();
    }

    /// `kv.dispatch.queue.bytes`, an estimate.
    long queueBytes() {
        return queueBytes.get();
    }

    long droppedAtClose() {
        return closedDropped.get();
    }

    boolean closed() {
        synchronized (store) {
            return closed;
        }
    }

    // ------------------------------------------------------------------ closed state
    static KVDeliveryError.DeliveryOutcomeUnknown outcomeUnknown(String store, String reason) {
        return KVDeliveryError.DeliveryOutcomeUnknown.FACTORY.apply(store, reason);
    }

    private KVDeliveryError.DeliveryOutcomeUnknown outcomeUnknown(String reason) {
        return outcomeUnknown(name, reason);
    }

    /// Closes once, first caller wins (R4.4): sets the flag and takes every waiter under the monitor, then fails them
    /// outside it. Empty when the store was already closed.
    private Option<List<Waiter>> closeOnce() {
        synchronized (store) {
            if (closed) {
                return Option.none();
            }

            closed = true;
            var all = new ArrayList<Waiter>();

            waiters.values().forEach(all::addAll);
            waiters.clear();

            return Option.some(all);
        }
    }

    private Unit failAll(List<Waiter> taken, String reason) {
        var cause = outcomeUnknown(reason);

        taken.forEach(waiter -> waiter.fail()
                                      .apply(cause));

        return unit();
    }

    /// Node teardown (§3.2 Lifecycle): closes the store, fails every waiter with outcome-unknown, drops the undelivered
    /// queue with one INFO line, and joins the delivery thread with a bound; a stuck thread is a daemon and is named in
    /// one WARN with its stack. A store with no delivery thread only closes.
    Unit close() {
        long from;
        long to;

        synchronized (store) {
            from = deliveredSeq + 1;
            to = enqueuedSeq;
        }

        closeOnce().onPresent(taken -> failAll(taken, "teardown"));
        stopRequested = true;
        var dropped = queue.size();

        queue.clear();
        closedDropped.set(dropped);
        if (to >= from) {
            log.info("KV store {} closed at teardown: {} undelivered batch(es) dropped (seq {}..{})",
                     name,
                     to - from + 1,
                     from,
                     to);
        }

        thread.onPresent(this::join);
        watchdog.onPresent(ScheduledExecutorService::shutdownNow);
        completions.onPresent(ExecutorService::shutdown);

        return unit();
    }

    /// A close called on the delivery thread itself (a host stop that runs inline on the failing thread) does not wait
    /// for itself: the loop sees `stopRequested` when the current batch returns.
    @Contract
    private void join(Thread delivery) {
        if (delivery == Thread.currentThread()) {
            return;
        }

        delivery.interrupt();
        Result.lift(Causes::fromThrowable, () -> delivery.join(JOIN_TIMEOUT_MILLIS));
        if (delivery.isAlive()) {
            log.warn("KV store {}: dispatcher thread did not end within {} ms; it is a daemon and is left behind:{}",
                     name,
                     JOIN_TIMEOUT_MILLIS,
                     stackOf(delivery));
        }
    }

    /// The failure path (§3.2, owner ruling 1): close first, then best-effort diagnostics, then stop the node through the
    /// sink in a `finally`. Entered at most once; a later caller logs one line and returns.
    Unit fail(String step, Throwable cause) {
        var taken = closeOnce();

        if (taken.isEmpty()) {
            log.error("KV dispatcher {}: already closed; later failure at {}: {}", name, step, cause.toString());

            return unit();
        }

        try {
            taken.onPresent(all -> failAll(all, "dispatcher failure at " + step));
            diagnostics(step, cause);
        } finally {
            sink.stopNode("kv-dispatcher-failed", cause);
        }

        return unit();
    }

    @Contract
    private void diagnostics(String step, Throwable cause) {
        bestEffort(() -> log.error("FATAL KV dispatcher {} failed at {}", name, step));
        bestEffort(() -> log.error("KV dispatcher {}: delivering seq {}, deliveredSeq {}, enqueuedSeq {}, committedRevision {}, queue depth {}",
                                   name,
                                   inFlight.map(DeliveryBatch::seq).or(NO_SEQ),
                                   deliveredSeq,
                                   enqueuedSeqUnlocked(),
                                   store.committedRevisionUnlocked(),
                                   queueDepth.get()));
        bestEffort(() -> log.error("KV dispatcher {}: failure", name, cause));
        bestEffort(() -> log.error("KV dispatcher {}: thread dump:{}", name, threadDump()));
        bestEffort(() -> events.emit(OperatorWarningCode.KV_DISPATCHER_FAILED,
                                     name,
                                     "KV store " + name
                                    + ": notification dispatcher failed at " + step
                                    + ": " + cause
                                    + "; the store is closed and the node is stopping"));
    }

    private long enqueuedSeqUnlocked() {
        return enqueuedSeq;
    }

    @Contract
    private static void bestEffort(Runnable step) {
        try {
            step.run();
        } catch (Throwable ignored) {
        // Diagnostics are best effort (ruling 1): one failing never stops the next, nor the sink.
        }
    }

    private static String threadLine(Thread thread, StackTraceElement[] frames) {
        var top = frames.length > 0
                  ? "\n\tat " + frames[0]
                  : "";

        return "\n\"" + thread.getName() + "\" " + thread.getState() + top;
    }

    private static String threadDump() {
        var builder = new StringBuilder();

        Thread.getAllStackTraces().forEach((t, frames) -> builder.append(threadLine(t, frames)));

        return builder.toString();
    }
}
