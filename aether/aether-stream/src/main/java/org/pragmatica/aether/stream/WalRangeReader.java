// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;
import org.pragmatica.aether.stream.segment.SegmentError;
import org.pragmatica.aether.stream.wal.PartitionWal;
import org.pragmatica.aether.stream.wal.PartitionWal.WalRecord;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Unit.unit;


/// Reads an evicted offset range back out of its partition's WAL (#1234). The WAL is never truncated above
/// the contiguous sealed watermark, so while a seal is pending its range is still in the WAL — which lets the
/// segment sealer drop its heap copy and rebuild the segment from here when it retries.
public interface WalRangeReader {
    /// Whether `(streamName, partition)` has a WAL — the crash-durable holder of its unsealed range. Without
    /// one (a manager built with no WAL directory, e.g. Ember or Forge without a data dir) nothing but the
    /// sealer's heap copy holds an evicted range.
    boolean durable(String streamName, int partition);
    /// Exactly the records `[fromOffset, toOffset]`, in offset order — or a failure. Never a shorter range and
    /// never one with a gap: a segment rebuilt from a partial range would seal a hole as if it were whole.
    Result<List<RawEvent>> read(String streamName, int partition, long fromOffset, long toOffset);

    static WalRangeReader walRangeReader(BiFunction<String, Integer, Option<PartitionWal>> walLookup) {
        record walRangeReader(BiFunction<String, Integer, Option<PartitionWal>> walLookup) implements WalRangeReader {
            @Override
            public boolean durable(String streamName, int partition) {
                return walLookup.apply(streamName, partition)
                                .isPresent();
            }

            @Override
            public Result<List<RawEvent>> read(String streamName, int partition, long fromOffset, long toOffset) {
                return walLookup.apply(streamName, partition)
                                .toResult(missing(streamName, partition, fromOffset, toOffset, 0))
                                .flatMap(wal -> collect(wal, fromOffset, toOffset))
                                .flatMap(events -> exactRange(streamName, partition, fromOffset, toOffset, events));
            }

            private static Result<List<RawEvent>> collect(PartitionWal wal, long fromOffset, long toOffset) {
                var events = new ArrayList<RawEvent>();

                return wal.replay(fromOffset - 1,
                                  walRecord -> collectWithin(events, walRecord, toOffset))
                          .map(_ -> List.copyOf(events));
            }

            private static Unit collectWithin(List<RawEvent> events, WalRecord walRecord, long toOffset) {
                if (walRecord.offset() <= toOffset) {
                    events.add(RawEvent.rawEvent(walRecord.offset(), walRecord.payload(), walRecord.timestampMillis()));
                }

                return unit();
            }

            private static Result<List<RawEvent>> exactRange(String streamName,
                                                             int partition,
                                                             long fromOffset,
                                                             long toOffset,
                                                             List<RawEvent> events) {
                return isExactly(events, fromOffset, toOffset)
                       ? success(events)
                       : missing(streamName, partition, fromOffset, toOffset, events.size()).result();
            }

            private static boolean isExactly(List<RawEvent> events, long fromOffset, long toOffset) {
                if (events.size() != toOffset - fromOffset + 1) {
                    return false;
                }

                for (int i = 0; i < events.size(); i++) {
                    if (events.get(i).offset() != fromOffset + i) {
                        return false;
                    }
                }

                return true;
            }

            private static SegmentError.WalRangeMissing missing(String streamName,
                                                                int partition,
                                                                long fromOffset,
                                                                long toOffset,
                                                                int found) {
                return new SegmentError.WalRangeMissing(streamName, partition, fromOffset, toOffset, found);
            }
        }

        return new walRangeReader(walLookup);
    }
}
