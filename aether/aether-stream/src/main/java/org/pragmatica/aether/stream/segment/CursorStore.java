// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.pragmatica.aether.slice.generation.Epoch;
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
    /// #1271: offset + the assignment epoch it was written under (`rabiaTerm`, `localCounter`).
    private static final int FENCED_CURSOR_BYTES = 3 * Long.BYTES;

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
    /// as one operation. Blocks are CONTENT-ADDRESSED, and a cursor block is just the 8-byte offset — so
    /// every cursor in the node sitting at offset N shares one block — replaceRef's accounting handles
    /// this correctly: a shared block's count reflects exactly how many live refs still point at it, and
    /// it only reaches [org.pragmatica.storage.BlockLifecycle#isOrphaned] once none do, at which point
    /// [org.pragmatica.storage.StorageGarbageCollector] can reclaim it. Remaining exposure, pre-existing
    /// and unchanged by this fix: a cursor block becoming GC-reachable surfaces it to two known gaps in
    /// GC-eligible blocks generally: #801 (a concurrent deduplicating put can resurrect a block between
    /// GC's orphan scan and its delete step) and #802 (a block demoted to the DHT alone drops out of
    /// every node's local GC candidate set, with no cluster-wide reclamation process).
    @Override
    public Promise<CommitOutcome> commit(String consumerGroup, String streamName, int partition, long offset) {
        var refName = buildRefName(consumerGroup, streamName, partition);
        var payload = encodeOffset(offset);

        return storage.replaceRef(refName, payload)
                      .map(_ -> CommitOutcome.persisted())
                      .onSuccess(_ -> logCommit(consumerGroup, streamName, partition, offset));
    }

    /// #1271: the same single-ref upsert as [#commit(String, String, int, long)], with the assignment
    /// epoch recorded next to the offset so [#fetch(String, String, int, Epoch)] can tell a cursor from
    /// THIS tenure from one this node wrote while it held the partition earlier.
    @Override
    public Promise<CommitOutcome> commit(String consumerGroup,
                                         String streamName,
                                         int partition,
                                         long offset,
                                         Epoch assignmentEpoch) {
        var refName = buildRefName(consumerGroup, streamName, partition);

        return storage.replaceRef(refName,
                                  encodeFencedOffset(offset, assignmentEpoch))
                      .map(_ -> CommitOutcome.persisted())
                      .onSuccess(_ -> logCommit(consumerGroup, streamName, partition, offset));
    }

    /// Any recorded cursor, fenced or not — the pull API's view, which owns rewinds and has no assignment.
    @Override
    public Promise<Option<Long>> fetch(String consumerGroup, String streamName, int partition) {
        return readCursor(consumerGroup, streamName, partition).map(stored -> stored.map(StoredCursor::offset));
    }

    /// #1271: only a cursor written under `assignmentEpoch`. An unfenced cursor, or one from another
    /// tenure, answers [Option#empty] and the caller resumes from the cluster checkpoint instead.
    @Override
    public Promise<Option<Long>> fetch(String consumerGroup, String streamName, int partition, Epoch assignmentEpoch) {
        return readCursor(consumerGroup, streamName, partition).map(stored -> stored.filter(cursor -> cursor.writtenUnder(assignmentEpoch))
                                                                                    .map(StoredCursor::offset));
    }

    private Promise<Option<StoredCursor>> readCursor(String consumerGroup, String streamName, int partition) {
        var refName = buildRefName(consumerGroup, streamName, partition);

        return storage.resolveRef(refName)
                      .map(this::readStoredCursor)
                      .or(Promise.success(Option.empty()));
    }

    private Promise<Option<StoredCursor>> readStoredCursor(BlockId blockId) {
        return storage.get(blockId)
                      .map(bytes -> bytes.flatMap(CursorStore::decodeCursor));
    }

    /// An 8-byte block is an unfenced cursor (pull API, or written before #1271); a 24-byte block also
    /// carries the assignment epoch. Anything else is unreadable and treated as absent, as before.
    static Option<StoredCursor> decodeCursor(byte[] bytes) {
        return switch (bytes.length) {
            case Long.BYTES -> Option.some(new StoredCursor(decodeOffset(bytes), Option.none()));
            case FENCED_CURSOR_BYTES -> Option.some(decodeFencedCursor(bytes));
            default -> Option.none();
        };
    }

    private static StoredCursor decodeFencedCursor(byte[] bytes) {
        var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        var offset = buffer.getLong();
        var rabiaTerm = buffer.getLong();
        var localCounter = buffer.getLong();

        return new StoredCursor(offset,
                                Option.some(Epoch.epoch(rabiaTerm, localCounter)));
    }

    static byte[] encodeFencedOffset(long offset, Epoch assignmentEpoch) {
        return ByteBuffer.allocate(FENCED_CURSOR_BYTES)
                         .order(ByteOrder.BIG_ENDIAN)
                         .putLong(offset)
                         .putLong(assignmentEpoch.rabiaTerm())
                         .putLong(assignmentEpoch.localCounter())
                         .array();
    }

    /// A decoded cursor block: the offset, and the assignment epoch it was written under when fenced.
    record StoredCursor(long offset, Option<Epoch> assignmentEpoch) {
        boolean writtenUnder(Epoch epoch) {
            return assignmentEpoch.map(epoch::equals)
                                  .or(false);
        }
    }

    private static void logCommit(String consumerGroup, String streamName, int partition, long offset) {
        log.debug("Cursor committed: {}/{}/{} -> {}",
                  consumerGroup,
                  streamName,
                  partition,
                  offset);
    }

    static String buildRefName(String consumerGroup, String streamName, int partition) {
        return CURSORS_PREFIX + consumerGroup + "/" + streamName + "/" + partition;
    }

    static byte[] encodeOffset(long offset) {
        return ByteBuffer.allocate(Long.BYTES)
                         .order(ByteOrder.BIG_ENDIAN)
                         .putLong(offset)
                         .array();
    }

    static long decodeOffset(byte[] bytes) {
        return ByteBuffer.wrap(bytes)
                         .order(ByteOrder.BIG_ENDIAN)
                         .getLong();
    }
}
