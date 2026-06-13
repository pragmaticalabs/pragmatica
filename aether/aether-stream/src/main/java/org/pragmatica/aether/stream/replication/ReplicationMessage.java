// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.serialization.Codec;

import java.util.Arrays;
import java.util.List;


/// Wire-routable replication-protocol messages. Rides the same `FORWARD` partition transport lane as
/// {@link org.pragmatica.aether.stream.forward.StreamForwardMessage}, so the A6 receive handlers can be
/// registered on the node {@code MessageRouter} and the events serialized over the cluster network.
/// `sender()` is the node that emitted the message (the owner/governor for replicate & catch-up
/// responses, the replica for acks & catch-up requests).
@Codec
public sealed interface ReplicationMessage extends ProtocolMessage {
    @Override
    default StreamType streamType() {
        return StreamType.FORWARD;
    }

    record ReplicateEvents(NodeId governorId,
                           String streamName,
                           int partition,
                           long fromOffset,
                           List<byte[]> payloads,
                           List<Long> timestamps) implements ReplicationMessage {
        public ReplicateEvents {
            payloads = payloads.stream().map(byte[]::clone).toList();
            timestamps = List.copyOf(timestamps);
        }

        public static ReplicateEvents replicateEvents(NodeId governorId,
                                                      String streamName,
                                                      int partition,
                                                      long fromOffset,
                                                      List<byte[]> payloads,
                                                      List<Long> timestamps) {
            return new ReplicateEvents(governorId, streamName, partition, fromOffset, payloads, timestamps);
        }

        @Override
        public NodeId sender() {
            return governorId;
        }

        @Override
        public List<byte[]> payloads() {
            return payloads.stream()
                           .map(byte[]::clone)
                           .toList();
        }
    }

    record ReplicateAck(NodeId replicaId, String streamName, int partition, long confirmedOffset) implements ReplicationMessage {
        public static ReplicateAck replicateAck(NodeId replicaId,
                                                String streamName,
                                                int partition,
                                                long confirmedOffset) {
            return new ReplicateAck(replicaId, streamName, partition, confirmedOffset);
        }

        @Override
        public NodeId sender() {
            return replicaId;
        }
    }

    record BatchSync(NodeId governorId,
                     String streamName,
                     int partition,
                     long fromOffset,
                     long toOffset,
                     byte[] compressedBatch) implements ReplicationMessage {
        public BatchSync {
            compressedBatch = compressedBatch.clone();
        }

        public static BatchSync batchSync(NodeId governorId,
                                          String streamName,
                                          int partition,
                                          long fromOffset,
                                          long toOffset,
                                          byte[] compressedBatch) {
            return new BatchSync(governorId, streamName, partition, fromOffset, toOffset, compressedBatch);
        }

        @Override
        public NodeId sender() {
            return governorId;
        }

        @Override
        public byte[] compressedBatch() {
            return compressedBatch.clone();
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof BatchSync other
                   && governorId.equals(other.governorId)
                   && streamName.equals(other.streamName)
                   && partition == other.partition
                   && fromOffset == other.fromOffset
                   && toOffset == other.toOffset
                   && Arrays.equals(compressedBatch, other.compressedBatch);
        }

        @Override
        public int hashCode() {
            int result = governorId.hashCode();

            result = 31 * result + streamName.hashCode();
            result = 31 * result + Integer.hashCode(partition);
            result = 31 * result + Long.hashCode(fromOffset);
            result = 31 * result + Long.hashCode(toOffset);
            result = 31 * result + Arrays.hashCode(compressedBatch);

            return result;
        }
    }

    record CatchupRequest(NodeId replicaId, String streamName, int partition, long fromOffset) implements ReplicationMessage {
        public static CatchupRequest catchupRequest(NodeId replicaId,
                                                    String streamName,
                                                    int partition,
                                                    long fromOffset) {
            return new CatchupRequest(replicaId, streamName, partition, fromOffset);
        }

        @Override
        public NodeId sender() {
            return replicaId;
        }
    }

    record CatchupResponse(NodeId governorId,
                           String streamName,
                           int partition,
                           long fromOffset,
                           long toOffset,
                           List<byte[]> payloads,
                           List<Long> timestamps) implements ReplicationMessage {
        public CatchupResponse {
            payloads = payloads.stream().map(byte[]::clone).toList();
            timestamps = List.copyOf(timestamps);
        }

        public static CatchupResponse catchupResponse(NodeId governorId,
                                                      String streamName,
                                                      int partition,
                                                      long fromOffset,
                                                      long toOffset,
                                                      List<byte[]> payloads,
                                                      List<Long> timestamps) {
            return new CatchupResponse(governorId, streamName, partition, fromOffset, toOffset, payloads, timestamps);
        }

        @Override
        public NodeId sender() {
            return governorId;
        }

        @Override
        public List<byte[]> payloads() {
            return payloads.stream()
                           .map(byte[]::clone)
                           .toList();
        }
    }
}
