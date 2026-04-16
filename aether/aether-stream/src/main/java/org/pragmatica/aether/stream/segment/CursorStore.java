// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.StorageInstance;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Persists consumer group cursor offsets in AHSE using named references.
///
/// Key format: `cursors/{consumerGroup}/{streamName}/{partition}`
/// Value: 8-byte big-endian long offset stored as a data block, referenced by name.
///
/// On commit, the previous ref (if any) is replaced with the new block.
public final class CursorStore {
    private static final Logger log = LoggerFactory.getLogger(CursorStore.class);

    private static final String CURSORS_PREFIX = "cursors/";

    private final StorageInstance storage;

    private CursorStore(StorageInstance storage) {
        this.storage = storage;
    }

    public static CursorStore cursorStore(StorageInstance storage) {
        return new CursorStore(storage);
    }

    public Promise<Unit> commit(String consumerGroup, String streamName, int partition, long offset) {
        var refName = buildRefName(consumerGroup, streamName, partition);
        var payload = encodeOffset(offset);
        return storage.put(payload).flatMap(blockId -> replaceRef(refName, blockId))
                          .onSuccess(_ -> logCommit(consumerGroup, streamName, partition, offset));
    }

    public Promise<Option<Long>> fetch(String consumerGroup, String streamName, int partition) {
        var refName = buildRefName(consumerGroup, streamName, partition);
        return storage.resolveRef(refName).map(this::readOffset)
                                 .or(Promise.success(Option.empty()));
    }

    private Promise<Unit> replaceRef(String refName, BlockId blockId) {
        return storage.deleteRef(refName).flatMap(_ -> storage.createRef(refName, blockId));
    }

    private Promise<Option<Long>> readOffset(BlockId blockId) {
        return storage.get(blockId).map(CursorStore::decodeOptionalOffset);
    }

    private static Option<Long> decodeOptionalOffset(Option<byte[]> opt) {
        return opt.filter(bytes -> bytes.length == Long.BYTES).map(CursorStore::decodeOffset);
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
        return ByteBuffer.allocate(Long.BYTES).order(ByteOrder.BIG_ENDIAN)
                                  .putLong(offset)
                                  .array();
    }

    static long decodeOffset(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                              .getLong();
    }
}
