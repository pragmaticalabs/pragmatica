// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
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
    /// few events, it redelivered the entire retained window. A single upsert has no such window: the ref
    /// resolves to either the old offset or the new one at every instant.
    ///
    /// What this deliberately does NOT do, stated because the accounting is now asymmetric: the superseded
    /// block is not reclaimed, so each commit leaves one behind. It cannot be reclaimed here. Blocks are
    /// CONTENT-ADDRESSED, and a cursor block is just the 8-byte offset — so every cursor in the node
    /// sitting at offset N shares one block, and deleting "the old block" would pull it out from under any
    /// other group or partition currently at that offset. Releasing it correctly needs a refcount-aware
    /// replace on [StorageInstance] (put-ref + increment-new + decrement-old as one step), which does not
    /// exist today; note also that [org.pragmatica.storage.BlockLifecycle#isUnreferenced] currently has NO
    /// callers, so the decrement the old code performed reclaimed nothing either. The leak predates this
    /// change and is unchanged by it — see #264.
    @Override
    public Promise<Unit> commit(String consumerGroup, String streamName, int partition, long offset) {
        var refName = buildRefName(consumerGroup, streamName, partition);
        var payload = encodeOffset(offset);

        return storage.put(payload)
                      .flatMap(blockId -> storage.createRef(refName, blockId))
                      .onSuccess(_ -> logCommit(consumerGroup, streamName, partition, offset));
    }

    @Override
    public Promise<Option<Long>> fetch(String consumerGroup, String streamName, int partition) {
        var refName = buildRefName(consumerGroup, streamName, partition);

        return storage.resolveRef(refName)
                      .map(this::readOffset)
                      .or(Promise.success(Option.empty()));
    }

    private Promise<Option<Long>> readOffset(BlockId blockId) {
        return storage.get(blockId)
                      .map(CursorStore::decodeOptionalOffset);
    }

    private static Option<Long> decodeOptionalOffset(Option<byte[]> opt) {
        return opt.filter(bytes -> bytes.length == Long.BYTES)
                  .map(CursorStore::decodeOffset);
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
