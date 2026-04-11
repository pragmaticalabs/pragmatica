package org.pragmatica.aether.stream;

import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.stream.consumer.TransactionalCursorCommit;
import org.pragmatica.aether.stream.segment.CursorStore;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.List;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// Manages push-based delivery of stream events to subscriber callbacks.
///
/// For each consumer group + partition pair, the runtime:
/// 1. Tracks cursor position (last delivered offset)
/// 2. Polls the ring buffer periodically
/// 3. Delivers events to the subscriber callback
/// 4. Handles errors based on ErrorStrategy (RETRY, SKIP, STALL)
/// 5. Records failed events to a dead-letter handler
public interface StreamConsumerRuntime extends AutoCloseable {
    Result<Unit> subscribe(String streamName, int partition, ConsumerConfig config, ConsumerCallback callback);
    Result<Unit> unsubscribe(String streamName, int partition, String consumerGroup);
    Option<Long> cursorPosition(String streamName, int partition, String consumerGroup);
    Option<TransactionalCursorCommit> transactionalCursorCommit();
    DeadLetterHandler deadLetterHandler();

    @FunctionalInterface interface ConsumerCallback {
        Promise<Unit> onEvent(long offset, byte[] payload, long timestamp);
    }

    @FunctionalInterface interface BatchConsumerCallback {
        Promise<Unit> onBatch(List<OffHeapRingBuffer.RawEvent> events);
    }

    static StreamConsumerRuntime streamConsumerRuntime(StreamPartitionManager partitionManager) {
        return streamConsumerRuntime(partitionManager, DeadLetterHandler.deadLetterHandler());
    }

    static StreamConsumerRuntime streamConsumerRuntime(StreamPartitionManager partitionManager,
                                                       DeadLetterHandler deadLetterHandler) {
        return new ConsumerRuntimeState(partitionManager, deadLetterHandler);
    }

    static StreamConsumerRuntime streamConsumerRuntime(StreamPartitionManager partitionManager,
                                                       DeadLetterHandler deadLetterHandler,
                                                       CursorStore cursorStore) {
        return new ConsumerRuntimeState(partitionManager, deadLetterHandler, some(cursorStore));
    }

    static StreamConsumerRuntime streamConsumerRuntime(StreamPartitionManager partitionManager,
                                                       DeadLetterHandler deadLetterHandler,
                                                       CursorStore cursorStore,
                                                       TransactionalCursorCommit transactionalCommit) {
        return new ConsumerRuntimeState(partitionManager,
                                        deadLetterHandler,
                                        some(cursorStore),
                                        some(transactionalCommit));
    }
}
