// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

/// What a `GOVERNOR` read may make of this node's copy of a partition ([StreamPartitionManager#replicaStaleness]). Two
/// reasons to go to the owner instead, because they differ in what an owner that cannot be asked leaves the read with.
public enum ReplicaStaleness {
    /// The copy has reached the mark the owner it follows announced, or this node is the owner: the copy serves the read.
    CURRENT,
    /// The copy is below the announced mark, or nothing was announced in this node's life. It is behind and never ahead of
    /// what is committed, so when the owner cannot be asked the copy's own answer stands in.
    BEHIND,
    /// The announced mark belongs to an owner life that has ended (#3325): the copy has no bound it may serve from, so when
    /// the owner cannot be asked the read is refused.
    VOID_OWNER_MARK
}
