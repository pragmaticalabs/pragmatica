// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.SnapshotManager;


/// Makes the metadata writes made before it durable. The streams metadata store reaches disk only through
/// snapshots, so two steps force one before they act on a ref: a seal WITHOUT a log, before it resolves (#1441 -- the
/// ref is then the only record that the range was sealed), and retention, before it drops the segment refs a
/// reclaimed-through floor licenses (#1278 -- a restart must find the floor whenever it no longer finds the refs).
/// A ref written before [#persist] and not touched again while it stands is in the snapshot it forces, however weakly
/// consistent the capture is.
@FunctionalInterface
public interface RefDurability {
    /// Every metadata write made before this call is durable once it succeeds.
    Result<Unit> persist();
    /// For a store that is its own durable truth (in-memory, tests): nothing to do.
    RefDurability LIVE = Result::unitResult;

    /// Force a metadata snapshot to disk: the refs written before it are in it.
    static RefDurability snapshotted(SnapshotManager snapshots) {
        return () -> snapshots.snapshotNow()
                              .mapToUnit();
    }
}
