package org.pragmatica.aether.stream.replication;

import org.pragmatica.consensus.NodeId;

/// Persistence abstraction for replica watermarks.
/// Implementations write confirmed offsets to durable storage (e.g. KV-Store).
@FunctionalInterface
public interface WatermarkStore {

    /// Persist the confirmed offset for a replica of a stream partition.
    void persistWatermark(String streamName, int partition, NodeId nodeId, long confirmedOffset);

    /// No-op store for testing or single-node deployments.
    WatermarkStore NOOP = (_, _, _, _) -> {};
}
