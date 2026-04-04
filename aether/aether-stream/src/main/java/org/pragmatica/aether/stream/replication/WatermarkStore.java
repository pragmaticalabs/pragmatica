package org.pragmatica.aether.stream.replication;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;


/// Persistence abstraction for replica watermarks.
/// Implementations write confirmed offsets to durable storage (e.g. KV-Store).
@FunctionalInterface public interface WatermarkStore {
    @Contract void persistWatermark(String streamName, int partition, NodeId nodeId, long confirmedOffset);

    WatermarkStore NOOP = (_, _, _, _) -> {};
}
