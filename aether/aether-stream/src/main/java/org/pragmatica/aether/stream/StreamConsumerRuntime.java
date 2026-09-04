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
    /// Closing flushes every consumer's cursor before removing push listeners. That flush is
    /// `ConsumerCursorStore.commit(...)` — a `Promise<Unit>` consensus write that can fail, or simply
    /// not settle before shutdown needs to proceed. #654: the batch of final commits is bound-await
    /// for up to 5 seconds so a wedged or slow write cannot hold node stop; a commit that has not
    /// settled within the bound counts as failed for THIS shutdown even if it later succeeds. Every
    /// failure — settled or bound-expired — is logged at ERROR (consumer group, stream, partition,
    /// cause), counted in [#cursorCommitFailureCount], and, while the consumer stays attached, visible
    /// on [SubscriptionSnapshot#lastCursorCommitFailure]. **Redelivery contract**: a consumer whose
    /// final flush failed or did not settle resumes, on its next attach, from its LAST COMMITTED
    /// offset [mechanism: `loadCursorAndStart` unconditionally fetches and applies the last committed
    /// offset before starting delivery] and redelivers every event since — consumers must be
    /// idempotent. Operator recovery: none needed for one failure at one shutdown — that is ordinary
    /// at-least-once behavior; a sustained rise in [#cursorCommitFailureCount] across restarts, rather
    /// than an isolated one, is the signal worth investigating (consensus write path health).
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
    /// #654: node-wide count of cursor commits — final flush at detach, or periodic checkpoint —
    /// that failed or did not settle within their bound. Monotonic for the life of this runtime;
    /// survives a consumer's removal from the live subscription set, unlike
    /// [SubscriptionSnapshot#lastCursorCommitFailure], which goes with the removed entry.
    long cursorCommitFailureCount();

    /// One live subscription. `cursor` is the next offset this consumer will read, i.e. one past the
    /// last delivered offset. `lastCursorCommitFailure` (#654) is the detail of this consumer's most
    /// recent cursor commit failure, cleared on its next successful commit; [Option#none] when its
    /// last commit succeeded or none has been attempted yet.
    record SubscriptionSnapshot(String streamName,
                                int partition,
                                String consumerGroup,
                                long cursor,
                                boolean stalled,
                                IdlePolicy idlePolicy,
                                Option<String> lastCursorCommitFailure) {}

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
