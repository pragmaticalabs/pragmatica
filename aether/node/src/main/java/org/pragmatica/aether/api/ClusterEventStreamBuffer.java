// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamAccess;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/// In-process, node-local, bounded {@link StreamAccess} backing for the framework
/// `system:cluster-events:1.0.0` stream (stream-namespaces rebuild, Stage 4).
///
/// **Why object-ring rather than the partition-managed transport.** The
/// `StreamPartitionManager` transport (`DefaultStreamPublisher` / `PartitionedStreamAccess`) stores
/// serialized `byte[]`, which would require generating and registering serialization codecs for the
/// entire 30-variant sealed {@link ClusterEvent} hierarchy plus its nested `HlcTimestamp` /
/// `StreamAddress` payloads. That codec surface is out of Stage 4 scope (and the upstream PR never
/// wired any transport for this stream — its publisher/consumer suppliers were left returning null,
/// so the events stream was registered but never enforced any retention). This object-ring keeps
/// the sealed records in heap, avoids the serialization rabbit hole, and — crucially — actually
/// enforces a bounded retention so the stream cannot grow unbounded after the sweeper's deletion.
///
/// **Retention enforcement (closes the Stage-4 item-6 gap).** A single-partition `ArrayDeque`
/// capped at `retention.maxCount()` entries; on publish, oldest entries are evicted
/// (drop-oldest) when the cap is exceeded. This is the same drop-oldest semantics the
/// partition-manager's `OffHeapRingBuffer` provides for app streams, applied at append time so the
/// in-heap footprint is structurally bounded. Offsets are monotonic (head offset keeps advancing as
/// older entries are evicted); `fetch(fromOffset, max)` returns the still-retained suffix.
///
/// **Locality.** Node-local, matching the RC1 system-stream locality documented on
/// `SystemStreamFactories` ("system streams in RC1 read from the local owner"). Cluster events are
/// observed and surfaced per-node; this is not Rabia-replicated (neither was the upstream PR's
/// design — the partition transport replicates only `StreamConfig` metadata, not payloads).
final class ClusterEventStreamBuffer implements StreamAccess<ClusterEvent> {
    private final String streamName;
    private final long maxCount;
    private final ArrayDeque<StreamEvent<ClusterEvent>> ring = new ArrayDeque<>();
    private long nextOffset;

    private ClusterEventStreamBuffer(String streamName, long maxCount) {
        this.streamName = streamName;
        this.maxCount = maxCount <= 0 ? 1 : maxCount;
    }

    static ClusterEventStreamBuffer clusterEventStreamBuffer(String streamName, RetentionPolicy retention) {
        return new ClusterEventStreamBuffer(streamName, retention.maxCount());
    }

    @Override public synchronized Promise<Long> publish(ClusterEvent event) {
        var offset = nextOffset++;
        ring.addLast(new StreamEvent<>(offset, System.currentTimeMillis(), 0, event));
        while (ring.size() > maxCount) {
            ring.pollFirst();
        }
        return Promise.success(offset);
    }

    @Override public synchronized Promise<List<StreamEvent<ClusterEvent>>> fetch(long fromOffset, int maxEvents) {
        var out = new ArrayList<StreamEvent<ClusterEvent>>(Math.min(maxEvents, ring.size()));
        for (var event : ring) {
            if (event.offset() < fromOffset) {continue;}
            if (out.size() >= maxEvents) {break;}
            out.add(event);
        }
        return Promise.success(List.copyOf(out));
    }

    @Override public Promise<List<StreamEvent<ClusterEvent>>> fetch(int partition, long fromOffset, int maxEvents) {
        return fetch(fromOffset, maxEvents);
    }

    @Override public Promise<Unit> commit(String consumerGroup, int partition, long offset) {
        return Promise.unitPromise();
    }

    @Override public Promise<Option<Long>> committedOffset(String consumerGroup, int partition) {
        return Promise.success(Option.none());
    }

    @Override public synchronized Promise<StreamMetadata> metadata() {
        var headOffset = ring.isEmpty() ? -1L : ring.peekFirst().offset();
        var tailOffset = ring.isEmpty() ? -1L : ring.peekLast().offset();
        var partition = new PartitionInfo(0, headOffset, tailOffset, ring.size());
        return Promise.success(new StreamMetadata(streamName, 1, List.of(partition)));
    }
}
