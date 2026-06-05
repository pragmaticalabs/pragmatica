// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageReceiver;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Unit.unit;


public final class StreamPartitionManager implements AutoCloseable {
    private static final long DEFAULT_MAX_TOTAL_BYTES = 128 * 1024 * 1024L;
    private static final Logger log = LoggerFactory.getLogger(StreamPartitionManager.class);

    private final ConcurrentHashMap<String, StreamEntry> streams = new ConcurrentHashMap<>();

    private final AtomicLong totalAllocatedBytes = new AtomicLong(0);

    private final long maxTotalBytes;
    private final EvictionListener evictionListener;
    private final ReplicationManager replicationManager;
    private final Option<ClusterNode<KVCommand<AetherKey>>> clusterNode;

    private StreamPartitionManager(long maxTotalBytes,
                                   EvictionListener evictionListener,
                                   ReplicationManager replicationManager,
                                   Option<ClusterNode<KVCommand<AetherKey>>> clusterNode) {
        this.maxTotalBytes = maxTotalBytes;
        this.evictionListener = evictionListener;
        this.replicationManager = replicationManager;
        this.clusterNode = clusterNode;
    }

    public static StreamPartitionManager streamPartitionManager() {
        return new StreamPartitionManager(DEFAULT_MAX_TOTAL_BYTES,
                                          EvictionListener.NOOP,
                                          ReplicationManager.NONE,
                                          Option.none());
    }

    public static StreamPartitionManager streamPartitionManager(long maxTotalBytes) {
        return new StreamPartitionManager(maxTotalBytes, EvictionListener.NOOP, ReplicationManager.NONE, Option.none());
    }

    public static StreamPartitionManager streamPartitionManager(long maxTotalBytes, EvictionListener evictionListener) {
        return new StreamPartitionManager(maxTotalBytes, evictionListener, ReplicationManager.NONE, Option.none());
    }

    public static StreamPartitionManager streamPartitionManager(long maxTotalBytes,
                                                                EvictionListener evictionListener,
                                                                ReplicationManager replicationManager) {
        return new StreamPartitionManager(maxTotalBytes, evictionListener, replicationManager, Option.none());
    }

    public static StreamPartitionManager streamPartitionManager(long maxTotalBytes,
                                                                EvictionListener evictionListener,
                                                                ReplicationManager replicationManager,
                                                                ClusterNode<KVCommand<AetherKey>> clusterNode) {
        return new StreamPartitionManager(maxTotalBytes,
                                          evictionListener,
                                          replicationManager,
                                          Option.some(clusterNode));
    }

    public static StreamPartitionManager streamPartitionManager(long maxTotalBytes,
                                                                ClusterNode<KVCommand<AetherKey>> clusterNode) {
        return new StreamPartitionManager(maxTotalBytes,
                                          EvictionListener.NOOP,
                                          ReplicationManager.NONE,
                                          Option.some(clusterNode));
    }

    public long totalAllocatedBytes() {
        return totalAllocatedBytes.get();
    }

    public long maxTotalBytes() {
        return maxTotalBytes;
    }

    @SuppressWarnings("JBCT-NULL-01") public Result<Unit> createStream(StreamConfig config) {
        if (streams.containsKey(config.name())) {return StreamError.General.STREAM_ALREADY_EXISTS.result();}
        if (config.consistencyMode() == ConsistencyMode.STRONG && evictionListener == EvictionListener.NOOP) {return StreamError.General.AHSE_REQUIRED_FOR_STRONG.result();}
        var requiredBytes = calculateStreamBytes(config);
        if (totalAllocatedBytes.get() + requiredBytes > maxTotalBytes) {return StreamError.General.STREAM_MEMORY_EXCEEDED.result();}
        var entry = StreamEntry.fromConfig(config, evictionListener);
        var previous = streams.putIfAbsent(config.name(), entry);
        if (previous != null) {
            entry.close();
            return StreamError.General.STREAM_ALREADY_EXISTS.result();
        }
        totalAllocatedBytes.addAndGet(requiredBytes);
        publishStreamConfig(config);
        return success(unit());
    }

    public Result<Unit> destroyStream(String streamName) {
        return option(streams.remove(streamName)).toResult(new StreamError.StreamNotFound(streamName))
                     .flatMap(this::closeAndRelease)
                     .onSuccess(_ -> publishStreamConfigRemoval(streamName));
    }

    @Contract private void publishStreamConfig(StreamConfig config) {
        clusterNode.onPresent(node -> applyPutCommand(node, config));
    }

    @Contract private void publishStreamConfigRemoval(String streamName) {
        clusterNode.onPresent(node -> applyRemoveCommand(node, streamName));
    }

    private void applyPutCommand(ClusterNode<KVCommand<AetherKey>> node, StreamConfig config) {
        var key = StreamConfigKey.streamConfigKey(config.name());
        var value = StreamConfigValue.streamConfigValue(config);
        var put = new KVCommand.Put<AetherKey, AetherValue>(key, value);
        node.apply(List.of(put))
                  .onFailure(cause -> log.warn("Failed to publish stream config for {}: {}",
                                               config.name(),
                                               cause.message()));
    }

    private void applyRemoveCommand(ClusterNode<KVCommand<AetherKey>> node, String streamName) {
        var key = StreamConfigKey.streamConfigKey(streamName);
        var remove = new KVCommand.Remove<AetherKey>(key);
        node.apply(List.of(remove))
                  .onFailure(cause -> log.warn("Failed to publish stream config removal for {}: {}",
                                               streamName,
                                               cause.message()));
    }

    @Contract @MessageReceiver public void onStreamConfigPut(ValuePut<StreamConfigKey, StreamConfigValue> put) {
        var streamName = put.cause().key()
                                       .streamName();
        var config = put.cause().value()
                                    .config();
        streams.computeIfAbsent(streamName, _ -> hydrateEntry(config));
    }

    @Contract @MessageReceiver public void onStreamConfigRemove(ValueRemove<StreamConfigKey, StreamConfigValue> remove) {
        var streamName = remove.cause().key()
                                             .streamName();
        removeAndReleaseIfPresent(streamName);
    }

    @SuppressWarnings("JBCT-RET-03") private void removeAndReleaseIfPresent(String streamName) {
        var existing = streams.remove(streamName);
        if (existing != null) {closeAndRelease(existing);}
    }

    private StreamEntry hydrateEntry(StreamConfig config) {
        var requiredBytes = calculateStreamBytes(config);
        var entry = StreamEntry.fromConfig(config, evictionListener);
        totalAllocatedBytes.addAndGet(requiredBytes);
        return entry;
    }

    public Result<Long> publishLocal(String streamName, int partition, byte[] payload, long timestamp) {
        return resolveStreamEntry(streamName).flatMap(entry -> appendToPartition(entry,
                                                                                 streamName,
                                                                                 partition,
                                                                                 payload,
                                                                                 timestamp))
                                 .onSuccess(offset -> replicationManager.replicateEvent(streamName,
                                                                                        partition,
                                                                                        offset,
                                                                                        payload,
                                                                                        timestamp));
    }

    public Promise<Unit> awaitReplication(String streamName, int partition, long offset, int minAcks) {
        return replicationManager.awaitReplication(streamName, partition, offset, minAcks);
    }

    /// Append a backfilled event into the local partition ring WITHOUT re-triggering replication.
    /// Used by the A4 catch-up path: a freshly-assigned replica receiving events from an up-to-date
    /// source must land them locally but must NOT re-emit them onto the replication stream (it is the
    /// receiver, not an owner). Offsets are preserved because the ring assigns sequential offsets and
    /// catch-up replays the source's events in order into an empty partition.
    public Result<Long> appendRecovered(String streamName, int partition, byte[] payload, long timestamp) {
        return resolveStreamEntry(streamName).flatMap(entry -> appendToPartition(entry,
                                                                                 streamName,
                                                                                 partition,
                                                                                 payload,
                                                                                 timestamp));
    }

    private Result<Long> appendToPartition(StreamEntry entry,
                                           String streamName,
                                           int partition,
                                           byte[] payload,
                                           long timestamp) {
        return checkEventSize(entry, payload).flatMap(_ -> resolvePartitionBuffer(streamName, partition))
                             .flatMap(buffer -> buffer.append(payload, timestamp))
                             .onSuccess(_ -> entry.updateActivity());
    }

    public Option<OffHeapRingBuffer> partitionBuffer(String streamName, int partition) {
        return resolvePartitionBuffer(streamName, partition).option();
    }

    public Result<List<OffHeapRingBuffer.RawEvent>> readLocal(String streamName,
                                                              int partition,
                                                              long fromOffset,
                                                              int maxEvents) {
        return resolvePartitionBuffer(streamName, partition).flatMap(buffer -> buffer.read(fromOffset, maxEvents));
    }

    public Option<StreamInfo> streamInfo(String streamName) {
        return option(streams.get(streamName)).map(entry -> buildStreamInfo(streamName, entry));
    }

    public List<StreamInfo> listStreams() {
        return streams.entrySet().stream()
                               .map(e -> buildStreamInfo(e.getKey(),
                                                         e.getValue()))
                               .toList();
    }

    /// Adapt this manager to the narrow {@link org.pragmatica.aether.stream.replication.StreamCatalog}
    /// consumed by `ReplicaSetController`. Exposes `(name, partitions, minSyncReplicas)` per stream —
    /// the per-stream `minSyncReplicas` is not carried by {@link StreamInfo}, so the controller cannot
    /// be fed by `listStreams()` alone; this accessor reads it straight from each stream's config.
    public org.pragmatica.aether.stream.replication.StreamCatalog replicaCatalog() {
        return new org.pragmatica.aether.stream.replication.StreamCatalog() {
            @Override public List<org.pragmatica.aether.stream.replication.StreamCatalog.StreamSpec> streams() {
                return StreamPartitionManager.this.streams.values().stream()
                                                          .map(entry -> entry.config())
                                                          .map(config -> new StreamSpec(config.name(),
                                                                                        config.partitions(),
                                                                                        config.minSyncReplicas()))
                                                          .toList();
            }

            @Override public boolean partitionHasData(String streamName, int partition) {
                return resolvePartitionBuffer(streamName, partition).map(buffer -> buffer.eventCount() > 0).or(false);
            }
        };
    }

    public int reapIdleStreams() {
        var now = System.currentTimeMillis();
        var reaped = new AtomicInteger(0);
        streams.forEach((name, entry) -> reapIfIdle(name, entry, now, reaped));
        return reaped.get();
    }

    private void reapIfIdle(String name, StreamEntry entry, long now, AtomicInteger reaped) {
        var maxAge = entry.config().retention()
                                 .maxAgeMs();
        var isEmpty = java.util.Arrays.stream(entry.partitions()).allMatch(b -> b.eventCount() == 0);
        var isExpired = (now - entry.createdAt()) > maxAge;
        var isIdle = (now - entry.lastActivity()) > maxAge;
        if (isEmpty && isExpired && isIdle) {
            var capturedActivity = entry.lastActivity();
            streams.computeIfPresent(name, (_, current) -> removeIfStillIdle(current, capturedActivity, reaped));
        }
    }

    @SuppressWarnings("JBCT-RET-03") private StreamEntry removeIfStillIdle(StreamEntry current,
                                                                           long capturedActivity,
                                                                           AtomicInteger reaped) {
        if (current.lastActivity() == capturedActivity) {
            closeAndRelease(current);
            reaped.incrementAndGet();
            return null;
        }
        return current;
    }

    @Contract@Override public void close() {
        streams.values().forEach(StreamEntry::close);
        streams.clear();
        totalAllocatedBytes.set(0);
    }

    private Result<StreamEntry> resolveStreamEntry(String streamName) {
        return option(streams.get(streamName)).toResult(new StreamError.StreamNotFound(streamName));
    }

    private static Result<Unit> checkEventSize(StreamEntry entry, byte[] payload) {
        if (payload.length > entry.config().maxEventSizeBytes()) {return new StreamError.EventTooLarge(payload.length,
                                                                                                       entry.config()
                                                                                                                   .maxEventSizeBytes()).result();}
        return success(unit());
    }

    private Result<OffHeapRingBuffer> resolvePartitionBuffer(String streamName, int partition) {
        return option(streams.get(streamName)).toResult(new StreamError.StreamNotFound(streamName))
                     .flatMap(entry -> resolvePartitionInEntry(streamName, partition, entry));
    }

    private static Result<OffHeapRingBuffer> resolvePartitionInEntry(String streamName,
                                                                     int partition,
                                                                     StreamEntry entry) {
        if (partition <0 || partition >= entry.partitions().length) {return new StreamError.PartitionOutOfRange(streamName,
                                                                                                                partition,
                                                                                                                entry.partitions().length).result();}
        return success(entry.partitions() [partition]);
    }

    private static StreamInfo buildStreamInfo(String name, StreamEntry entry) {
        var totalEvents = 0L;
        var totalBytes = 0L;
        for (var buffer : entry.partitions()) {
            totalEvents += buffer.eventCount();
            totalBytes += buffer.allocatedBytes();
        }
        return StreamInfo.streamInfo(name, entry.partitions().length, totalEvents, totalBytes);
    }

    private Result<Unit> closeAndRelease(StreamEntry entry) {
        totalAllocatedBytes.addAndGet(- calculateStreamBytes(entry.config()));
        entry.close();
        return success(unit());
    }

    private static long calculateStreamBytes(StreamConfig config) {
        var retention = config.retention();
        var perPartition = 64 + (24 * retention.maxCount()) + retention.maxBytes();
        return perPartition * config.partitions();
    }

    public Result<PartitionInfo> partitionInfo(String streamName, int partition) {
        return resolvePartitionBuffer(streamName, partition).map(buffer -> PartitionInfo.partitionInfo(partition,
                                                                                                       buffer.headOffset(),
                                                                                                       buffer.tailOffset(),
                                                                                                       buffer.eventCount()));
    }

    public Result<List<PartitionInfo>> allPartitionInfo(String streamName) {
        return option(streams.get(streamName)).toResult(new StreamError.StreamNotFound(streamName))
                     .map(StreamPartitionManager::buildAllPartitionInfo);
    }

    private static List<PartitionInfo> buildAllPartitionInfo(StreamEntry entry) {
        var infos = new ArrayList<PartitionInfo>();
        for (int i = 0;i <entry.partitions().length;i++) {
            var buffer = entry.partitions() [i];
            infos.add(PartitionInfo.partitionInfo(i, buffer.headOffset(), buffer.tailOffset(), buffer.eventCount()));
        }
        return List.copyOf(infos);
    }

    public record StreamInfo(String name, int partitions, long totalEvents, long totalBytes) {
        public static StreamInfo streamInfo(String name, int partitions, long totalEvents, long totalBytes) {
            return new StreamInfo(name, partitions, totalEvents, totalBytes);
        }
    }

    public record PartitionInfo(int partition, long headOffset, long tailOffset, long eventCount) {
        public static PartitionInfo partitionInfo(int partition, long headOffset, long tailOffset, long eventCount) {
            return new PartitionInfo(partition, headOffset, tailOffset, eventCount);
        }
    }

    record StreamEntry(StreamConfig config,
                       OffHeapRingBuffer[] partitions,
                       long createdAt,
                       AtomicLong lastActivityRef) implements AutoCloseable {
        static StreamEntry fromConfig(StreamConfig config, EvictionListener listener) {
            var retention = config.retention();
            var policy = deriveEvictionPolicy(config);
            var buffers = new OffHeapRingBuffer[config.partitions()];
            for (int i = 0;i <config.partitions();i++) {buffers[i] = OffHeapRingBuffer.offHeapRingBuffer(config.name(),
                                                                                                         i,
                                                                                                         retention.maxCount(),
                                                                                                         retention.maxBytes(),
                                                                                                         listener,
                                                                                                         policy);}
            var now = System.currentTimeMillis();
            return new StreamEntry(config, buffers, now, new AtomicLong(now));
        }

        long lastActivity() {
            return lastActivityRef.get();
        }

        @Contract void updateActivity() {
            lastActivityRef.set(System.currentTimeMillis());
        }

        private static EvictionPolicy deriveEvictionPolicy(StreamConfig config) {
            return config.consistencyMode() == ConsistencyMode.STRONG
                  ? EvictionPolicy.REJECT_WHEN_FULL
                  : EvictionPolicy.DROP_OLDEST;
        }

        @Contract@Override public void close() {
            for (var buffer : partitions) {buffer.close();}
        }
    }
}
