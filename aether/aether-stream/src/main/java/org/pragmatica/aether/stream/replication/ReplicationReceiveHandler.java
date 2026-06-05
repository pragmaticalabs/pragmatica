// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Result;
import org.pragmatica.messaging.MessageReceiver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateAck.replicateAck;

/// Replica-side receive/apply for the A6 replication path.
///
/// An owner of `(stream, partition)` replicates each published event to its registered replica set
/// via {@link DefaultReplicationManager#replicateEvent} → {@link ReplicationTransport#send} carrying a
/// {@link ReplicationMessage.ReplicateEvents}. THIS handler runs on each replica: it lands the
/// replicated payloads into the local partition ring and acks the highest applied offset back to the
/// owner.
///
/// ## Offset-preserving, non-replicating apply
/// Events are applied through {@link RecoveredAppender#appendRecovered} (the A4 seam, backed by
/// `StreamPartitionManager::appendRecovered`) which appends WITHOUT re-invoking the replication
/// manager. This is what stops an infinite replicate→apply→replicate loop: a replicated event lands
/// locally but is never re-emitted onto the replication stream. Offsets are preserved because the
/// owner replays its events in arrival order into the replica's empty (or backfilled-then-empty)
/// partition, and the ring assigns sequential offsets.
///
/// ## Ack
/// After applying a batch starting at `fromOffset` with `n` payloads, the highest applied offset is
/// `fromOffset + n - 1`; that is acked back so {@link DefaultReplicationManager#handleAck} can advance
/// the watermark and resolve any pending `awaitReplication(minAcks)` promise. A partial apply acks
/// only what landed.
public final class ReplicationReceiveHandler {
    private static final Logger log = LoggerFactory.getLogger(ReplicationReceiveHandler.class);

    /// Non-replicating, offset-preserving append seam. In production this is
    /// `StreamPartitionManager::appendRecovered`.
    @FunctionalInterface public interface RecoveredAppender {
        Result<Long> appendRecovered(String streamName, int partition, byte[] payload, long timestamp);
    }

    private final NodeId self;
    private final RecoveredAppender appender;
    private final ReplicationTransport transport;

    private ReplicationReceiveHandler(NodeId self, RecoveredAppender appender, ReplicationTransport transport) {
        this.self = self;
        this.appender = appender;
        this.transport = transport;
    }

    public static ReplicationReceiveHandler replicationReceiveHandler(NodeId self,
                                                                      RecoveredAppender appender,
                                                                      ReplicationTransport transport) {
        return new ReplicationReceiveHandler(self, appender, transport);
    }

    @Contract @MessageReceiver @SuppressWarnings("JBCT-RET-01") public void onReplicateEvents(ReplicationMessage.ReplicateEvents message) {
        var payloads = message.payloads();
        var timestamps = message.timestamps();
        var fromOffset = message.fromOffset();
        var applied = applyBatch(message.streamName(), message.partition(), fromOffset, payloads, timestamps);
        if (applied <= 0) {
            log.warn("ReplicationReceiveHandler: applied no events for {}[{}] from {} (batch size {})",
                     message.streamName(),
                     message.partition(),
                     message.governorId(),
                     payloads.size());
            return;
        }
        var highestApplied = fromOffset + applied - 1;
        transport.send(message.governorId(),
                       replicateAck(self, message.streamName(), message.partition(), highestApplied));
    }

    private int applyBatch(String streamName,
                           int partition,
                           long fromOffset,
                           List<byte[]> payloads,
                           List<Long> timestamps) {
        var applied = 0;
        for (var i = 0; i < payloads.size(); i++) {
            var result = appender.appendRecovered(streamName, partition, payloads.get(i), timestamps.get(i));
            if (result.isFailure()) {
                result.onFailure(cause -> log.warn("ReplicationReceiveHandler: append failed for {}[{}] at index {}: {}",
                                                   streamName,
                                                   partition,
                                                   fromOffset,
                                                   cause.message()));
                break;
            }
            applied++;
        }
        return applied;
    }
}
