// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.slice.ConsumerConfig.ErrorStrategy;
import org.pragmatica.aether.stream.consumer.TransactionalCursorCommit;
import org.pragmatica.aether.stream.segment.ConsumerCursorStore;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.TerminalOperation;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.JitterUtil;
import org.pragmatica.lang.utils.SharedScheduler;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


final class ConsumerRuntimeState implements StreamConsumerRuntime {
    private static final System.Logger LOG = System.getLogger(ConsumerRuntimeState.class.getName());
    private static final long MIN_POLL_MS = 1L;
    private static final long MAX_POLL_MS = 50L;
    private static final int MAX_POLL_BATCH = 100;
    private static final int DEFAULT_MAX_RETRIES = 5;
    private static final long BASE_BACKOFF_MS = 100;
    private static final long MAX_BACKOFF_MS = 10_000;
    private static final long CONSUMER_TIMEOUT_MS = 60_000L;
    private static final long IDLE_CHECK_INTERVAL_MS = 10_000L;
    /// #654: bounds the final-commit batch in [#close] so a wedged or slow consensus write cannot
    /// hold node shutdown. Well under [org.pragmatica.consensus.rabia.ProtocolConfig#DEFAULT_APPLY_TIMEOUT]
    /// (30s) — a commit that would still succeed given the full apply timeout is treated as failed
    /// for THIS shutdown and reported via [#cursorCommitFailureCount] / [ConsumerState#lastCursorCommitFailure]
    /// rather than holding the node. [design intent — unverified: 5s is not derived from a measured
    /// commit-latency distribution, it is a judgment call reviewed and accepted for this fix].
    private static final TimeSpan CURSOR_COMMIT_SHUTDOWN_BOUND = timeSpan(5).seconds();

    private final StreamPartitionManager partitionManager;
    private final DeadLetterHandler dlHandler;
    private final Option<ConsumerCursorStore> cursorStore;
    private final Option<TransactionalCursorCommit> transactionalCommit;
    private final PartitionReader reader;
    private final ConcurrentHashMap<ConsumerKey, ConsumerState> consumers = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ScheduledFuture<?> idleConsumerChecker;
    /// #654: node-wide count of cursor commits (final flush or periodic checkpoint) that resolved
    /// with a failure or never settled at all. Survives a consumer's removal from [#consumers] at
    /// detach, which a per-consumer-only field could not. Exposed via [#cursorCommitFailureCount].
    private final AtomicLong cursorCommitFailureCount = new AtomicLong(0);

    ConsumerRuntimeState(StreamPartitionManager partitionManager, DeadLetterHandler dlHandler) {
        this(partitionManager, dlHandler, none(), none());
    }

    ConsumerRuntimeState(StreamPartitionManager partitionManager,
                         DeadLetterHandler dlHandler,
                         Option<ConsumerCursorStore> cursorStore) {
        this(partitionManager, dlHandler, cursorStore, none());
    }

    ConsumerRuntimeState(StreamPartitionManager partitionManager,
                         DeadLetterHandler dlHandler,
                         Option<ConsumerCursorStore> cursorStore,
                         Option<TransactionalCursorCommit> transactionalCommit) {
        this(partitionManager,
             dlHandler,
             cursorStore,
             transactionalCommit,
             StreamConsumerRuntime.localPartitionReader(partitionManager));
    }

    ConsumerRuntimeState(StreamPartitionManager partitionManager,
                         DeadLetterHandler dlHandler,
                         Option<ConsumerCursorStore> cursorStore,
                         Option<TransactionalCursorCommit> transactionalCommit,
                         PartitionReader reader) {
        this.partitionManager = partitionManager;
        this.dlHandler = dlHandler;
        this.cursorStore = cursorStore;
        this.transactionalCommit = transactionalCommit;
        this.reader = reader;
        this.idleConsumerChecker = SharedScheduler.scheduleAtFixedRate(this::reapIdleConsumers,
                                                                       TimeSpan.timeSpan(IDLE_CHECK_INTERVAL_MS).millis());
    }

    @Override
    public Result<Unit> subscribe(String streamName, int partition, ConsumerConfig config, ConsumerCallback callback) {
        return subscribe(streamName, partition, config, callback, IdlePolicy.REAP_WHEN_IDLE);
    }

    @SuppressWarnings("JBCT-NULL-01")
    @Override
    public Result<Unit> subscribe(String streamName,
                                  int partition,
                                  ConsumerConfig config,
                                  ConsumerCallback callback,
                                  IdlePolicy idlePolicy) {
        if (closed.get()) {
            return StreamError.General.CONSUMER_RUNTIME_CLOSED.result();
        }

        var key = ConsumerKey.consumerKey(streamName, partition, config.groupId());
        var state = ConsumerState.consumerState(config, callback, 0L, idlePolicy);

        if (consumers.putIfAbsent(key, state) != null) {
            return StreamError.General.CONSUMER_ALREADY_SUBSCRIBED.result();
        }

        loadCursorAndStart(key, state, config.groupId(), streamName, partition);

        return success(unit());
    }

    @Override
    public List<SubscriptionSnapshot> subscriptions() {
        return consumers.entrySet()
                        .stream()
                        .map(entry -> toSnapshot(entry.getKey(),
                                                 entry.getValue()))
                        .toList();
    }

    @Override
    public long cursorCommitFailureCount() {
        return cursorCommitFailureCount.get();
    }

    private static SubscriptionSnapshot toSnapshot(ConsumerKey key, ConsumerState state) {
        return new SubscriptionSnapshot(key.streamName(),
                                        key.partition(),
                                        key.groupId(),
                                        state.cursor(),
                                        state.isStalled(),
                                        state.idlePolicy(),
                                        state.lastCursorCommitFailure());
    }

    @Override
    public Result<Unit> unsubscribe(String streamName, int partition, String consumerGroup) {
        var key = ConsumerKey.consumerKey(streamName, partition, consumerGroup);

        return option(consumers.remove(key)).toResult(StreamError.General.CONSUMER_NOT_FOUND)
                     .onSuccess(state -> cleanupConsumer(key, state))
                     .mapToUnit();
    }

    private void cleanupConsumer(ConsumerKey key, ConsumerState state) {
        flushCursorForKey(key, state);
        removePushListener(key, state);
        state.cancel();
    }

    @Override
    public Option<Long> cursorPosition(String streamName, int partition, String consumerGroup) {
        var key = ConsumerKey.consumerKey(streamName, partition, consumerGroup);

        return option(consumers.get(key)).map(ConsumerState::cursor);
    }

    @Override
    public Option<TransactionalCursorCommit> transactionalCursorCommit() {
        return transactionalCommit;
    }

    @Override
    public DeadLetterHandler deadLetterHandler() {
        return dlHandler;
    }

    @Contract
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            idleConsumerChecker.cancel(false);
            awaitFinalCursorCommits();
            consumers.forEach(this::removePushListener);
            consumers.values().forEach(ConsumerState::cancel);
            consumers.clear();
        }
    }

    /// #654: batches every consumer's final cursor commit into one bounded wait so a slow or wedged
    /// consensus write cannot hold node stop past [#CURSOR_COMMIT_SHUTDOWN_BOUND] — this runs inside
    /// the #488 ordering window, while [#partitionManager] and the cursor store are still alive.
    /// Each commit's [#observedCommit] handlers are attached BEFORE batching, so a commit that only
    /// resolves after the bound is still logged and counted: [Promise#await(TimeSpan)] leaves an
    /// unresolved promise running rather than cancelling it, and `onSuccess`/`onFailure` return the
    /// same promise instance, so batching for the bound changes nothing about when those handlers fire.
    @TerminalOperation
    private void awaitFinalCursorCommits() {
        var commits = new ArrayList<Promise<Unit>>(consumers.size());

        consumers.forEach((key, state) -> commits.add(flushCursorForKey(key, state)));
        if (commits.isEmpty()) {
            return;
        }

        Promise.allOf(commits)
               .await(CURSOR_COMMIT_SHUTDOWN_BOUND)
               .onFailure(cause -> LOG.log(System.Logger.Level.WARNING,
                                           "{0} cursor commit(s) did not settle within the {1}ms shutdown bound ({2}); "
                                          + "node stop is continuing, late results are still logged and counted",
                                           commits.size(),
                                           CURSOR_COMMIT_SHUTDOWN_BOUND.millis(),
                                           cause.message()));
    }

    private void reapIdleConsumers() {
        reapIdleConsumers(System.currentTimeMillis());
    }

    /// Time-injected seam: the reap threshold is 60s, so a test that waited for wall-clock would be
    /// both slow and flaky. Package-private for [StreamConsumerRuntimeTest].
    @Contract
    void reapIdleConsumers(long now) {
        if (closed.get()) {
            return;
        }

        consumers.forEach((key, state) -> reapIfIdleConsumer(key, state, now));
    }

    private void reapIfIdleConsumer(ConsumerKey key, ConsumerState state, long now) {
        if (state.idlePolicy() == IdlePolicy.KEEP_UNTIL_UNSUBSCRIBED) {
            return;
        }

        var elapsed = now - state.lastPollTime();

        if (elapsed <= CONSUMER_TIMEOUT_MS) {
            return;
        }

        LOG.log(System.Logger.Level.INFO, "Auto-unsubscribed idle consumer {0} (no poll for {1}ms)", key, elapsed);
        unsubscribe(key.streamName(), key.partition(), key.groupId());
    }

    private void loadCursorAndStart(ConsumerKey key,
                                    ConsumerState state,
                                    String groupId,
                                    String streamName,
                                    int partition) {
        cursorStore.onPresent(store -> store.fetch(groupId, streamName, partition)
                                            .onResult(result -> applyCursorAndStart(result, key, state)))
                   .onEmpty(() -> startConsumer(key, state));
    }

    private void applyCursorAndStart(Result<Option<Long>> result, ConsumerKey key, ConsumerState state) {
        result.onSuccess(opt -> opt.onPresent(state::advanceCursor));
        startConsumer(key, state);
    }

    private void startConsumer(ConsumerKey key, ConsumerState state) {
        if (closed.get() || state.isCancelled()) {
            return;
        }

        state.markCursorInitialized();
        subscribePushOrPoll(key, state);
    }

    /// #654: the final commit for one consumer at detach (either interactive [#unsubscribe] or batch
    /// [#close]). Returns the observed commit so [#awaitFinalCursorCommits] can bound-await the whole
    /// batch; [#cleanupConsumer]'s call discards the same return value on purpose — a single
    /// interactive detach does not gate node shutdown, so nothing there needs to await it, and the
    /// failure is already logged/counted inside [#observedCommit] regardless of who awaits.
    private Promise<Unit> flushCursorForKey(ConsumerKey key, ConsumerState state) {
        if (!state.cursorInitialized()) {
            return Promise.unitPromise();
        }

        return observedCommit(key, state);
    }

    private void subscribePushOrPoll(ConsumerKey key, ConsumerState state) {
        partitionManager.partitionBuffer(key.streamName(),
                                         key.partition())
                        .onPresent(buffer -> registerPushListener(buffer, key, state))
                        .onEmpty(() -> scheduleNextPoll(key, state));
    }

    private void registerPushListener(OffHeapRingBuffer buffer, ConsumerKey key, ConsumerState state) {
        LongConsumer listener = _ -> onAppend(key, state);

        state.pushListener(listener);
        buffer.addAppendListener(listener);
    }

    private void removePushListener(ConsumerKey key, ConsumerState state) {
        state.pushListener()
             .onPresent(listener -> partitionManager.partitionBuffer(key.streamName(),
                                                                     key.partition())
                                                    .onPresent(buffer -> buffer.removeAppendListener(listener)));
    }

    private void checkpointIfNeeded(ConsumerKey key, ConsumerState state) {
        if (state.shouldCheckpoint()) {
            observedCommit(key, state);
            state.resetCheckpointCounters();
        }
    }

    /// #654: the single place that performs a cursor commit and observes its outcome — shared by the
    /// detach paths ([#flushCursorForKey]) and the periodic path ([#checkpointIfNeeded]), closing the
    /// discard defect for both call sites at once. A missing [#cursorStore] (no persistence configured)
    /// has nothing to commit and nothing to fail, so it resolves the fallback unit promise rather than
    /// going through [#onCursorCommitFailure].
    private Promise<Unit> observedCommit(ConsumerKey key, ConsumerState state) {
        return cursorStore.map(store -> store.commit(key.groupId(),
                                                     key.streamName(),
                                                     key.partition(),
                                                     state.cursor())
                                             .onSuccess(_ -> state.clearCursorCommitFailure())
                                             .onFailure(cause -> onCursorCommitFailure(key, state, cause)))
                          .or(Promise.unitPromise());
    }

    private void onCursorCommitFailure(ConsumerKey key, ConsumerState state, Cause cause) {
        cursorCommitFailureCount.incrementAndGet();
        state.recordCursorCommitFailure(cause.message());
        LOG.log(System.Logger.Level.ERROR,
                "Cursor commit failed for consumer group {0} on stream {1} partition {2}: {3}",
                key.groupId(),
                key.streamName(),
                key.partition(),
                cause.message());
    }

    private void scheduleNextPoll(ConsumerKey key, ConsumerState state) {
        if (state.isCancelled()) {
            return;
        }

        var delay = TimeSpan.timeSpan(state.currentPollMs.get()).millis();
        var future = SharedScheduler.schedule(() -> pollAndReschedule(key, state), delay);

        state.scheduledFuture(future);
    }

    /// The scheduled poll loop is SERIAL: the next poll is scheduled only once the current cycle —
    /// read AND delivery — has completed.
    ///
    /// The previous shape rescheduled eagerly, so a second poll could read from a cursor the first poll
    /// had not yet advanced and re-deliver the same events. That was harmless while every read was a
    /// synchronous local one, but it becomes a duplicate generator the moment a consumer assigned to a
    /// non-owner node reads THROUGH the owner (#535): those reads take a network round trip, during
    /// which the eager 1ms reschedule would stack further reads on the same offset.
    @Contract
    private void pollAndReschedule(ConsumerKey key, ConsumerState state) {
        pollCycle(key, state).onFailure(cause -> logPollFailure(key, cause)).onResult(_ -> scheduleNextPoll(key, state));
    }

    /// Push-listener entry point. An append notification is fire-and-forget by construction — the ring
    /// buffer hands out a `LongConsumer` — so the cycle's outcome has no caller to return to and is
    /// logged instead. Only reachable when the ring IS local, where the read resolves without I/O.
    @Contract
    private void onAppend(ConsumerKey key, ConsumerState state) {
        pollCycle(key, state).onFailure(cause -> logPollFailure(key, cause));
    }

    /// One poll cycle: read the partition, then deliver what came back. The returned promise resolves
    /// when the cycle is DONE. It fails only when the READ failed; a DELIVERY failure is handled by the
    /// consumer's error strategy ([#handleDeliveryFailure]) and deliberately never surfaces here, so a
    /// handler error cannot be mistaken for an unreachable partition.
    private Promise<Unit> pollCycle(ConsumerKey key, ConsumerState state) {
        if (closed.get() || state.isCancelled() || state.isStalled() || state.isDeadLetterInFlight()) {
            return Promise.unitPromise();
        }

        state.touchLastPollTime();

        return reader.read(key.streamName(),
                           key.partition(),
                           state.cursor(),
                           MAX_POLL_BATCH)
                     .fold(result -> result.fold(cause -> pollFailed(state, cause),
                                                 events -> pollSucceeded(key, state, events)));
    }

    private Promise<Unit> pollSucceeded(ConsumerKey key, ConsumerState state, List<OffHeapRingBuffer.RawEvent> events) {
        state.adjustPollInterval(!events.isEmpty());

        return deliverEvents(key, state, events);
    }

    /// Back off on failure too, not just on an empty successful read.
    ///
    /// `currentPollMs` starts at `MIN_POLL_MS` (1ms) and previously only ever grew on a successful
    /// read, so a consumer whose partition is not materialized locally — `readLocal` fails with
    /// `PARTITION_NOT_LOCAL` — rescheduled every millisecond forever, ~1000 wakeups/s per consumer,
    /// each on its own virtual thread. The declarative path (#488) can enter that window legitimately:
    /// HRW can name this node OWNER of a partition whose ring is still materializing, so the poll path
    /// is reachable before the push listener exists.
    private Promise<Unit> pollFailed(ConsumerState state, Cause cause) {
        state.adjustPollInterval(false);

        return cause.promise();
    }

    private Promise<Unit> deliverEvents(ConsumerKey key, ConsumerState state, List<OffHeapRingBuffer.RawEvent> events) {
        return deliverNextEvent(key, state, events, 0).fold(_ -> Promise.unitPromise());
    }

    private Promise<Unit> deliverNextEvent(ConsumerKey key,
                                           ConsumerState state,
                                           List<OffHeapRingBuffer.RawEvent> events,
                                           int index) {
        if (index >= events.size() || state.isCancelled() || state.isStalled() || state.isDeadLetterInFlight()) {
            return Promise.unitPromise();
        }

        return deliverSingleEvent(key, state, events.get(index)).flatMap(_ -> deliverNextEvent(key,
                                                                                               state,
                                                                                               events,
                                                                                               index + 1));
    }

    private Promise<Unit> deliverSingleEvent(ConsumerKey key, ConsumerState state, OffHeapRingBuffer.RawEvent event) {
        return state.callback()
                    .onEvent(event.offset(),
                             event.data(),
                             event.timestamp())
                    .onSuccess(_ -> advanceCursor(key,
                                                  state,
                                                  event.offset()))
                    .onFailure(cause -> handleDeliveryFailure(key,
                                                              state,
                                                              event,
                                                              cause.message()));
    }

    private void advanceCursor(ConsumerKey key, ConsumerState state, long offset) {
        state.advanceCursor(offset + 1);
        state.resetRetryCount();
        state.incrementEventsSinceCheckpoint();
        checkpointIfNeeded(key, state);
    }

    private void handleDeliveryFailure(ConsumerKey key,
                                       ConsumerState state,
                                       OffHeapRingBuffer.RawEvent event,
                                       String errorMessage) {
        switch (state.errorStrategy()) {
            case RETRY -> handleRetry(key, state, event, errorMessage);
            case SKIP -> handleSkip(key, state, event, errorMessage);
            case STALL -> handleStall(key, state, event, errorMessage);
        }
    }

    private void handleRetry(ConsumerKey key,
                             ConsumerState state,
                             OffHeapRingBuffer.RawEvent event,
                             String errorMessage) {
        var attempt = state.incrementRetryCount();

        if (attempt >= state.maxRetries()) {
            appendDeadLetterThenAdvance(key, state, event, errorMessage, attempt, 1);

            return;
        }

        var backoffMs = computeBackoff(attempt);
        var delay = TimeSpan.timeSpan(backoffMs).millis();

        SharedScheduler.schedule(() -> retryDeliverEvent(key, state, event), delay);
    }

    private void retryDeliverEvent(ConsumerKey key, ConsumerState state, OffHeapRingBuffer.RawEvent event) {
        if (state.isCancelled() || state.isStalled()) {
            return;
        }

        state.callback()
             .onEvent(event.offset(),
                      event.data(),
                      event.timestamp())
             .onSuccess(_ -> advanceCursor(key,
                                           state,
                                           event.offset()))
             .onFailure(cause -> handleRetryFailureAgain(key,
                                                         state,
                                                         event,
                                                         cause.message()));
    }

    private void handleRetryFailureAgain(ConsumerKey key,
                                         ConsumerState state,
                                         OffHeapRingBuffer.RawEvent event,
                                         String errorMessage) {
        var attempt = state.incrementRetryCount();

        if (attempt >= state.maxRetries()) {
            appendDeadLetterThenAdvance(key, state, event, errorMessage, attempt, 1);

            return;
        }

        var backoffMs = computeBackoff(attempt);

        SharedScheduler.schedule(() -> retryDeliverEvent(key, state, event),
                                 TimeSpan.timeSpan(backoffMs).millis());
    }

    private void handleSkip(ConsumerKey key,
                            ConsumerState state,
                            OffHeapRingBuffer.RawEvent event,
                            String errorMessage) {
        LOG.log(System.Logger.Level.WARNING,
                "Skipping failed event at {0}[{1}]@{2}: {3}",
                key.streamName(),
                key.partition(),
                event.offset(),
                errorMessage);
        appendDeadLetterThenAdvance(key, state, event, errorMessage, 1, 1);
    }

    private void handleStall(ConsumerKey key,
                             ConsumerState state,
                             OffHeapRingBuffer.RawEvent event,
                             String errorMessage) {
        LOG.log(System.Logger.Level.ERROR,
                "Consumer stalled at {0}[{1}]@{2}: {3}",
                key.streamName(),
                key.partition(),
                event.offset(),
                errorMessage);
        state.stall();
    }

    /// Dead-lettering an event and advancing past it is ONE unit: the cursor moves only after the
    /// sink has accepted the entry (durable-pubsub-spec §9 — no event is skipped past a sink that
    /// has not stored it). While the append is unresolved the partition's delivery loop is held by
    /// [ConsumerState#isDeadLetterInFlight] — without that guard the next poll cycle would re-read
    /// from the un-advanced cursor and re-deliver the exhausted event to the handler. A failed
    /// append retries with backoff indefinitely: capping and advancing anyway would BE the silent
    /// loss this contract exists to prevent; the stall is deliberate and operator-visible via the
    /// held cursor (the §9 `DLQ_STALL` alarm surface arrives with the D3 batch).
    private void appendDeadLetterThenAdvance(ConsumerKey key,
                                             ConsumerState state,
                                             OffHeapRingBuffer.RawEvent event,
                                             String errorMessage,
                                             int attemptCount,
                                             int appendAttempt) {
        state.markDeadLetterInFlight();
        dlHandler.append(key.streamName(),
                         key.partition(),
                         event.offset(),
                         key.groupId(),
                         event.data(),
                         errorMessage,
                         attemptCount)
                 .onSuccess(_ -> completeDeadLetter(key, state, event))
                 .onFailure(cause -> retryDeadLetterAppend(key,
                                                           state,
                                                           event,
                                                           errorMessage,
                                                           attemptCount,
                                                           appendAttempt,
                                                           cause));
    }

    private void completeDeadLetter(ConsumerKey key, ConsumerState state, OffHeapRingBuffer.RawEvent event) {
        advanceCursor(key, state, event.offset());
        state.clearDeadLetterInFlight();
        resumeAfterDeadLetter(key, state);
    }

    /// A push-mode consumer is only ever driven by append notifications, and every notification
    /// that arrived while the dead-letter append was in flight was absorbed by the guard — so
    /// releasing the hold must also re-drive the loop, or events already in the ring sit
    /// undelivered until the NEXT append happens to arrive. Poll-mode consumers resume on their
    /// own schedule and treat this as one extra cycle.
    @Contract
    private void resumeAfterDeadLetter(ConsumerKey key, ConsumerState state) {
        pollCycle(key, state).onFailure(cause -> logPollFailure(key, cause));
    }

    private void retryDeadLetterAppend(ConsumerKey key,
                                       ConsumerState state,
                                       OffHeapRingBuffer.RawEvent event,
                                       String errorMessage,
                                       int attemptCount,
                                       int appendAttempt,
                                       Cause cause) {
        LOG.log(System.Logger.Level.WARNING,
                "Dead-letter append failed for {0}[{1}]@{2} (attempt {3}), holding cursor: {4}",
                key.streamName(),
                key.partition(),
                event.offset(),
                appendAttempt,
                cause.message());
        if (state.isCancelled()) {
            return;
        }
        // computeBackoff shifts 1L << (attempt - 1); an uncapped attempt count overflows the shift
        // at 64 and the min() then picks the negative product, so the argument is clamped below it.
        var cappedAttempt = Math.min(appendAttempt, 30);

        SharedScheduler.schedule(() -> appendDeadLetterThenAdvance(key,
                                                                   state,
                                                                   event,
                                                                   errorMessage,
                                                                   attemptCount,
                                                                   appendAttempt + 1),
                                 TimeSpan.timeSpan(computeBackoff(cappedAttempt)).millis());
    }

    private static long computeBackoff(int attempt) {
        var base = Math.min(BASE_BACKOFF_MS * (1L<< (attempt - 1)), MAX_BACKOFF_MS);

        return JitterUtil.applyJitter(base, JitterUtil.MIN_FACTOR_DEFAULT, JitterUtil.MAX_FACTOR_DEFAULT);
    }

    private static void logPollFailure(ConsumerKey key, Cause cause) {
        LOG.log(System.Logger.Level.DEBUG,
                "Poll failed for {0}[{1}]: {2}",
                key.streamName(),
                key.partition(),
                cause.message());
    }

    record ConsumerKey(String streamName, int partition, String groupId) {
        static ConsumerKey consumerKey(String streamName, int partition, String groupId) {
            return new ConsumerKey(streamName, partition, groupId);
        }
    }

    static final class ConsumerState {
        private static final long CHECKPOINT_EVENT_THRESHOLD = 1000;

        private final ConsumerConfig config;
        private final ConsumerCallback callback;
        private final IdlePolicy idlePolicy;
        private final AtomicLong cursor;
        private final AtomicLong eventsSinceCheckpoint = new AtomicLong(0);
        private final AtomicInteger retryCount = new AtomicInteger(0);
        private final AtomicBoolean stalled = new AtomicBoolean(false);
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicBoolean cursorInitialized = new AtomicBoolean(false);
        private final AtomicBoolean deadLetterInFlight = new AtomicBoolean(false);
        private volatile ScheduledFuture<?> future;
        private volatile LongConsumer pushListenerRef;
        private final AtomicLong currentPollMs = new AtomicLong(MIN_POLL_MS);
        private final AtomicLong lastCheckpointTime = new AtomicLong(System.currentTimeMillis());
        private final AtomicLong lastPollTime = new AtomicLong(System.currentTimeMillis());
        /// #654: detail of the most recent cursor commit failure for this consumer, cleared on the
        /// next successful commit. Read by [ConsumerRuntimeState#toSnapshot] onto
        /// [StreamConsumerRuntime.SubscriptionSnapshot] while this consumer is still attached — most
        /// useful for a periodic [ConsumerRuntimeState#checkpointIfNeeded] failure, since detach
        /// removes the entry from [ConsumerRuntimeState#consumers] and this per-consumer detail goes
        /// with it. What survives detach is the node-wide [ConsumerRuntimeState#cursorCommitFailureCount].
        private volatile String lastCursorCommitFailure;

        private ConsumerState(ConsumerConfig config,
                              ConsumerCallback callback,
                              long initialCursor,
                              IdlePolicy idlePolicy) {
            this.config = config;
            this.callback = callback;
            this.idlePolicy = idlePolicy;
            this.cursor = new AtomicLong(initialCursor);
        }

        static ConsumerState consumerState(ConsumerConfig config,
                                           ConsumerCallback callback,
                                           long initialCursor,
                                           IdlePolicy idlePolicy) {
            return new ConsumerState(config, callback, initialCursor, idlePolicy);
        }

        IdlePolicy idlePolicy() {
            return idlePolicy;
        }

        ConsumerCallback callback() {
            return callback;
        }

        ErrorStrategy errorStrategy() {
            return config.errorStrategy();
        }

        int maxRetries() {
            return config.maxRetries() > 0
                   ? config.maxRetries()
                   : DEFAULT_MAX_RETRIES;
        }

        long cursor() {
            return cursor.get();
        }

        @Contract
        void advanceCursor(long offset) {
            cursor.set(offset);
        }

        @Contract
        void markCursorInitialized() {
            cursorInitialized.set(true);
        }

        boolean cursorInitialized() {
            return cursorInitialized.get();
        }

        @Contract
        void resetRetryCount() {
            retryCount.set(0);
        }

        @Contract
        void adjustPollInterval(boolean hasData) {
            currentPollMs.set(hasData
                              ? MIN_POLL_MS
                              : Math.min(currentPollMs.get() * 2, MAX_POLL_MS));
        }

        int incrementRetryCount() {
            return retryCount.incrementAndGet();
        }

        @Contract
        void incrementEventsSinceCheckpoint() {
            eventsSinceCheckpoint.incrementAndGet();
        }

        /// Time half honors the DECLARED `checkpointInterval` (previously inert — read by nothing;
        /// safe to honor because #576's validator rejects non-default declarative values, so only
        /// the 1s factory default and explicit programmatic values exist). The event half stays at
        /// the class constant: a cadence-dominated bound, tightened per durable-pubsub-spec §7 for
        /// durable-topic groups by their 500ms attach-time interval.
        boolean shouldCheckpoint() {
            return eventsSinceCheckpoint.get() >= CHECKPOINT_EVENT_THRESHOLD || (System.currentTimeMillis() - lastCheckpointTime.get()) >= config.checkpointInterval()
                                                                                                                                                 .millis();
        }

        @Contract
        void resetCheckpointCounters() {
            eventsSinceCheckpoint.set(0);
            lastCheckpointTime.set(System.currentTimeMillis());
        }

        boolean isStalled() {
            return stalled.get();
        }

        /// True while a dead-letter append for this consumer's current head event is unresolved.
        /// Holds the delivery loop so the un-advanced cursor cannot re-deliver the exhausted event
        /// (see [ConsumerRuntimeState#appendDeadLetterThenAdvance]). Cleared strictly AFTER the
        /// cursor advance, so a loop that observes the flag clear always reads the moved cursor.
        boolean isDeadLetterInFlight() {
            return deadLetterInFlight.get();
        }

        @Contract
        void markDeadLetterInFlight() {
            deadLetterInFlight.set(true);
        }

        @Contract
        void clearDeadLetterInFlight() {
            deadLetterInFlight.set(false);
        }

        @Contract
        void stall() {
            stalled.set(true);
        }

        boolean isCancelled() {
            return cancelled.get();
        }

        @Contract
        void scheduledFuture(ScheduledFuture<?> future) {
            this.future = future;
        }

        @Contract
        void pushListener(LongConsumer listener) {
            this.pushListenerRef = listener;
        }

        Option<LongConsumer> pushListener() {
            return option(pushListenerRef);
        }

        @Contract
        void touchLastPollTime() {
            lastPollTime.set(System.currentTimeMillis());
        }

        long lastPollTime() {
            return lastPollTime.get();
        }

        @Contract
        void cancel() {
            cancelled.set(true);
            option(future).onPresent(f -> f.cancel(false));
        }

        Option<String> lastCursorCommitFailure() {
            return option(lastCursorCommitFailure);
        }

        @Contract
        void recordCursorCommitFailure(String detail) {
            lastCursorCommitFailure = detail;
        }

        @Contract
        void clearCursorCommitFailure() {
            lastCursorCommitFailure = null;
        }
    }
}
