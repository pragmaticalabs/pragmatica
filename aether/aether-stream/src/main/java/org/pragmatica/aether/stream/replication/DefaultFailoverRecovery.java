package org.pragmatica.aether.stream.replication;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import static org.pragmatica.aether.stream.replication.FailoverRecovery.RecoveryResult.recoveryResult;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.CatchupRequest.catchupRequest;
import static org.pragmatica.lang.Option.option;

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

    @Override
    public Promise<RecoveryResult> recover(String streamName, int partitionCount) {
        var startMs = System.currentTimeMillis();
        var partitionsRecovered = new AtomicInteger(0);
        var eventsReplayed = new AtomicLong(0);
        var partitionIndices = IntStream.range(0, partitionCount).boxed().toList();

        return recoverPartitions(streamName, partitionIndices, 0, partitionsRecovered, eventsReplayed)
            .map(_ -> buildResult(startMs, partitionsRecovered, eventsReplayed));
    }

    private Promise<RecoveryResult> recoverPartitions(String streamName, List<Integer> partitions, int index,
                                                       AtomicInteger partitionsRecovered, AtomicLong eventsReplayed) {
        if (index >= partitions.size()) {
            return Promise.success(recoveryResult(partitionsRecovered.get(), eventsReplayed.get(), 0));
        }

        return recoverSinglePartition(streamName, partitions.get(index))
            .map(events -> accumulateStats(partitionsRecovered, eventsReplayed, events))
            .flatMap(_ -> recoverPartitions(streamName, partitions, index + 1, partitionsRecovered, eventsReplayed));
    }

    private Promise<Long> recoverSinglePartition(String streamName, int partition) {
        var replicas = registry.replicasFor(streamName, partition);

        return findBestReplica(replicas)
            .map(best -> requestCatchupFromReplica(streamName, partition, best))
            .or(Promise.success(0L));
    }

    private Promise<Long> requestCatchupFromReplica(String streamName, int partition, ReplicaDescriptor bestReplica) {
        var fromOffset = bestReplica.confirmedOffset() + 1;
        var request = catchupRequest(bestReplica.nodeId(), streamName, partition, fromOffset);

        return transport.requestCatchup(bestReplica.nodeId(), request)
                        .map(response -> applyRecoveredEvents(streamName, partition, response));
    }

    private long applyRecoveredEvents(String streamName, int partition,
                                       ReplicationMessage.CatchupResponse response) {
        var payloads = response.payloads();
        var timestamps = response.timestamps();
        var count = Math.min(payloads.size(), timestamps.size());

        for (int i = 0; i < count; i++) {
            partitionRecovery.appendRecoveredEvent(streamName, partition, payloads.get(i), timestamps.get(i));
        }

        return count;
    }

    private static Option<ReplicaDescriptor> findBestReplica(List<ReplicaDescriptor> replicas) {
        return option(replicas.stream()
                              .max(Comparator.comparingLong(ReplicaDescriptor::confirmedOffset))
                              .orElse(null));
    }

    private static long accumulateStats(AtomicInteger partitionsRecovered, AtomicLong eventsReplayed, long events) {
        if (events > 0) {
            partitionsRecovered.incrementAndGet();
        }

        eventsReplayed.addAndGet(events);
        return events;
    }

    private static RecoveryResult buildResult(long startMs, AtomicInteger partitionsRecovered,
                                               AtomicLong eventsReplayed) {
        return recoveryResult(partitionsRecovered.get(), eventsReplayed.get(), System.currentTimeMillis() - startMs);
    }
}
