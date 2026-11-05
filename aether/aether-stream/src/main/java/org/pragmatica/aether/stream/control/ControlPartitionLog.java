// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.control;

import java.util.List;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.OffHeapRingBuffer;
import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.StreamPartitionManager.ControlRunAppend;
import org.pragmatica.aether.stream.forward.CatchupRead;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.aether.stream.segment.TieredStreamReader;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/// What the control log needs from this node's copy of a partition (#2590 P1). The production log is the stream
/// substrate ([#partitionManagerLog]); the control log adds admission, the in-flight tail and the latest-per-key index on
/// top of it and never touches the ring or the WAL any other way.
public interface ControlPartitionLog {
    /// The owner append of `payloads` as one run, refused before anything is appended or appended with its fsync pending.
    Result<ControlRunAppend> appendRun(String streamName, int partition, List<byte[]> payloads, long timestamp);

    /// The confirmation barrier for `lastOffset`, with the confirmation factor read NOW, after the append (#1391: an owner
    /// that materialized the stream inside the append would otherwise use the 0 it reported before).
    Promise<Unit> awaitConfirmed(String streamName, int partition, long lastOffset);
    /// Appended records of the retained log from `fromOffset`, the ring and below it the sealed tier.
    Promise<List<RawEvent>> read(String streamName, int partition, long fromOffset, int maxEvents);
    /// Appended records the ring holds from `fromOffset`; the committed feed reads only recent records.
    Result<List<RawEvent>> readRing(String streamName, int partition, long fromOffset, int maxEvents);
    /// The oldest offset this node retains for the partition: where an index rebuild starts.
    long retainedFrom(String streamName, int partition);
    /// The highest appended offset, `-1` when nothing is.
    long head(String streamName, int partition);
    /// How far this node knows the partition to be committed ([StreamPartitionManager#committedThrough]).
    long committedThrough(String streamName, int partition);
    /// The ownership epoch an owner append is stamped with now; a change of it is a change of owner.
    Epoch ownerEpoch(String streamName, int partition);
    /// The change counter of the committed in-sync set, `0` when the ownership record has none.
    long isrVersion(String streamName, int partition);
    /// The identity of the ring serving the partition here, [Option#none] when none is materialized. A new one is a new
    /// materialization, and the index over the old one no longer describes it.
    Option<Long> ringIncarnation(String streamName, int partition);

    static ControlPartitionLog partitionManagerLog(StreamPartitionManager manager,
                                                   Option<TieredStreamReader> tier,
                                                   Option<SegmentIndex> sealedIndex) {
        return new PartitionManagerLog(manager, tier, sealedIndex);
    }
}

record PartitionManagerLog(StreamPartitionManager manager,
                           Option<TieredStreamReader> tier,
                           Option<SegmentIndex> sealedIndex) implements ControlPartitionLog {
    @Override
    public Result<ControlRunAppend> appendRun(String streamName, int partition, List<byte[]> payloads, long timestamp) {
        return manager.appendControlRun(streamName, partition, payloads, timestamp);
    }

    @Override
    public Promise<Unit> awaitConfirmed(String streamName, int partition, long lastOffset) {
        var confirmationFactor = manager.confirmationFactorFor(streamName);

        return confirmationFactor > 1
               ? manager.awaitReplication(streamName, partition, lastOffset, confirmationFactor - 1)
               : Promise.unitPromise();
    }

    @Override
    public Promise<List<RawEvent>> read(String streamName, int partition, long fromOffset, int maxEvents) {
        return CatchupRead.readAppended(manager, tier, streamName, partition, fromOffset, maxEvents);
    }

    @Override
    public Result<List<RawEvent>> readRing(String streamName, int partition, long fromOffset, int maxEvents) {
        return manager.readAppended(streamName, partition, fromOffset, maxEvents);
    }

    /// Below the ring, the oldest sealed segment this node indexed; without a sealed index, the ring's own tail.
    @Override
    public long retainedFrom(String streamName, int partition) {
        var ringTail = manager.earliestRetainedOffset(streamName, partition);

        return sealedIndex.flatMap(index -> oldestSealed(index, streamName, partition))
                          .filter(sealed -> sealed < ringTail)
                          .or(Math.max(ringTail, 0L));
    }

    private static Option<Long> oldestSealed(SegmentIndex index, String streamName, int partition) {
        return Option.from(index.listSegments(streamName, partition)
                                .stream()
                                .map(SegmentIndex.SegmentRef::startOffset)
                                .min(Long::compare));
    }

    @Override
    public long head(String streamName, int partition) {
        return manager.partitionBuffer(streamName, partition)
                      .map(OffHeapRingBuffer::headOffset)
                      .or(-1L);
    }

    @Override
    public long committedThrough(String streamName, int partition) {
        return manager.committedThrough(streamName, partition);
    }

    @Override
    public Epoch ownerEpoch(String streamName, int partition) {
        return manager.currentOwnerEpoch(streamName, partition);
    }

    @Override
    public long isrVersion(String streamName, int partition) {
        return manager.committedIsrVersion(streamName, partition);
    }

    @Override
    public Option<Long> ringIncarnation(String streamName, int partition) {
        return manager.ringIncarnation(streamName, partition);
    }
}
