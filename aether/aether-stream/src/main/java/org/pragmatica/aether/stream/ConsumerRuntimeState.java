// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

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
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.JitterUtil;
import org.pragmatica.lang.utils.SharedScheduler;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Unit.unit;


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

    private final StreamPartitionManager partitionManager;
    private final DeadLetterHandler dlHandler;
    private final Option<ConsumerCursorStore> cursorStore;
    private final Option<TransactionalCursorCommit> transactionalCommit;
    private final ConcurrentHashMap<ConsumerKey, ConsumerState> consumers = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ScheduledFuture<?> idleConsumerChecker;

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
        this.partitionManager = partitionManager;
        this.dlHandler = dlHandler;
        this.cursorStore = cursorStore;
        this.transactionalCommit = transactionalCommit;
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

    private static SubscriptionSnapshot toSnapshot(ConsumerKey key, ConsumerState state) {
        return new SubscriptionSnapshot(key.streamName(),
                                        key.partition(),
                                        key.groupId(),
                                        state.cursor(),
                                        state.isStalled(),
                                        state.idlePolicy());
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
            consumers.forEach(this::flushCursorForKey);
            consumers.forEach(this::removePushListener);
            consumers.values().forEach(ConsumerState::cancel);
            consumers.clear();
        }
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

    private void flushCursorForKey(ConsumerKey key, ConsumerState state) {
        if (!state.cursorInitialized()) {
            return;
        }

        cursorStore.onPresent(store -> store.commit(key.groupId(), key.streamName(), key.partition(), state.cursor()));
    }

    private void subscribePushOrPoll(ConsumerKey key, ConsumerState state) {
        partitionManager.partitionBuffer(key.streamName(),
                                         key.partition())
                        .onPresent(buffer -> registerPushListener(buffer, key, state))
                        .onEmpty(() -> scheduleNextPoll(key, state));
    }

    private void registerPushListener(OffHeapRingBuffer buffer, ConsumerKey key, ConsumerState state) {
        LongConsumer listener = _ -> pollPartition(key, state);

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
            cursorStore.onPresent(store -> store.commit(key.groupId(), key.streamName(), key.partition(), state.cursor()));
            state.resetCheckpointCounters();
        }
    }

    private void scheduleNextPoll(ConsumerKey key, ConsumerState state) {
        if (state.isCancelled()) {
            return;
        }

        var delay = TimeSpan.timeSpan(state.currentPollMs.get()).millis();
        var future = SharedScheduler.schedule(() -> pollAndReschedule(key, state), delay);

        state.scheduledFuture(future);
    }

    private void pollAndReschedule(ConsumerKey key, ConsumerState state) {
        pollPartition(key, state);
        scheduleNextPoll(key, state);
    }

    private void pollPartition(ConsumerKey key, ConsumerState state) {
        if (closed.get() || state.isCancelled() || state.isStalled()) {
            return;
        }

        state.touchLastPollTime();
        var fromOffset = state.cursor();

        partitionManager.readLocal(key.streamName(),
                                   key.partition(),
                                   fromOffset,
                                   MAX_POLL_BATCH)
                        .onSuccess(events -> handlePollSuccess(key, state, events))
                        .onFailure(cause -> handlePollFailure(key, state, cause));
    }

    private void handlePollSuccess(ConsumerKey key, ConsumerState state, List<OffHeapRingBuffer.RawEvent> events) {
        state.adjustPollInterval(!events.isEmpty());
        deliverEvents(key, state, events);
    }

    /// Back off on failure too, not just on an empty successful read.
    ///
    /// `currentPollMs` starts at `MIN_POLL_MS` (1ms) and previously only ever grew inside
    /// [#handlePollSuccess], so a consumer whose partition is not materialized locally — `readLocal`
    /// fails with `PARTITION_NOT_LOCAL` — rescheduled every millisecond forever, ~1000 wakeups/s per
    /// consumer, each on its own virtual thread. The declarative path (#488) can enter that window
    /// legitimately: HRW can name this node OWNER of a partition whose ring is still materializing, so
    /// the poll path is reachable before the push listener exists.
    private void handlePollFailure(ConsumerKey key, ConsumerState state, Cause cause) {
        state.adjustPollInterval(false);
        logPollFailure(key, cause);
    }

    private void deliverEvents(ConsumerKey key, ConsumerState state, List<OffHeapRingBuffer.RawEvent> events) {
        deliverNextEvent(key, state, events, 0);
    }

    private void deliverNextEvent(ConsumerKey key,
                                  ConsumerState state,
                                  List<OffHeapRingBuffer.RawEvent> events,
                                  int index) {
        if (index >= events.size() || state.isCancelled() || state.isStalled()) {
            return;
        }

        deliverSingleEvent(key, state, events.get(index)).onSuccess(_ -> deliverNextEvent(key, state, events, index + 1));
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
            recordDeadLetter(key, event, errorMessage, attempt);
            advanceCursor(key, state, event.offset());

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
            recordDeadLetter(key, event, errorMessage, attempt);
            advanceCursor(key, state, event.offset());

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
        recordDeadLetter(key, event, errorMessage, 1);
        advanceCursor(key, state, event.offset());
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

    private void recordDeadLetter(ConsumerKey key,
                                  OffHeapRingBuffer.RawEvent event,
                                  String errorMessage,
                                  int attemptCount) {
        dlHandler.record(key.streamName(), key.partition(), event.offset(), event.data(), errorMessage, attemptCount);
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
        private static final long CHECKPOINT_TIME_THRESHOLD_MS = 30_000;

        private final ConsumerConfig config;
        private final ConsumerCallback callback;
        private final IdlePolicy idlePolicy;
        private final AtomicLong cursor;
        private final AtomicLong eventsSinceCheckpoint = new AtomicLong(0);
        private final AtomicInteger retryCount = new AtomicInteger(0);
        private final AtomicBoolean stalled = new AtomicBoolean(false);
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicBoolean cursorInitialized = new AtomicBoolean(false);
        private volatile ScheduledFuture<?> future;
        private volatile LongConsumer pushListenerRef;
        private final AtomicLong currentPollMs = new AtomicLong(MIN_POLL_MS);
        private final AtomicLong lastCheckpointTime = new AtomicLong(System.currentTimeMillis());
        private final AtomicLong lastPollTime = new AtomicLong(System.currentTimeMillis());

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

        boolean shouldCheckpoint() {
            return eventsSinceCheckpoint.get() >= CHECKPOINT_EVENT_THRESHOLD || (System.currentTimeMillis() - lastCheckpointTime.get()) >= CHECKPOINT_TIME_THRESHOLD_MS;
        }

        @Contract
        void resetCheckpointCounters() {
            eventsSinceCheckpoint.set(0);
            lastCheckpointTime.set(System.currentTimeMillis());
        }

        boolean isStalled() {
            return stalled.get();
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
    }
}
