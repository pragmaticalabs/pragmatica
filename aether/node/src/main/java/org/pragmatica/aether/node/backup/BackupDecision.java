// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.backup;

import org.pragmatica.aether.slice.kvstore.BackupEntryCodec.BackupHeader;
import org.pragmatica.lang.Option;

/// Whether this cluster's state may become the backup head, given the head already there (#1532).
///
/// - **No head** — a brand-new backup: write it, which establishes this cluster's lineage.
/// - **Same lineage** — write unless the head is strictly ahead by `(incarnation, revision)`. A head at
///   exactly this position is this cluster's own queued write: writing it again commits nothing and
///   pushes what is pending. A head strictly ahead means this leader is behind the backup (a deposed
///   leader pushing late), and its state is dropped.
/// - **Another lineage** — write only when this cluster's incarnation is strictly higher, which is what
///   `aether backup declare-genesis` arranges. A freshly started cluster (incarnation 1, its own new
///   lineage) never overwrites an existing backup of another lineage: it is GATED until it restores that
///   backup (#1533) or an operator declares genesis.
public enum BackupDecision {
    WRITE,
    STALE,
    GATED;

    public static BackupDecision decide(BackupHeader ours, Option<BackupHeader> head) {
        return head.map(existing -> decideAgainst(ours, existing))
                   .or(WRITE);
    }

    private static BackupDecision decideAgainst(BackupHeader ours, BackupHeader head) {
        return ours.isSameLineage(head)
               ? aheadOrStale(ours, head)
               : supersedesOrGated(ours, head);
    }

    private static BackupDecision aheadOrStale(BackupHeader ours, BackupHeader head) {
        return head.isAhead(ours)
               ? STALE
               : WRITE;
    }

    private static BackupDecision supersedesOrGated(BackupHeader ours, BackupHeader head) {
        return ours.incarnation() > head.incarnation()
               ? WRITE
               : GATED;
    }
}
