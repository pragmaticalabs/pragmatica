// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import org.pragmatica.lang.Result;
import org.pragmatica.storage.MetadataSnapshot;
import org.pragmatica.storage.SnapshotManager;
import org.pragmatica.storage.StorageGarbageCollector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// What retention runs under disk pressure after dropping expired refs (#1604): make the drops durable, then
/// collect the blocks they orphaned at once instead of after the collector's grace period. GC stays the single
/// delete path; this only lets it collect early what the grace period protects, once that protection holds.
@FunctionalInterface
public interface PressureRelief {
    Logger LOG = LoggerFactory.getLogger(PressureRelief.class);
    PressureRelief NONE = () -> 0;

    /// Blocks collected.
    int relieve();

    /// Force a metadata snapshot, then collect exactly the orphans that snapshot records
    /// ([StorageGarbageCollector#collectOrphansDurableIn]). A snapshot that cannot be written means no early
    /// collection this pass -- FER toward keeping the blocks; the normal cadence still collects them later.
    static PressureRelief snapshotBounded(SnapshotManager snapshots, StorageGarbageCollector collector) {
        return () -> collectIn(snapshots.snapshotNow(), collector);
    }

    private static int collectIn(Result<MetadataSnapshot> durable, StorageGarbageCollector collector) {
        return durable.onFailure(cause -> LOG.warn("Disk pressure: the forced metadata snapshot failed, so nothing is "
                                                   + "collected early this pass: {}",
                                                   cause.message()))
                      .map(collector::collectOrphansDurableIn)
                      .or(0);
    }
}
