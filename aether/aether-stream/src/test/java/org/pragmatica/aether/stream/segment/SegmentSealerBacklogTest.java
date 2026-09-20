// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream.segment;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.segment.SegmentSealer.segmentSealer;
import static org.pragmatica.lang.Unit.unit;

/// #1234 (review of #1297): a backlog queued behind a storage stall is released to a sink that completes
/// SYNCHRONOUSLY. The drain used to recurse `sealed -> sealHead -> seal -> sealed` on one stack; a few hundred
/// segments deep a `StackOverflowError` was swallowed inside a promise callback with the in-flight flag still
/// set, and the partition never sealed again — no failure counted, no log. Stack depth must not depend on the
/// backlog: every segment seals, in offset order.
class SegmentSealerBacklogTest {
    private static final String STREAM = "backlog-stream";
    private static final int PARTITION = 0;
    private static final int BACKLOG = 5_000;
    private static final long AWAIT_MS = 30_000;
    private static final long POLL_NANOS = 10_000_000;

    private final List<Long> sealedOffsets = new CopyOnWriteArrayList<>();
    private final AtomicBoolean storageStalled = new AtomicBoolean(true);
    private final Promise<Unit> stall = Promise.promise();

    @Test
    void backlogReleasedToSynchronousSink_sealsEverySegmentInOrder_pendingBytesReachZero() {
        var sealer = segmentSealer(this::seal);

        LongStream.range(0, BACKLOG)
                  .forEach(offset -> sealer.onEviction(STREAM,
                                                       PARTITION,
                                                       List.of(RawEvent.rawEvent(offset, "e".getBytes(), offset))));
        awaitCondition(() -> !sealedOffsets.isEmpty());

        storageStalled.set(false);
        stall.succeed(unit());

        awaitCondition(() -> sealer.pendingBytes() == 0);

        assertThat(sealedOffsets).containsExactlyElementsOf(LongStream.range(0, BACKLOG).boxed().toList());
        assertThat(sealer.sealFailureCount()).isZero();
    }

    /// The first seal holds until the stall clears; every later one completes synchronously.
    private Promise<Unit> seal(SealedSegment segment) {
        sealedOffsets.add(segment.startOffset());

        return storageStalled.get()
               ? stall
               : Promise.success(unit());
    }

    private static void awaitCondition(BooleanSupplier condition) {
        var deadline = System.currentTimeMillis() + AWAIT_MS;

        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            LockSupport.parkNanos(POLL_NANOS);
        }

        assertThat(condition.getAsBoolean()).as("condition within %d ms", AWAIT_MS).isTrue();
    }
}
