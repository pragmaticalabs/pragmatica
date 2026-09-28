// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.backup;

import org.pragmatica.aether.slice.kvstore.BackupEntryCodec.BackupHeader;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.parse.Number;

/// Whether this cluster's state may become the backup head, given the head already there (#1532).
///
/// **The remote's lineage changes only by an operator declaration.**
///
/// - **No head** — a brand-new backup: write it, which establishes this cluster's lineage.
/// - **Same lineage** — write unless the head is strictly ahead by `(incarnation, revision)`. A head at
///   exactly this position is this cluster's own queued write: writing it again commits nothing and
///   pushes what is pending. A head strictly ahead means this leader is behind the backup (a deposed
///   leader pushing late), and its state is dropped.
/// - **Another lineage** — GATED, whatever the incarnations, unless the head carries a [Declaration]
///   naming exactly this cluster's `(lineage, incarnation)`. Only `aether backup declare-genesis` writes
///   one. Incarnation order alone never replaces another lineage: a node that installed an old snapshot of
///   a divergent lineage (§6.4 mixed wipe) must not overwrite the backup because its number is larger.
public enum BackupDecision {
    WRITE,
    STALE,
    GATED;

    /// An operator's genesis declaration, as committed in the backup repository.
    public record Declaration(String lineageId, long incarnation) {
        public static Declaration declaration(String lineageId, long incarnation) {
            return new Declaration(lineageId, incarnation);
        }

        /// `<lineage> <incarnation>`; absent when the text is not exactly that.
        public static Option<Declaration> parse(String text) {
            var parts = text.strip()
                            .split(" ");

            return parts.length == 2
                   ? Number.parseLong(parts[1])
                           .option()
                           .map(incarnation -> declaration(parts[0], incarnation))
                   : Option.none();
        }

        public String render() {
            return lineageId + " " + incarnation + "\n";
        }

        boolean authorizes(BackupHeader ours) {
            return lineageId.equals(ours.lineageId()) && incarnation == ours.incarnation();
        }
    }

    public static BackupDecision decide(BackupHeader ours, Option<BackupHeader> head, Option<Declaration> declaration) {
        return head.map(existing -> decideAgainst(ours, existing, declaration))
                   .or(WRITE);
    }

    private static BackupDecision decideAgainst(BackupHeader ours, BackupHeader head, Option<Declaration> declaration) {
        return ours.isSameLineage(head)
               ? aheadOrStale(ours, head)
               : declaredOrGated(ours, declaration);
    }

    private static BackupDecision aheadOrStale(BackupHeader ours, BackupHeader head) {
        return head.isAhead(ours)
               ? STALE
               : WRITE;
    }

    private static BackupDecision declaredOrGated(BackupHeader ours, Option<Declaration> declaration) {
        return declaration.filter(declared -> declared.authorizes(ours))
                          .isPresent()
               ? WRITE
               : GATED;
    }
}
