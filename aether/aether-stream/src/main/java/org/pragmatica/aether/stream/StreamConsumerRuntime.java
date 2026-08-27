// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;

import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.stream.consumer.TransactionalCursorCommit;
import org.pragmatica.aether.stream.segment.ConsumerCursorStore;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


public interface StreamConsumerRuntime extends AutoCloseable {
    /// Narrowed from [`AutoCloseable#close`] so callers need no exception handling (#642) — the
    /// implementation signals nothing through exceptions, and `AetherNode.stop()` has to be able to
    /// call it from a plain statement. `void` is the JDK's contract here, not a choice, hence
    /// [`Contract`].
    ///
    /// **This is NOT an infallible operation, and the narrowed signature hides that.** Closing flushes
    /// every consumer cursor before removing push listeners, and that flush is
    /// `ConsumerCursorStore.commit(...)` — a `Promise<Unit>` consensus write that can fail. The flush
    /// site does not absorb the failure so much as never look at it: the returned `Promise` is
    /// discarded unobserved, so a failed final commit is silent — no log, no retry, no signal here.
    /// Effect: shutdown always completes, cursor durability is not guaranteed, and a consumer whose
    /// final flush failed resumes from its last committed offset and redelivers the gap. Consumers
    /// must therefore be idempotent [design intent — unverified]. Making that dropped failure
    /// deliberate rather than incidental is worth its own ticket; this doc only stops the signature
    /// from lying about it.
    ///
    /// Ordering is load-bearing (#488): call this while the partition manager is still open and the
    /// cluster node still up, or the cursor write races shutdown and the listener removal — which
    /// resolves through `partitionBuffer` — silently no-ops.
    @Contract
    @Override
    void close();

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

    /// How a consumer reads the partition it is attached to.
    ///
    /// Defaults to the LOCAL ring ([#localPartitionReader]), which is what a consumer running on the
    /// partition's owner wants. The node wires the routed reader instead, so a consumer assigned to a
    /// node that does not hold the ring reads THROUGH the owner rather than failing
    /// `PARTITION_NOT_LOCAL` forever (#535). Consumer placement is then free of the requirement that
    /// the partition owner also host the declaring slice.
    @FunctionalInterface
    interface PartitionReader {
        Promise<List<OffHeapRingBuffer.RawEvent>> read(String streamName,
                                                       int partition,
                                                       long fromOffset,
                                                       int maxEvents);
    }

    /// The default reader: this node's own ring, and nothing else.
    static PartitionReader localPartitionReader(StreamPartitionManager partitionManager) {
        return (streamName, partition, fromOffset, maxEvents) -> partitionManager.readLocal(streamName,
                                                                                            partition,
                                                                                            fromOffset,
                                                                                            maxEvents)
                                                                                 .async();
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

    /// #535 production overload: the same runtime with an explicit [PartitionReader], so the node can
    /// hand it the routed reader that forwards to the partition owner when the ring is not local.
    static StreamConsumerRuntime streamConsumerRuntime(StreamPartitionManager partitionManager,
                                                       DeadLetterHandler deadLetterHandler,
                                                       ConsumerCursorStore cursorStore,
                                                       PartitionReader reader) {
        return new ConsumerRuntimeState(partitionManager, deadLetterHandler, some(cursorStore), none(), reader);
    }
}
