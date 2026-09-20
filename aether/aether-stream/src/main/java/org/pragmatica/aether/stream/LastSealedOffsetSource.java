// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Unit;


/// Source of the durable last-sealed offset for a `(stream, partition)`, used to bound WAL replay when
/// a partition ring is (re)built on recovery (streaming-persistence Phase A-WAL, step W4). Sealed
/// segments durably cover `[0, lastSealedOffset]` and are served post-restart by the tiered reader, so
/// the recovered ring must skip those records and replay only the UN-sealed tail
/// (`offset > lastSealedOffset`) at its ORIGINAL offsets.
///
/// The aether-level wiring binds the production source to the node's
/// [org.pragmatica.aether.stream.segment.SegmentIndex] (`streamSegmentIndex::lastSealedOffset`), which
/// is rebuilt from the durable `streams/` refs at boot; Forge/unit/legacy setups with no sealed
/// segments use [#none] (the floor `-1`). A floor of `-1` means nothing is sealed, so the fresh ring
/// is NOT seeded and replay appends the whole log from offset 0.
@FunctionalInterface
public interface LastSealedOffsetSource {
    /// The highest offset durably sealed into segments for `(stream, partition)`, or `-1` when nothing
    /// is sealed yet. WAL replay skips `offset <= lastSealedOffset` and recovers only the tail above it.
    long lastSealedOffset(String stream, int partition);

    /// `[fromOffset, toOffset]` of `(stream, partition)` was reclaimed by DROP_OLDEST without a seal (#1352):
    /// never acknowledged, not in the log. The source advances its watermark over the range as over a sealed
    /// segment, so WAL truncation proceeds past it and a read from it reports "reclaimed", not "a seal failed".
    /// The default forgets it: the floor source has no watermark to advance.
    @Contract
    default Unit markReclaimed(String stream, int partition, long fromOffset, long toOffset) {
        return Unit.unit();
    }

    /// The floor source (`-1` — nothing sealed) for non-cluster stream paths, legacy callers, and
    /// tests. With it, a recovered ring is never seeded and replays its full WAL from offset 0.
    static LastSealedOffsetSource none() {
        return (_, _) -> - 1L;
    }
}
