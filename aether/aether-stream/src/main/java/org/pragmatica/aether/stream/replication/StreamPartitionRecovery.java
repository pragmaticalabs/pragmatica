package org.pragmatica.aether.stream.replication;

import org.pragmatica.lang.Result;


/// Abstraction for applying recovered events to a stream partition's ring buffer.
/// Decouples failover recovery from the concrete StreamPartitionManager.
@FunctionalInterface public interface StreamPartitionRecovery {
    Result<Long> appendRecoveredEvent(String streamName, int partition, byte[] payload, long timestamp);

    StreamPartitionRecovery NOOP = (_, _, _, _) -> Result.success(0L);
}
