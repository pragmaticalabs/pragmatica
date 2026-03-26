package org.pragmatica.aether.stream;

import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.lang.Contract;
import org.pragmatica.aether.slice.ConsumerConfig.ErrorStrategy;
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

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Unit.unit;

/// Package-private implementation of StreamConsumerRuntime.
/// Manages polling lifecycle and error handling for all subscribed consumers.
final class ConsumerRuntimeState implements StreamConsumerRuntime {
    private static final System.Logger LOG = System.getLogger(ConsumerRuntimeState.class.getName());
    private static final TimeSpan POLL_INTERVAL = TimeSpan.timeSpan(50)
                                                         .millis();
    private static final int MAX_POLL_BATCH = 100;
    private static final int DEFAULT_MAX_RETRIES = 5;
    private static final long BASE_BACKOFF_MS = 100;
    private static final long MAX_BACKOFF_MS = 10_000;

    private final StreamPartitionManager partitionManager;
    private final DeadLetterHandler dlHandler;
    private final ConcurrentHashMap<ConsumerKey, ConsumerState> consumers = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    ConsumerRuntimeState(StreamPartitionManager partitionManager, DeadLetterHandler dlHandler) {
        this.partitionManager = partitionManager;
        this.dlHandler = dlHandler;
    }

    @Override
    public Result<Unit> subscribe(String streamName,
                                  int partition,
                                  ConsumerConfig config,
                                  ConsumerCallback callback) {
        if (closed.get()) {
            return StreamError.General.CONSUMER_RUNTIME_CLOSED.result();
        }
        var key = ConsumerKey.consumerKey(streamName, partition, config.groupId());
        var state = ConsumerState.consumerState(config, callback);
        if (consumers.putIfAbsent(key, state) != null) {
            return StreamError.General.CONSUMER_ALREADY_SUBSCRIBED.result();
        }
        var future = SharedScheduler.scheduleAtFixedRate(() -> pollPartition(key, state), POLL_INTERVAL);
        state.scheduledFuture(future);
        return success(unit());
    }

    @Override
    public Result<Unit> unsubscribe(String streamName, int partition, String consumerGroup) {
        var key = ConsumerKey.consumerKey(streamName, partition, consumerGroup);
        var state = consumers.remove(key);
        if (state == null) {
            return StreamError.General.CONSUMER_NOT_FOUND.result();
        }
        state.cancel();
        return success(unit());
    }

    @Override
    public Option<Long> cursorPosition(String streamName, int partition, String consumerGroup) {
        var key = ConsumerKey.consumerKey(streamName, partition, consumerGroup);
        return option(consumers.get(key)).map(ConsumerState::cursor);
    }

    @Override
    public DeadLetterHandler deadLetterHandler() {
        return dlHandler;
    }

    @Contract
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            consumers.values()
                     .forEach(ConsumerState::cancel);
            consumers.clear();
        }
    }

    // --- Polling ---
    private void pollPartition(ConsumerKey key, ConsumerState state) {
        if (closed.get() || state.isCancelled() || state.isStalled()) {
            return;
        }
        var fromOffset = state.cursor();
        partitionManager.readLocal(key.streamName(),
                                   key.partition(),
                                   fromOffset,
                                   MAX_POLL_BATCH)
                        .onSuccess(events -> deliverEvents(key, state, events))
                        .onFailure(cause -> logPollFailure(key, cause));
    }

    private void deliverEvents(ConsumerKey key,
                               ConsumerState state,
                               List<OffHeapRingBuffer.RawEvent> events) {
        for (var event : events) {
            if (state.isCancelled() || state.isStalled()) {
                return;
            }
            deliverSingleEvent(key, state, event);
        }
    }

    private void deliverSingleEvent(ConsumerKey key,
                                    ConsumerState state,
                                    OffHeapRingBuffer.RawEvent event) {
        state.callback()
             .onEvent(event.offset(),
                      event.data(),
                      event.timestamp())
             .onSuccess(_ -> advanceCursor(state,
                                           event.offset()))
             .onFailure(cause -> handleDeliveryFailure(key,
                                                       state,
                                                       event,
                                                       cause.message()));
    }

    private void advanceCursor(ConsumerState state, long offset) {
        state.advanceCursor(offset + 1);
        state.resetRetryCount();
    }

    // --- Error handling ---
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
            advanceCursor(state, event.offset());
            return;
        }
        var backoffMs = computeBackoff(attempt);
        var delay = TimeSpan.timeSpan(backoffMs)
                            .millis();
        SharedScheduler.schedule(() -> retryDeliverEvent(key, state, event), delay);
    }

    private void retryDeliverEvent(ConsumerKey key,
                                   ConsumerState state,
                                   OffHeapRingBuffer.RawEvent event) {
        if (state.isCancelled() || state.isStalled()) {
            return;
        }
        state.callback()
             .onEvent(event.offset(),
                      event.data(),
                      event.timestamp())
             .onSuccess(_ -> advanceCursor(state,
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
            advanceCursor(state, event.offset());
            return;
        }
        var backoffMs = computeBackoff(attempt);
        SharedScheduler.schedule(() -> retryDeliverEvent(key, state, event),
                                 TimeSpan.timeSpan(backoffMs)
                                         .millis());
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
        advanceCursor(state, event.offset());
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
        return Math.min(BASE_BACKOFF_MS * (1L<< (attempt - 1)), MAX_BACKOFF_MS);
    }

    private static void logPollFailure(ConsumerKey key, org.pragmatica.lang.Cause cause) {
        LOG.log(System.Logger.Level.DEBUG,
                "Poll failed for {0}[{1}]: {2}",
                key.streamName(),
                key.partition(),
                cause.message());
    }

    // --- Internal types ---
    record ConsumerKey(String streamName, int partition, String groupId) {
        static ConsumerKey consumerKey(String streamName, int partition, String groupId) {
            return new ConsumerKey(streamName, partition, groupId);
        }
    }

    static final class ConsumerState {
        private final ConsumerConfig config;
        private final ConsumerCallback callback;
        private final AtomicLong cursor;
        private final AtomicInteger retryCount = new AtomicInteger(0);
        private final AtomicBoolean stalled = new AtomicBoolean(false);
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private volatile ScheduledFuture<?> future;

        private ConsumerState(ConsumerConfig config, ConsumerCallback callback) {
            this.config = config;
            this.callback = callback;
            this.cursor = new AtomicLong(0);
        }

        static ConsumerState consumerState(ConsumerConfig config, ConsumerCallback callback) {
            return new ConsumerState(config, callback);
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

        int incrementRetryCount() {
            return retryCount.incrementAndGet();
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

        void cancel() {
            cancelled.set(true);
            var f = future;
            if (f != null) {
                f.cancel(false);
            }
        }
    }
}
