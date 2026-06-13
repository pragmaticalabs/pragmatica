// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.BlockEncryptor;
import org.pragmatica.storage.CompressionCodec;
import org.pragmatica.storage.EncryptionParams;
import org.pragmatica.storage.StorageInstance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;


public final class StorageSegmentSink implements SegmentSink {
    private static final Logger log = LoggerFactory.getLogger(StorageSegmentSink.class);

    private final StorageInstance storage;
    private final SegmentIndex index;
    private final CompressionCodec compressionCodec;
    private final int compressionOrdinal;
    private final Option<BlockEncryptor> encryptor;

    private StorageSegmentSink(StorageInstance storage,
                               SegmentIndex index,
                               CompressionCodec compressionCodec,
                               int compressionOrdinal,
                               Option<BlockEncryptor> encryptor) {
        this.storage = storage;
        this.index = index;
        this.compressionCodec = compressionCodec;
        this.compressionOrdinal = compressionOrdinal;
        this.encryptor = encryptor;
    }

    public static StorageSegmentSink storageSegmentSink(StorageInstance storage, SegmentIndex index) {
        return new StorageSegmentSink(storage, index, CompressionCodec.NONE, 0, none());
    }

    public static StorageSegmentSink storageSegmentSink(StorageInstance storage,
                                                        SegmentIndex index,
                                                        CompressionCodec compressionCodec,
                                                        int compressionOrdinal,
                                                        Option<BlockEncryptor> encryptor) {
        return new StorageSegmentSink(storage, index, compressionCodec, compressionOrdinal, encryptor);
    }

    @Override
    public Promise<Unit> seal(SealedSegment segment) {
        var raw = segment.serializedEvents();
        var originalSize = raw.length;
        var compressed = compressionCodec.compress(raw).or(raw);
        var processedData = applyEncryption(compressed);

        return storage.put(processedData.data())
                      .flatMap(blockId -> storage.createRef(refName(segment),
                                                            blockId))
                      .onSuccess(_ -> updateIndex(segment,
                                                  originalSize,
                                                  processedData.encrypted()))
                      .onSuccess(_ -> logSealed(segment));
    }

    private ProcessedData applyEncryption(byte[] data) {
        return encryptor.map(enc -> encryptData(enc, data))
                        .or(ProcessedData.unencrypted(data));
    }

    private static ProcessedData encryptData(BlockEncryptor enc, byte[] data) {
        return enc.encrypt(data)
                  .map(encrypted -> new ProcessedData(prependIv(encrypted),
                                                      true))
                  .or(ProcessedData.unencrypted(data));
    }

    private static byte[] prependIv(BlockEncryptor.EncryptedData encrypted) {
        var iv = encrypted.params().iv();
        var ciphertext = encrypted.ciphertext();
        var result = new byte[Integer.BYTES + iv.length + ciphertext.length];

        result[0] = (byte)(iv.length >> 24);
        result[1] = (byte)(iv.length >> 16);
        result[2] = (byte)(iv.length >> 8);
        result[3] = (byte) iv.length;
        System.arraycopy(iv, 0, result, Integer.BYTES, iv.length);
        System.arraycopy(ciphertext, 0, result, Integer.BYTES + iv.length, ciphertext.length);

        return result;
    }

    private void updateIndex(SealedSegment segment, int originalSize, boolean encrypted) {
        index.addSegment(segment.streamName(),
                         segment.partition(),
                         segment.startOffset(),
                         segment.endOffset(),
                         segment.maxTimestamp(),
                         compressionOrdinal,
                         encrypted,
                         originalSize);
    }

    private void logSealed(SealedSegment segment) {
        log.debug("Sealed segment {} partition={} offsets=[{}-{}] events={}",
                  segment.streamName(),
                  segment.partition(),
                  segment.startOffset(),
                  segment.endOffset(),
                  segment.eventCount());
    }

    static String refName(SealedSegment segment) {
        return "streams/" + segment.streamName()
             + "/" + segment.partition()
             + "/" + segment.startOffset()
             + "-" + segment.endOffset();
    }

    private record ProcessedData(byte[] data, boolean encrypted) {
        static ProcessedData unencrypted(byte[] data) {
            return new ProcessedData(data, false);
        }
    }
}
