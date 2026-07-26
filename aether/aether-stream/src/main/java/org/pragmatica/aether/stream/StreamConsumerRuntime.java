// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;

import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.stream.consumer.TransactionalCursorCommit;
import org.pragmatica.aether.stream.segment.ConsumerCursorStore;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


public interface StreamConsumerRuntime extends AutoCloseable {
    /// Client-driven subscription: reaped once idle past the consumer timeout.
    /// Equivalent to [#subscribe] with [IdlePolicy#REAP_WHEN_IDLE].
    Result<Unit> subscribe(String streamName, int partition, ConsumerConfig config, ConsumerCallback callback);

    Result<Unit> subscribe(String streamName,
                           int partition,
                           ConsumerConfig config,
                           ConsumerCallback callback,
                           IdlePolicy idlePolicy);

    Result<Unit> unsubscribe(String streamName, int partition, String consumerGroup);
    Option<Long> cursorPosition(String streamName, int partition, String consumerGroup);
    Option<TransactionalCursorCommit> transactionalCursorCommit();
    DeadLetterHandler deadLetterHandler();
    /// Snapshot of everything currently subscribed. Pure read, assembled on request — the operator
    /// surface for "is this consumer actually attached, and where is it?" (#488).
    List<SubscriptionSnapshot> subscriptions();

    /// One live subscription. `cursor` is the next offset this consumer will read, i.e. one past the
    /// last delivered offset.
    record SubscriptionSnapshot(String streamName,
                                int partition,
                                String consumerGroup,
                                long cursor,
                                boolean stalled,
                                IdlePolicy idlePolicy) {}

    /// Whether the idle reaper may unsubscribe a consumer that has not polled recently.
    ///
    /// The reaper measures time since the last `pollPartition`, which for a PUSH-listener consumer
    /// only advances when events arrive. Idle time therefore says nothing about liveness — it says
    /// the partition is quiet. A deployment-declared consumer on a quiet partition must survive that.
    enum IdlePolicy {
        /// Reap after the idle timeout. A client-driven consumer that stopped polling has gone away.
        REAP_WHEN_IDLE,
        /// Never reap; only an explicit unsubscribe (or runtime close) detaches this consumer. Used
        /// for consumers declared by a deployment, whose lifetime is owned by the deployment.
        KEEP_UNTIL_UNSUBSCRIBED
    }

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
                                                       ConsumerCursorStore cursorStore) {
        return new ConsumerRuntimeState(partitionManager, deadLetterHandler, some(cursorStore));
    }

    static StreamConsumerRuntime streamConsumerRuntime(StreamPartitionManager partitionManager,
                                                       DeadLetterHandler deadLetterHandler,
                                                       ConsumerCursorStore cursorStore,
                                                       TransactionalCursorCommit transactionalCommit) {
        return new ConsumerRuntimeState(partitionManager,
                                        deadLetterHandler,
                                        some(cursorStore),
                                        some(transactionalCommit));
    }
}
