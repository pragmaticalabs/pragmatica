// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.storage.BlockEncryptor;
import org.pragmatica.storage.Compression;
import org.pragmatica.storage.EncryptionParams;
import org.pragmatica.storage.StorageInstance;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;


public final class SegmentReader {
    private static final Logger log = LoggerFactory.getLogger(SegmentReader.class);

    private static final int PER_EVENT_HEADER = Long.BYTES + Long.BYTES + Integer.BYTES;

    private final StorageInstance storage;
    private final SegmentIndex index;
    private final Option<BlockEncryptor> encryptor;

    private SegmentReader(StorageInstance storage, SegmentIndex index, Option<BlockEncryptor> encryptor) {
        this.storage = storage;
        this.index = index;
        this.encryptor = encryptor;
    }

    public static SegmentReader segmentReader(StorageInstance storage, SegmentIndex index) {
        return new SegmentReader(storage, index, none());
    }

    public static SegmentReader segmentReader(StorageInstance storage,
                                              SegmentIndex index,
                                              Option<BlockEncryptor> encryptor) {
        return new SegmentReader(storage, index, encryptor);
    }

    public Promise<List<RawEvent>> readEvents(String streamName, int partition, long fromOffset, int maxEvents) {
        var endOffset = fromOffset + maxEvents - 1;
        var refs = index.segmentRange(streamName, partition, fromOffset, endOffset);
        if (refs.isEmpty()) {return Promise.success(List.of());}
        return readFromSegmentRefs(streamName, partition, refs, fromOffset, maxEvents);
    }

    private Promise<List<RawEvent>> readFromSegmentRefs(String streamName,
                                                        int partition,
                                                        List<SegmentIndex.SegmentRef> refs,
                                                        long fromOffset,
                                                        int maxEvents) {
        return readNextSegment(streamName, partition, refs, 0, fromOffset, maxEvents, List.of());
    }

    private Promise<List<RawEvent>> readNextSegment(String streamName,
                                                    int partition,
                                                    List<SegmentIndex.SegmentRef> refs,
                                                    int refIndex,
                                                    long fromOffset,
                                                    int remaining,
                                                    List<RawEvent> accumulated) {
        if (refIndex >= refs.size() || remaining <= 0) {return Promise.success(accumulated);}
        var ref = refs.get(refIndex);
        var refName = buildRefName(streamName, partition, ref);
        return storage.resolveRef(refName).async(SegmentError.General.SEGMENT_REF_NOT_FOUND)
                                 .flatMap(storage::get)
                                 .flatMap(opt -> opt.async(SegmentError.General.SEGMENT_DATA_NOT_FOUND))
                                 .map(bytes -> decryptAndDecompress(bytes, ref))
                                 .map(bytes -> deserializeAndFilter(bytes, fromOffset, remaining))
                                 .flatMap(events -> continueWithImmutableAccumulation(streamName,
                                                                                      partition,
                                                                                      refs,
                                                                                      refIndex,
                                                                                      fromOffset,
                                                                                      remaining,
                                                                                      accumulated,
                                                                                      events));
    }

    private byte[] decryptAndDecompress(byte[] data, SegmentIndex.SegmentRef ref) {
        var decrypted = decryptIfNeeded(data, ref);
        return decompressIfNeeded(decrypted, ref);
    }

    private byte[] decryptIfNeeded(byte[] data, SegmentIndex.SegmentRef ref) {
        if (!ref.encrypted()) {return data;}
        return encryptor.flatMap(enc -> decryptWithEncryptor(enc, data)).or(data);
    }

    private static Option<byte[]> decryptWithEncryptor(BlockEncryptor enc, byte[] data) {
        return extractIvAndDecrypt(enc, data).onFailure(cause -> log.warn("Segment decryption failed, returning raw: {}",
                                                                          cause.message()))
                                  .option();
    }

    private static Result<byte[]> extractIvAndDecrypt(BlockEncryptor enc, byte[] data) {
        if (data.length <Integer.BYTES) {return SegmentError.General.SEGMENT_DATA_NOT_FOUND.result();}
        var ivLength = ((data[0] & 0xFF)<<24) | ((data[1] & 0xFF)<<16) | ((data[2] & 0xFF)<<8) | (data[3] & 0xFF);
        if (data.length <Integer.BYTES + ivLength) {return SegmentError.General.SEGMENT_DATA_NOT_FOUND.result();}
        var iv = new byte[ivLength];
        System.arraycopy(data, Integer.BYTES, iv, 0, ivLength);
        var ciphertext = new byte[data.length - Integer.BYTES - ivLength];
        System.arraycopy(data, Integer.BYTES + ivLength, ciphertext, 0, ciphertext.length);
        var params = EncryptionParams.encryptionParams("AES/GCM/NoPadding", iv, "");
        return enc.decrypt(ciphertext, params);
    }

    private static byte[] decompressIfNeeded(byte[] data, SegmentIndex.SegmentRef ref) {
        if (ref.compressionOrdinal() == 0 || ref.originalSize() == 0) {return data;}
        var compression = compressionFromOrdinal(ref.compressionOrdinal());
        return compression.codec().decompress(data,
                                              ref.originalSize())
                                .onFailure(cause -> log.warn("Segment decompression failed, returning raw: {}",
                                                             cause.message()))
                                .or(data);
    }

    private static Compression compressionFromOrdinal(int ordinal) {
        var values = Compression.values();
        if (ordinal >= 0 && ordinal <values.length) {return values[ordinal];}
        return Compression.NONE;
    }

    private Promise<List<RawEvent>> continueWithImmutableAccumulation(String streamName,
                                                                      int partition,
                                                                      List<SegmentIndex.SegmentRef> refs,
                                                                      int refIndex,
                                                                      long fromOffset,
                                                                      int remaining,
                                                                      List<RawEvent> accumulated,
                                                                      List<RawEvent> events) {
        var combined = List.copyOf(Stream.concat(accumulated.stream(), events.stream()).toList());
        var newRemaining = remaining - events.size();
        return readNextSegment(streamName, partition, refs, refIndex + 1, fromOffset, newRemaining, combined);
    }

    private static String buildRefName(String streamName, int partition, SegmentIndex.SegmentRef ref) {
        return "streams/" + streamName + "/" + partition + "/" + ref.startOffset() + "-" + ref.endOffset();
    }

    static List<RawEvent> deserializeAndFilter(byte[] serialized, long fromOffset, int maxEvents) {
        var buffer = ByteBuffer.wrap(serialized).order(ByteOrder.BIG_ENDIAN);
        var result = new ArrayList<RawEvent>();
        while (buffer.remaining() >= PER_EVENT_HEADER && result.size() <maxEvents) {
            var event = readSingleEvent(buffer);
            if (event.offset() >= fromOffset) {result.add(event);}
        }
        return List.copyOf(result);
    }

    private static RawEvent readSingleEvent(ByteBuffer buffer) {
        var offset = buffer.getLong();
        var timestamp = buffer.getLong();
        var len = buffer.getInt();
        var data = new byte[len];
        buffer.get(data);
        return RawEvent.rawEvent(offset, data, timestamp);
    }
}
