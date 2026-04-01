package org.pragmatica.aether.stream.replication;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

import static org.pragmatica.aether.stream.replication.FailoverRecovery.RecoveryResult.recoveryResult;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.CatchupRequest.catchupRequest;

/// Default implementation of governor failover recovery.
/// Iterates partitions sequentially, requesting catch-up from the most advanced replica.
final class DefaultFailoverRecovery implements FailoverRecovery {
    private final ReplicaRegistry registry;
    private final StreamPartitionRecovery partitionRecovery;
    private final CatchupTransport transport;

    DefaultFailoverRecovery(ReplicaRegistry registry,
                            StreamPartitionRecovery partitionRecovery,
                            CatchupTransport transport) {
        this.registry = registry;
        this.partitionRecovery = partitionRecovery;
        this.transport = transport;
    }

    @Override public Promise<RecoveryResult> recover(String streamName, int partitionCount) {
        var startMs = System.currentTimeMillis();
        var partitionIndices = IntStream.range(0, partitionCount).boxed()
                                              .toList();
        return recoverPartitions(streamName, partitionIndices, 0, RecoveryProgress.EMPTY)
        .map(progress -> progress.toResult(startMs));
    }

    private Promise<RecoveryProgress> recoverPartitions(String streamName,
                                                        List<Integer> partitions,
                                                        int index,
                                                        RecoveryProgress progress) {
        if ( index >= partitions.size()) {
        return Promise.success(progress);}
        return recoverSinglePartition(streamName,
                                      partitions.get(index)).map(progress::withEvents)
                                     .flatMap(updated -> recoverPartitions(streamName, partitions, index + 1, updated));
    }

    private Promise<Long> recoverSinglePartition(String streamName, int partition) {
        var replicas = registry.replicasFor(streamName, partition);
        return findBestReplica(replicas).map(best -> requestCatchupFromReplica(streamName, partition, best))
                              .or(Promise.success(0L));
    }

    private Promise<Long> requestCatchupFromReplica(String streamName, int partition, ReplicaDescriptor bestReplica) {
        var fromOffset = bestReplica.confirmedOffset() + 1;
        var request = catchupRequest(bestReplica.nodeId(), streamName, partition, fromOffset);
        return transport.requestCatchup(bestReplica.nodeId(), request)
        .map(response -> applyRecoveredEvents(streamName, partition, response));
    }

    private long applyRecoveredEvents(String streamName,
                                      int partition,
                                      ReplicationMessage.CatchupResponse response) {
        var payloads = response.payloads();
        var timestamps = response.timestamps();
        var count = Math.min(payloads.size(), timestamps.size());
        IntStream.range(0, count)
        .forEach(i -> partitionRecovery.appendRecoveredEvent(streamName, partition, payloads.get(i), timestamps.get(i)));
        return count;
    }

    private static Option<ReplicaDescriptor> findBestReplica(List<ReplicaDescriptor> replicas) {
        return Option.from(replicas.stream().max(Comparator.comparingLong(ReplicaDescriptor::confirmedOffset)));
    }

    /// Immutable accumulator for recovery statistics, threaded through the flatMap chain.
    record RecoveryProgress(int partitionsRecovered, long eventsReplayed) {
        static final RecoveryProgress EMPTY = new RecoveryProgress(0, 0L);

        RecoveryProgress withEvents(long events) {
            return events > 0
                   ? new RecoveryProgress(partitionsRecovered + 1, eventsReplayed + events)
                   : this;
        }

        RecoveryResult toResult(long startMs) {
            return recoveryResult(partitionsRecovered, eventsReplayed, System.currentTimeMillis() - startMs);
        }
    }
}
