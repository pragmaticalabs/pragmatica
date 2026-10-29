// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.replica;

import java.util.ArrayList;
import java.util.List;

import org.pragmatica.cluster.state.kvstore.AnchorPair;
import org.pragmatica.cluster.state.kvstore.IncarnationTag;


/// Where a replica stands in its source's history: the incarnation tag of the history it follows, the Rabia phase frontier it has applied, and the
/// last [#ANCHOR_PAIRS] `(slot, batchId)` pairs of effect-bearing entries it applied (oldest first). A position without an anchor is
/// UNANCHORED: it proves nothing to a source, so it is never offered for resumption (a disconnect then costs one snapshot).
public record ReplicaPosition(IncarnationTag tag, long applied, List<AnchorPair> anchor) {
    public static final int ANCHOR_PAIRS = 8;
    private static final ReplicaPosition NONE = new ReplicaPosition(IncarnationTag.NONE, 0L, List.of());

    public ReplicaPosition {
        anchor = List.copyOf(anchor);
    }

    /// No history followed: nothing held yet, or a held position discarded.
    public static ReplicaPosition none() {
        return NONE;
    }

    public static ReplicaPosition replicaPosition(IncarnationTag tag, long applied, List<AnchorPair> anchor) {
        return new ReplicaPosition(tag, applied, anchor);
    }

    public boolean isHeld() {
        return ! tag.isNone();
    }

    /// Resumable at a source iff it follows a history and carries at least one anchor pair to verify.
    public boolean isResumable() {
        return isHeld() && !anchor.isEmpty();
    }

    /// The position after applying an effect entry: the frontier moves to `applied` and the pair joins the anchor, the oldest pair leaving past
    /// [#ANCHOR_PAIRS].
    public ReplicaPosition advancedBy(long newApplied, AnchorPair pair) {
        var all = new ArrayList<>(anchor);

        all.add(pair);

        return new ReplicaPosition(tag,
                                   newApplied,
                                   all.subList(Math.max(0, all.size() - ANCHOR_PAIRS),
                                               all.size()));
    }

    /// The position after applying a range marker: the frontier moves, the anchor stays.
    public ReplicaPosition movedTo(long newApplied) {
        return new ReplicaPosition(tag, newApplied, anchor);
    }
}
