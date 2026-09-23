// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.lang.Cause;
import org.pragmatica.storage.MetadataSnapshot;
import org.pragmatica.storage.SnapshotManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Point-in-time source of the sealed watermarks a RESTART would rebuild (#1345) — the only bound WAL
/// truncation may use.
///
/// The live [SegmentIndex] advances the moment a seal lands, but its refs reach disk only through the storage
/// metadata snapshot. Truncating the WAL off the live index and crashing before that snapshot left the
/// survivors' refs nowhere: recovery rebuilt a LOWER watermark, seeded the ring below the point the WAL was
/// already compacted past, and appended the survivors at fresh offsets — silent renumbering. So the truncation
/// bound is derived from the refs that are already on disk: what [SegmentIndex#rebuildFromRefs] would compute
/// at the next boot, never what the live index says now.
///
/// [#current] is read ONCE per truncation tick (a snapshot may be read from disk), and the returned source is
/// consulted per partition.
@FunctionalInterface
public interface DurableSealedOffsetSource {
    Logger LOG = LoggerFactory.getLogger(DurableSealedOffsetSource.class);
    LastSealedOffsetSource current();

    /// Nothing durable — truncation never discards anything.
    static DurableSealedOffsetSource none() {
        return () -> LastSealedOffsetSource.none();
    }

    /// Treat `source` as durable. For standalone/test wiring whose index IS the durable truth (a test that
    /// writes the index directly, or one modelling a restart by handing the same index to the rebuilt manager).
    static DurableSealedOffsetSource same(LastSealedOffsetSource source) {
        return () -> source;
    }

    /// The watermarks of the latest metadata snapshot ON DISK, rebuilt the way boot rebuilds them. No snapshot
    /// yet, or an unreadable one, means nothing is durable and nothing is truncated — the direction that keeps
    /// the WAL. `[unverified: power loss]` — the snapshot file is written without fsync, so "on disk" here
    /// means process-crash-durable.
    ///
    /// #1013 made "unreadable" a failure distinct from "none yet" at the manager; here both still mean "keep
    /// the WAL". This is a truncation tick, not the boot path -- the boot that could have refused already ran.
    ///
    /// The failure is WARNed HERE, at [#onDisk], and that is not redundant with the manager's own logging. The
    /// manager WARNs per FILE (`Snapshot restore failed`) and per aggregate verdict
    /// (`DefaultSnapshotManager.reportNothingRestorable`); neither says what THIS caller then did about it.
    /// Snapshots that tear after a good boot make every subsequent tick fail, and the operator's only
    /// running-node signal is that this tick kept the WAL instead of truncating.
    static DurableSealedOffsetSource fromLatestSnapshot(SnapshotManager snapshotManager) {
        return () -> onDisk(snapshotManager);
    }

    private static LastSealedOffsetSource onDisk(SnapshotManager snapshotManager) {
        return snapshotManager.restoreFromLatest()
                              .onFailure(DurableSealedOffsetSource::reportUnreadable)
                              .fold(_ -> LastSealedOffsetSource.none(),
                                    restored -> restored.map(DurableSealedOffsetSource::indexOf)
                                                        .or(LastSealedOffsetSource.none()));
    }

    /// #1013 round 2: the truncation tick's own signal. Nothing durable is reported, so nothing is discarded
    /// and the WAL is kept -- data stays safe, which is why this is a WARN and not an ERROR, and why it is
    /// worth saying at all: without it the safe outcome is also a silent one.
    private static void reportUnreadable(Cause cause) {
        LOG.warn("No durable sealed offset this tick: {}. Keeping the WAL; nothing is truncated.", cause.message());
    }

    private static LastSealedOffsetSource indexOf(MetadataSnapshot snapshot) {
        var index = new SegmentIndex();

        index.rebuildFromRefs(snapshot.refs());

        return index::lastSealedOffset;
    }
}
