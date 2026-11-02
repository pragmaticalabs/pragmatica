// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm.write;

/// What a [WriteSite] does when [TermWriter] refuses its write because this node is not the leader the committed
/// leader record names, or its term has ended (#2584).
public enum NotLeaderAction {
    /// The write fails with [FsmWriteError.NotLeader] and the site's own failure path runs.
    FAIL_NOT_LEADER,
    /// The rollback is abandoned: the site runs its `onAbandoned` and schedules no failure reconcile (#2592).
    ABANDON_ROLLBACK,
    /// The write fails with `HierarchyStateWriter.Refusal.INACTIVE`, the refusal hierarchy writes have always seen.
    INACTIVE_REFUSAL
}
