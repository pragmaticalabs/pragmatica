// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.generation;

/// The epoch a consumer group's cursor runs under (#1333): identifies ONE projection rebuild's
/// rewind. `NONE` is the epoch of a group that has never been rewound.
///
/// It is the fencing token on `AetherValue.StreamCursorCheckpointValue`: a rewind PUTs the group's
/// cursor at `(fromOffset, epoch')`, and the KV applier then refuses every checkpoint stamped with a
/// strictly older epoch — a zombie consumer still committing its pre-rewind position cannot undo the
/// rewind, which is the only mechanism that closes `StreamConsumerManager`'s last-write-wins for a
/// rewind. Resume order is lexicographic `(epoch, offset)`, so a node that crashed before applying
/// the rewind cannot resurrect its stale-high local cursor either.
///
/// Not a `@Codec` type: `StreamCursorCheckpointValue` carries the two longs directly and derives
/// this, so the checkpoint value needs no nested record and no new wire tag. The values mirror the
/// projection facade's `ProjectionStore.RewindToken(generation, rewind)` one-to-one.
public record RewindEpoch(long generation, long rewind) implements Comparable<RewindEpoch> {
    public static final RewindEpoch NONE = new RewindEpoch(0L, 0L);

    public static RewindEpoch rewindEpoch(long generation, long rewind) {
        return new RewindEpoch(generation, rewind);
    }

    @Override
    public int compareTo(RewindEpoch other) {
        var byGeneration = Long.compare(generation, other.generation);

        return byGeneration != 0
               ? byGeneration
               : Long.compare(rewind, other.rewind);
    }

    public boolean isStrictlyAfter(RewindEpoch other) {
        return compareTo(other) > 0;
    }

    public boolean isNone() {
        return equals(NONE);
    }

    @Override
    public String toString() {
        return generation + "/" + rewind;
    }
}
