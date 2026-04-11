package org.pragmatica.aether.stream;

import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.slice.ConsumerConfig.ErrorStrategy;
import org.pragmatica.aether.stream.consumer.TransactionalCursorCommit;
import org.pragmatica.aether.stream.segment.CursorStore;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Unit.unit;


/// Package-private implementation of StreamConsumerRuntime.
/// Manages polling lifecycle and error handling for all subscribed consumers.
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
    private final Option<CursorStore> cursorStore;
    private final Option<TransactionalCursorCommit> transactionalCommit;

    private final ConcurrentHashMap<ConsumerKey, ConsumerState> consumers = new ConcurrentHashMap<>();

    private final AtomicBoolean closed = new AtomicBoolean(false);

    private final ScheduledFuture<?> idleConsumerChecker;

    ConsumerRuntimeState(StreamPartitionManager partitionManager, DeadLetterHandler dlHandler) {
        this(partitionManager, dlHandler, none(), none());
    }

    ConsumerRuntimeState(StreamPartitionManager partitionManager,
                         DeadLetterHandler dlHandler,
                         Option<CursorStore> cursorStore) {
        this(partitionManager, dlHandler, cursorStore, none());
    }

    ConsumerRuntimeState(StreamPartitionManager partitionManager,
                         DeadLetterHandler dlHandler,
                         Option<CursorStore> cursorStore,
                         Option<TransactionalCursorCommit> transactionalCommit) {
        this.partitionManager = partitionManager;
        this.dlHandler = dlHandler;
        this.cursorStore = cursorStore;
        this.transactionalCommit = transactionalCommit;
        this.idleConsumerChecker = SharedScheduler.scheduleAtFixedRate(this::reapIdleConsumers,
                                                                       TimeSpan.timeSpan(IDLE_CHECK_INTERVAL_MS)
                                                                                        .millis());
    }

    @Override public Result<Unit> subscribe(String streamName,
                                            int partition,
                                            ConsumerConfig config,
                                            ConsumerCallback callback) {
        if (closed.get()) {return StreamError.General.CONSUMER_RUNTIME_CLOSED.result();}
        var key = ConsumerKey.consumerKey(streamName, partition, config.groupId());
        var initialCursor = loadInitialCursor(config.groupId(), streamName, partition);
        var state = ConsumerState.consumerState(config, callback, initialCursor);
        if (consumers.putIfAbsent(key, state) != null) {return StreamError.General.CONSUMER_ALREADY_SUBSCRIBED.result();}
        subscribePushOrPoll(key, state);
        return success(unit());
    }

    @Override public Result<Unit> unsubscribe(String streamName, int partition, String consumerGroup) {
        var key = ConsumerKey.consumerKey(streamName, partition, consumerGroup);
        var state = consumers.remove(key);
        if (state == null) {return StreamError.General.CONSUMER_NOT_FOUND.result();}
        flushCursorForKey(key, state);
        removePushListener(key, state);
        state.cancel();
        return success(unit());
    }

    @Override public Option<Long> cursorPosition(String streamName, int partition, String consumerGroup) {
        var key = ConsumerKey.consumerKey(streamName, partition, consumerGroup);
        return option(consumers.get(key)).map(ConsumerState::cursor);
    }

    @Override public Option<TransactionalCursorCommit> transactionalCursorCommit() {
        return transactionalCommit;
    }

    @Override public DeadLetterHandler deadLetterHandler() {
        return dlHandler;
    }

    @Contract@Override public void close() {
        if (closed.compareAndSet(false, true)) {
            idleConsumerChecker.cancel(false);
            consumers.forEach(this::flushCursorForKey);
            consumers.forEach(this::removePushListener);
            consumers.values().forEach(ConsumerState::cancel);
            consumers.clear();
        }
    }

    private void reapIdleConsumers() {
        if (closed.get()) {return;}
        var now = System.currentTimeMillis();
        consumers.forEach((key, state) -> reapIfIdleConsumer(key, state, now));
    }

    private void reapIfIdleConsumer(ConsumerKey key, ConsumerState state, long now) {
        var elapsed = now - state.lastPollTime();
        if (elapsed <= CONSUMER_TIMEOUT_MS) {return;}
        LOG.log(System.Logger.Level.INFO, "Auto-unsubscribed idle consumer {0} (no poll for {1}ms)", key, elapsed);
        unsubscribe(key.streamName(), key.partition(), key.groupId());
    }

    private long loadInitialCursor(String groupId, String streamName, int partition) {
        return cursorStore.flatMap(store -> store.fetch(groupId, streamName, partition)).or(0L);
    }

    private void flushCursorForKey(ConsumerKey key, ConsumerState state) {
        cursorStore.onPresent(store -> store.commit(key.groupId(), key.streamName(), key.partition(), state.cursor()));
    }

    private void subscribePushOrPoll(ConsumerKey key, ConsumerState state) {
        partitionManager.partitionBuffer(key.streamName(),
                                         key.partition()).onPresent(buffer -> registerPushListener(buffer, key, state))
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
        if (state.isCancelled()) {return;}
        var delay = TimeSpan.timeSpan(state.currentPollMs).millis();
        var future = SharedScheduler.schedule(() -> pollAndReschedule(key, state), delay);
        state.scheduledFuture(future);
    }

    private void pollAndReschedule(ConsumerKey key, ConsumerState state) {
        pollPartition(key, state);
        scheduleNextPoll(key, state);
    }

    private void pollPartition(ConsumerKey key, ConsumerState state) {
        if (closed.get() || state.isCancelled() || state.isStalled()) {return;}
        state.touchLastPollTime();
        var fromOffset = state.cursor();
        partitionManager.readLocal(key.streamName(),
                                   key.partition(),
                                   fromOffset,
                                   MAX_POLL_BATCH).onSuccess(events -> handlePollSuccess(key, state, events))
                                  .onFailure(cause -> logPollFailure(key, cause));
    }

    private void handlePollSuccess(ConsumerKey key, ConsumerState state, List<OffHeapRingBuffer.RawEvent> events) {
        state.adjustPollInterval(!events.isEmpty());
        deliverEvents(key, state, events);
    }

    private void deliverEvents(ConsumerKey key, ConsumerState state, List<OffHeapRingBuffer.RawEvent> events) {
        for (var event : events) {
            if (state.isCancelled() || state.isStalled()) {return;}
            deliverSingleEvent(key, state, event);
        }
    }

    private void deliverSingleEvent(ConsumerKey key, ConsumerState state, OffHeapRingBuffer.RawEvent event) {
        state.callback().onEvent(event.offset(),
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
        switch (state.errorStrategy()){
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
        if (state.isCancelled() || state.isStalled()) {return;}
        state.callback().onEvent(event.offset(),
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
        return Math.min(BASE_BACKOFF_MS * (1L<<(attempt - 1)), MAX_BACKOFF_MS);
    }

    private static void logPollFailure(ConsumerKey key, org.pragmatica.lang.Cause cause) {
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
        private final AtomicLong cursor;

        private final AtomicLong eventsSinceCheckpoint = new AtomicLong(0);

        private final AtomicInteger retryCount = new AtomicInteger(0);

        private final AtomicBoolean stalled = new AtomicBoolean(false);

        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        private volatile ScheduledFuture<?> future;
        private volatile LongConsumer pushListenerRef;

        volatile long currentPollMs = MIN_POLL_MS;

        private volatile long lastCheckpointTime = System.currentTimeMillis();

        volatile long lastPollTime = System.currentTimeMillis();

        private ConsumerState(ConsumerConfig config, ConsumerCallback callback, long initialCursor) {
            this.config = config;
            this.callback = callback;
            this.cursor = new AtomicLong(initialCursor);
        }

        static ConsumerState consumerState(ConsumerConfig config, ConsumerCallback callback, long initialCursor) {
            return new ConsumerState(config, callback, initialCursor);
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

        void advanceCursor(long offset) {
            cursor.set(offset);
        }

        void resetRetryCount() {
            retryCount.set(0);
        }

        void adjustPollInterval(boolean hasData) {
            currentPollMs = hasData
                           ? MIN_POLL_MS
                           : Math.min(currentPollMs * 2, MAX_POLL_MS);
        }

        int incrementRetryCount() {
            return retryCount.incrementAndGet();
        }

        void incrementEventsSinceCheckpoint() {
            eventsSinceCheckpoint.incrementAndGet();
        }

        boolean shouldCheckpoint() {
            return eventsSinceCheckpoint.get() >= CHECKPOINT_EVENT_THRESHOLD || (System.currentTimeMillis() - lastCheckpointTime) >= CHECKPOINT_TIME_THRESHOLD_MS;
        }

        void resetCheckpointCounters() {
            eventsSinceCheckpoint.set(0);
            lastCheckpointTime = System.currentTimeMillis();
        }

        boolean isStalled() {
            return stalled.get();
        }

        void stall() {
            stalled.set(true);
        }

        boolean isCancelled() {
            return cancelled.get();
        }

        void scheduledFuture(ScheduledFuture<?> future) {
            this.future = future;
        }

        void pushListener(LongConsumer listener) {
            this.pushListenerRef = listener;
        }

        Option<LongConsumer> pushListener() {
            return option(pushListenerRef);
        }

        void touchLastPollTime() {
            lastPollTime = System.currentTimeMillis();
        }

        long lastPollTime() {
            return lastPollTime;
        }

        void cancel() {
            cancelled.set(true);
            var f = future;
            if (f != null) {f.cancel(false);}
        }
    }
}
