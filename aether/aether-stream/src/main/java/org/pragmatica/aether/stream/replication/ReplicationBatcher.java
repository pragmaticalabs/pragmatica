package org.pragmatica.aether.stream.replication;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.ReentrantLock;

import static org.pragmatica.aether.stream.replication.PartitionKey.partitionKey;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateEvents.replicateEvents;


/// Batches replication events to amortize per-message QUIC overhead.
/// Flushes when batch reaches maxEvents OR after maxDelay, whichever comes first.
/// Each partition has its own independent accumulator to preserve ordering guarantees.
public final class ReplicationBatcher implements AutoCloseable {
    static final int DEFAULT_MAX_EVENTS = 100;

    static final TimeSpan DEFAULT_MAX_DELAY = TimeSpan.timeSpan(1).millis();

    private final ConcurrentHashMap<PartitionKey, BatchAccumulator> accumulators = new ConcurrentHashMap<>();

    private final ReplicationTransport transport;
    private final ReplicaRegistry registry;
    private final NodeId governorId;
    private final int maxEvents;
    private final ScheduledFuture<?> flushTask;

    private ReplicationBatcher(ReplicationTransport transport,
                               ReplicaRegistry registry,
                               NodeId governorId,
                               int maxEvents,
                               TimeSpan maxDelay) {
        this.transport = transport;
        this.registry = registry;
        this.governorId = governorId;
        this.maxEvents = maxEvents;
        this.flushTask = SharedScheduler.scheduleAtFixedRate(this::flushAll, maxDelay);
    }

    public static ReplicationBatcher replicationBatcher(ReplicationTransport transport,
                                                        ReplicaRegistry registry,
                                                        NodeId governorId) {
        return new ReplicationBatcher(transport, registry, governorId, DEFAULT_MAX_EVENTS, DEFAULT_MAX_DELAY);
    }

    public static ReplicationBatcher replicationBatcher(ReplicationTransport transport,
                                                        ReplicaRegistry registry,
                                                        NodeId governorId,
                                                        int maxEvents,
                                                        TimeSpan maxDelay) {
        return new ReplicationBatcher(transport, registry, governorId, maxEvents, maxDelay);
    }

    @Contract public void add(String streamName, int partition, long offset, byte[] payload, long timestamp) {
        var key = partitionKey(streamName, partition);
        var accumulator = accumulators.computeIfAbsent(key, _ -> new BatchAccumulator());
        var shouldFlush = accumulator.add(offset, payload, timestamp, maxEvents);
        if (shouldFlush) {flushPartition(key, accumulator);}
    }

    @Contract@Override public void close() {
        flushTask.cancel(false);
        flushAll();
    }

    @Contract void flushAll() {
        accumulators.forEach(this::flushPartition);
    }

    private void flushPartition(PartitionKey key, BatchAccumulator accumulator) {
        var snapshot = accumulator.drain();
        if (snapshot.isEmpty()) {return;}
        sendBatch(key, snapshot);
    }

    private void sendBatch(PartitionKey key, BatchSnapshot snapshot) {
        var replicas = registry.replicasFor(key.streamName(), key.partition());
        if (replicas.isEmpty()) {return;}
        var message = replicateEvents(governorId,
                                      key.streamName(),
                                      key.partition(),
                                      snapshot.fromOffset(),
                                      snapshot.payloads(),
                                      snapshot.timestamps());
        replicas.forEach(replica -> transport.send(replica.nodeId(), message));
    }

    static final class BatchAccumulator {
        private final ReentrantLock lock = new ReentrantLock();

        private long fromOffset = - 1;

        private List<byte[]> payloads = new ArrayList<>();

        private List<Long> timestamps = new ArrayList<>();

        @SuppressWarnings("JBCT-EX-01") boolean add(long offset, byte[] payload, long timestamp, int maxEvents) {
            lock.lock();
            try {
                if (payloads.isEmpty()) {fromOffset = offset;}
                payloads.add(payload.clone());
                timestamps.add(timestamp);
                return payloads.size() >= maxEvents;
            } finally {
                lock.unlock();
            }
        }

        @SuppressWarnings("JBCT-EX-01") BatchSnapshot drain() {
            lock.lock();
            try {
                if (payloads.isEmpty()) {return BatchSnapshot.EMPTY;}
                var snapshot = new BatchSnapshot(fromOffset, payloads, timestamps);
                payloads = new ArrayList<>();
                timestamps = new ArrayList<>();
                fromOffset = - 1;
                return snapshot;
            } finally {
                lock.unlock();
            }
        }
    }

    record BatchSnapshot(long fromOffset, List<byte[]> payloads, List<Long> timestamps) {
        static final BatchSnapshot EMPTY = new BatchSnapshot(- 1, List.of(), List.of());

        boolean isEmpty() {
            return payloads.isEmpty();
        }
    }
}
