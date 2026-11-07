// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.generation;

/// The scope inside which a consumer group's committed cluster cursor never decreases (#1961, overhaul invariant I-18):
/// the [RewindEpoch] first, then the owner [Epoch] (KIP-320), compared lexicographically. A strictly newer scope may lower
/// the cursor: an operator rewind mints a newer rewind epoch, a re-seek adopts a newer owner epoch.
///
/// A commit stamped [Epoch#ZERO] after a non-ZERO owner epoch inside one rewind epoch is a LOWER scope and is refused; no
/// production flow is known to make one (overhaul spec 8.1, F-27).
///
/// Not a `@Codec` type, like [RewindEpoch]: `StreamCursorCheckpointValue` derives it from fields it already carries, so the
/// checkpoint value's wire form is unchanged.
public record CursorScope(RewindEpoch rewindEpoch, Epoch ownerEpoch) implements Comparable<CursorScope> {
    public static CursorScope cursorScope(RewindEpoch rewindEpoch, Epoch ownerEpoch) {
        return new CursorScope(rewindEpoch, ownerEpoch);
    }

    @Override
    public int compareTo(CursorScope other) {
        var byRewind = rewindEpoch.compareTo(other.rewindEpoch);

        return byRewind != 0
               ? byRewind
               : ownerEpoch.compareTo(other.ownerEpoch);
    }
}
