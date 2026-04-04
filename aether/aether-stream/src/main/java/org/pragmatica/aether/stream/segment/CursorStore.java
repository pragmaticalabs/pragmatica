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

    /// Persist a consumer cursor. Stores the offset as a data block and creates/updates
    /// the named reference pointing to it.
    public Promise<Unit> commit(String consumerGroup, String streamName, int partition, long offset) {
        var refName = buildRefName(consumerGroup, streamName, partition);
        var payload = encodeOffset(offset);
        return storage.put(payload)
                      .flatMap(blockId -> replaceRef(refName, blockId))
                      .onSuccess(_ -> logCommit(consumerGroup, streamName, partition, offset));
    }

    /// Retrieve the last committed offset for a consumer group on a specific partition.
    public Option<Long> fetch(String consumerGroup, String streamName, int partition) {
        var refName = buildRefName(consumerGroup, streamName, partition);
        return storage.resolveRef(refName)
                      .flatMap(this::readOffset);
    }

    private Promise<Unit> replaceRef(String refName, BlockId blockId) {
        return storage.deleteRef(refName)
                      .flatMap(_ -> storage.createRef(refName, blockId));
    }

    private Option<Long> readOffset(BlockId blockId) {
        return storage.get(blockId).await()
                      .option()
                      .flatMap(opt -> opt)
                      .filter(bytes -> bytes.length == Long.BYTES)
                      .map(CursorStore::decodeOffset);
    }

    private static void logCommit(String consumerGroup, String streamName, int partition, long offset) {
        log.debug("Cursor committed: {}/{}/{} -> {}", consumerGroup, streamName, partition, offset);
    }

    static String buildRefName(String consumerGroup, String streamName, int partition) {
        return CURSORS_PREFIX + consumerGroup + "/" + streamName + "/" + partition;
    }

    static byte[] encodeOffset(long offset) {
        return ByteBuffer.allocate(Long.BYTES).order(ByteOrder.BIG_ENDIAN).putLong(offset).array();
    }

    static long decodeOffset(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getLong();
    }
}
