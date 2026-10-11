// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.aether.stream.segment.SegmentSink;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;

/// Test support for reading a sealed offset through the tier. The sealer indexes a segment, resolves the sink's
/// seal promise, and only THEN drops its retained copy; until it does, a read of the offset is refused as
/// `SealInFlight` — the designed transient (#1234). So "indexed" does not mean "readable", and a wait that stops
/// at the index races that refusal. [#laggingRelease] makes the gap wide and deterministic, [#awaitReadable]
/// is the wait that closes it.
final class SealReleaseLag {
    /// How long the sink holds a seal's promise after the segment is indexed.
    static final long RELEASE_LAG_MS = 100;

    private SealReleaseLag() {}

    static SegmentSink laggingRelease(SegmentSink delegate) {
        return (segment, log) -> delegate.seal(segment, log).flatMap(_ -> {
            Promise<Unit> released = Promise.promise();

            Thread.startVirtualThread(() -> {
                try {
                    Thread.sleep(RELEASE_LAG_MS);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
                released.succeed(Unit.unit());
            });
            return released;
        });
    }

    /// Waits until `offset` is indexed AND the sealer no longer retains it, then asserts both.
    static void awaitReadable(SegmentIndex index, StreamPartitionManager manager, String stream, int partition, long offset) {
        var deadline = System.nanoTime() + 10_000_000_000L;

        while (!readable(index, manager, stream, partition, offset) && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(index.lastSealedOffset(stream, partition)).as("sealed through %d", offset).isGreaterThanOrEqualTo(offset);
        assertThat(manager.sealInFlight(stream, partition, offset)).as("offset %d released by the sealer", offset).isFalse();
    }

    private static boolean readable(SegmentIndex index, StreamPartitionManager manager, String stream, int partition, long offset) {
        return index.lastSealedOffset(stream, partition) >= offset && !manager.sealInFlight(stream, partition, offset);
    }
}
