// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.pragmatica.aether.slice.generation.RewindEpoch;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.StorageInstance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Node-local committed-offset store, backed by this node's [StorageInstance] ref index.
///
/// The ref index is per-node and is NOT replicated, so a cursor written here is invisible to every
/// other node. That is why [ConsumerCursorStore] exists: the node composes this store with a
/// consensus-KV cursor for deployment-declared consumers, which must resume after their partition's
/// owner changes.
public final class CursorStore implements ConsumerCursorStore {
    private static final Logger log = LoggerFactory.getLogger(CursorStore.class);
    private static final String CURSORS_PREFIX = "cursors/";
    /// `offset | rewindGeneration | rewindSequence`, three big-endian longs (#1333).
    static final int CURSOR_BYTES = 3 * Long.BYTES;

    private final StorageInstance storage;

    private CursorStore(StorageInstance storage) {
        this.storage = storage;
    }

    public static CursorStore cursorStore(StorageInstance storage) {
        return new CursorStore(storage);
    }

    /// Replaces the committed offset with a SINGLE ref write (#264).
    ///
    /// The ref is upserted, never removed first. The previous form was `deleteRef` then `createRef`, which
    /// left a window in which the ref did not exist at all — and for a cursor, absent is much worse than
    /// stale: [#fetch] answers `Option.empty()`, which the caller reads as "this group has never
    /// committed" and resumes from the earliest RETAINED offset. So a crash in that window did not cost a
    /// few events, it redelivered the entire retained window. A single upsert has no such window: the
    /// ref itself always resolves to a valid block, either the old offset or the new one, at every
    /// instant. That guarantee covers the REF's presence only -- it says nothing about the refcount
    /// bookkeeping of the block behind it; see below for that.
    ///
    /// The superseded block IS reclaimed (#737, fixed): [StorageInstance#replaceRef] is a refcount-aware
    /// ref-replace that increments the new block and decrements whatever the ref previously pointed to,
    /// as one operation. Blocks are CONTENT-ADDRESSED, and a cursor block is just the 24-byte cursor
    /// (offset + rewind epoch, #1333) — so every cursor in the node sitting at offset N under the same
    /// epoch shares one block — replaceRef's accounting handles
    /// this correctly: a shared block's count reflects exactly how many live refs still point at it, and
    /// it only reaches [org.pragmatica.storage.BlockLifecycle#isOrphaned] once none do, at which point
    /// [org.pragmatica.storage.StorageGarbageCollector] can reclaim it. Remaining exposure, pre-existing
    /// and unchanged by this fix: a cursor block becoming GC-reachable surfaces it to two known gaps in
    /// GC-eligible blocks generally: #801 (a concurrent deduplicating put can resurrect a block between
    /// GC's orphan scan and its delete step) and #802 (a block demoted to the DHT alone drops out of
    /// every node's local GC candidate set, with no cluster-wide reclamation process).
    @Override
    public Promise<CommitOutcome> commit(String consumerGroup, String streamName, int partition, long offset) {
        return commit(consumerGroup, streamName, partition, offset, RewindEpoch.NONE);
    }

    /// #1333: the block is the 24-byte `offset | rewindGeneration | rewindSequence` cursor, so the epoch a
    /// consumer committed under survives a same-node restart and a resume can rank the local cursor
    /// against the cluster one by `(epoch, offset)`.
    @Override
    public Promise<CommitOutcome> commit(String consumerGroup,
                                         String streamName,
                                         int partition,
                                         long offset,
                                         RewindEpoch epoch) {
        var refName = buildRefName(consumerGroup, streamName, partition);
        var payload = encodeCursor(offset, epoch);

        return storage.replaceRef(refName, payload)
                      .map(_ -> CommitOutcome.persisted())
                      .onSuccess(_ -> logCommit(consumerGroup, streamName, partition, offset, epoch));
    }

    @Override
    public Promise<Option<Long>> fetch(String consumerGroup, String streamName, int partition) {
        return fetchCursor(consumerGroup, streamName, partition).map(cursor -> cursor.map(Cursor::offset));
    }

    /// A ref whose block is not exactly [#CURSOR_BYTES] long reads as ABSENT (resume from the earliest
    /// retained offset). That includes the pre-#1333 8-byte offset-only block: a node upgraded with such
    /// refs on disk resumes each group from earliest ONCE, then rewrites the ref in the new layout — a
    /// pre-GA one-time redelivery, stated in the changelog, taken over carrying a layout fork forever.
    @Override
    public Promise<Option<Cursor>> fetchCursor(String consumerGroup, String streamName, int partition) {
        var refName = buildRefName(consumerGroup, streamName, partition);

        return storage.resolveRef(refName)
                      .map(this::readCursor)
                      .or(Promise.success(Option.empty()));
    }

    private Promise<Option<Cursor>> readCursor(BlockId blockId) {
        return storage.get(blockId)
                      .map(CursorStore::decodeOptionalCursor);
    }

    private static Option<Cursor> decodeOptionalCursor(Option<byte[]> opt) {
        return opt.filter(bytes -> bytes.length == CURSOR_BYTES)
                  .map(CursorStore::decodeCursor);
    }

    private static void logCommit(String consumerGroup, String streamName, int partition, long offset, RewindEpoch epoch) {
        log.debug("Cursor committed: {}/{}/{} -> {} @ epoch {}",
                  consumerGroup,
                  streamName,
                  partition,
                  offset,
                  epoch);
    }

    static String buildRefName(String consumerGroup, String streamName, int partition) {
        return CURSORS_PREFIX + consumerGroup + "/" + streamName + "/" + partition;
    }

    /// The offset-only encoding: an unrewound cursor. Same bytes [#commit(String, String, int, long)]
    /// writes, so a test computing a block id from an offset matches what that commit stored.
    static byte[] encodeOffset(long offset) {
        return encodeCursor(offset, RewindEpoch.NONE);
    }

    static byte[] encodeCursor(long offset, RewindEpoch epoch) {
        return ByteBuffer.allocate(CURSOR_BYTES)
                         .order(ByteOrder.BIG_ENDIAN)
                         .putLong(offset)
                         .putLong(epoch.generation())
                         .putLong(epoch.rewind())
                         .array();
    }

    static long decodeOffset(byte[] bytes) {
        return ByteBuffer.wrap(bytes)
                         .order(ByteOrder.BIG_ENDIAN)
                         .getLong();
    }

    static Cursor decodeCursor(byte[] bytes) {
        var buffer = ByteBuffer.wrap(bytes)
                               .order(ByteOrder.BIG_ENDIAN);

        return Cursor.cursor(buffer.getLong(), RewindEpoch.rewindEpoch(buffer.getLong(), buffer.getLong()));
    }
}
