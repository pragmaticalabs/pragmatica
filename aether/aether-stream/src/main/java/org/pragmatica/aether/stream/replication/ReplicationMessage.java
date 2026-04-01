package org.pragmatica.aether.stream.replication;

import org.pragmatica.consensus.NodeId;

import java.util.Arrays;
import java.util.List;

/// Protocol messages for stream replication between governor and replicas.
public sealed interface ReplicationMessage {
    /// Governor to Replica: replicate a batch of events.
    record ReplicateEvents(NodeId governorId,
                           String streamName,
                           int partition,
                           long fromOffset,
                           List<byte[]> payloads,
                           List<Long> timestamps) implements ReplicationMessage {
        public ReplicateEvents {
            payloads = payloads.stream().map(byte[]::clone)
                                      .toList();
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

        @Override public List<byte[]> payloads() {
            return payloads.stream().map(byte[]::clone)
                                  .toList();
        }
    }

    /// Replica to Governor: acknowledge received events.
    record ReplicateAck(NodeId replicaId,
                        String streamName,
                        int partition,
                        long confirmedOffset) implements ReplicationMessage {
        public static ReplicateAck replicateAck(NodeId replicaId,
                                                String streamName,
                                                int partition,
                                                long confirmedOffset) {
            return new ReplicateAck(replicaId, streamName, partition, confirmedOffset);
        }
    }

    /// Governor to Replica: full batch sync for initial catch-up.
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

        @Override public byte[] compressedBatch() {
            return compressedBatch.clone();
        }

        @Override public boolean equals(Object obj) {
            return obj instanceof BatchSync other &&
            governorId.equals(other.governorId) &&
            streamName.equals(other.streamName) &&
            partition == other.partition && fromOffset == other.fromOffset && toOffset == other.toOffset && Arrays.equals(compressedBatch,
                                                                                                                          other.compressedBatch);
        }

        @Override public int hashCode() {
            int result = governorId.hashCode();
            result = 31 * result + streamName.hashCode();
            result = 31 * result + Integer.hashCode(partition);
            result = 31 * result + Long.hashCode(fromOffset);
            result = 31 * result + Long.hashCode(toOffset);
            result = 31 * result + Arrays.hashCode(compressedBatch);
            return result;
        }
    }

    /// Replica to Governor: request catch-up from a specific offset.
    record CatchupRequest(NodeId replicaId,
                          String streamName,
                          int partition,
                          long fromOffset) implements ReplicationMessage {
        public static CatchupRequest catchupRequest(NodeId replicaId,
                                                    String streamName,
                                                    int partition,
                                                    long fromOffset) {
            return new CatchupRequest(replicaId, streamName, partition, fromOffset);
        }
    }

    /// Governor to Replica: catch-up response with events from requested offset.
    record CatchupResponse(NodeId governorId,
                           String streamName,
                           int partition,
                           long fromOffset,
                           long toOffset,
                           List<byte[]> payloads,
                           List<Long> timestamps) implements ReplicationMessage {
        public CatchupResponse {
            payloads = payloads.stream().map(byte[]::clone)
                                      .toList();
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

        @Override public List<byte[]> payloads() {
            return payloads.stream().map(byte[]::clone)
                                  .toList();
        }
    }
}
