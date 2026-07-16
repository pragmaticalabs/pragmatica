// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;

import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.stream.consumer.TransactionalCursorCommit;
import org.pragmatica.aether.stream.segment.CursorStore;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


public interface StreamConsumerRuntime extends AutoCloseable {
    Result<Unit> subscribe(String streamName, int partition, ConsumerConfig config, ConsumerCallback callback);
    Result<Unit> unsubscribe(String streamName, int partition, String consumerGroup);
    Option<Long> cursorPosition(String streamName, int partition, String consumerGroup);
    Option<TransactionalCursorCommit> transactionalCursorCommit();
    DeadLetterHandler deadLetterHandler();

    @FunctionalInterface
    interface ConsumerCallback {
        Promise<Unit> onEvent(long offset, byte[] payload, long timestamp);
    }

    @FunctionalInterface
    interface BatchConsumerCallback {
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
