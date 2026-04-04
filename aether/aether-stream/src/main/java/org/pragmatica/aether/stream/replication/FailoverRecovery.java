package org.pragmatica.aether.stream.replication;

import org.pragmatica.lang.Promise;


/// Orchestrates stream partition recovery when a new governor takes over.
/// Recovery sequence:
/// 1. Read watermarks from KV-Store (via ReplicaRegistry.rebuildFromWatermarks)
/// 2. Rebuild segment index from storage refs (via SegmentIndex.rebuildFromRefs)
/// 3. Identify the most advanced replica per partition
/// 4. Request catch-up from that replica (CatchupRequest)
/// 5. Apply catch-up events to local ring buffer
public interface FailoverRecovery {
    Promise<RecoveryResult> recover(String streamName, int partitionCount);

    record RecoveryResult(int partitionsRecovered, long eventsReplayed, long recoveryMs) {
        public static RecoveryResult recoveryResult(int partitionsRecovered, long eventsReplayed, long recoveryMs) {
            return new RecoveryResult(partitionsRecovered, eventsReplayed, recoveryMs);
        }

        public static RecoveryResult recoveryResult(long recoveryMs) {
            return new RecoveryResult(0, 0L, recoveryMs);
        }
    }

    static FailoverRecovery failoverRecovery(ReplicaRegistry registry,
                                             StreamPartitionRecovery partitionRecovery,
                                             CatchupTransport transport) {
        return new DefaultFailoverRecovery(registry, partitionRecovery, transport);
    }
}
