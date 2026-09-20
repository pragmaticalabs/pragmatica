// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.storage.BlockEncryptor;
import org.pragmatica.storage.Compression;
import org.pragmatica.storage.EncryptionParams;
import org.pragmatica.storage.StorageInstance;

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

        if (refs.isEmpty()) {
            return Promise.success(List.of());
        }

        return readFromSegmentRefs(streamName, partition, refs, fromOffset, maxEvents);
    }

    private Promise<List<RawEvent>> readFromSegmentRefs(String streamName,
                                                        int partition,
                                                        List<SegmentIndex.SegmentRef> refs,
                                                        long fromOffset,
                                                        int maxEvents) {
        return readNextSegment(streamName, partition, refs, 0, fromOffset, maxEvents, new ArrayList<>());
    }

    /// #1265: `accumulated` is ONE mutable list threaded through the whole read and flattened once at
    /// the end, instead of an immutable concatenation per segment (n·(k+1) reference copies for n events
    /// across k segments). It is confined to this read: segments are fetched strictly one after another,
    /// each step running only once the previous one completed, and the list escapes only as the final copy.
    private Promise<List<RawEvent>> readNextSegment(String streamName,
                                                    int partition,
                                                    List<SegmentIndex.SegmentRef> refs,
                                                    int refIndex,
                                                    long fromOffset,
                                                    int remaining,
                                                    List<RawEvent> accumulated) {
        if (refIndex >= refs.size() || remaining <= 0) {
            return Promise.success(List.copyOf(accumulated));
        }

        var ref = refs.get(refIndex);
        var refName = buildRefName(streamName, partition, ref);

        return storage.resolveRef(refName)
                      .async(SegmentError.General.SEGMENT_REF_NOT_FOUND)
                      .flatMap(storage::get)
                      .flatMap(opt -> opt.async(SegmentError.General.SEGMENT_DATA_NOT_FOUND))
                      .map(bytes -> decryptAndDecompress(bytes, ref))
                      .flatMap(bytes -> deserializeAndFilter(refName, bytes, fromOffset, remaining).async())
                      .flatMap(events -> continueWithAccumulation(streamName,
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
        if (!ref.encrypted()) {
            return data;
        }

        return encryptor.flatMap(enc -> decryptWithEncryptor(enc, data))
                        .or(data);
    }

    private static Option<byte[]> decryptWithEncryptor(BlockEncryptor enc, byte[] data) {
        return extractIvAndDecrypt(enc, data).onFailure(cause -> log.warn("Segment decryption failed, returning raw: {}",
                                                                          cause.message()))
                                  .option();
    }

    private static Result<byte[]> extractIvAndDecrypt(BlockEncryptor enc, byte[] data) {
        if (data.length < Integer.BYTES) {
            return SegmentError.General.SEGMENT_DATA_NOT_FOUND.result();
        }

        var ivLength = ((data[0] & 0xFF) << 24) | ((data[1] & 0xFF) << 16) | ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);

        if (data.length < Integer.BYTES + ivLength) {
            return SegmentError.General.SEGMENT_DATA_NOT_FOUND.result();
        }

        var iv = new byte[ivLength];

        System.arraycopy(data, Integer.BYTES, iv, 0, ivLength);
        var ciphertext = new byte[data.length - Integer.BYTES - ivLength];

        System.arraycopy(data, Integer.BYTES + ivLength, ciphertext, 0, ciphertext.length);
        var params = EncryptionParams.encryptionParams("AES/GCM/NoPadding", iv, "");

        return enc.decrypt(ciphertext, params);
    }

    private static byte[] decompressIfNeeded(byte[] data, SegmentIndex.SegmentRef ref) {
        if (ref.compressionOrdinal() == 0 || ref.originalSize() == 0) {
            return data;
        }

        var compression = compressionFromOrdinal(ref.compressionOrdinal());

        return compression.codec()
                          .decompress(data,
                                      ref.originalSize())
                          .onFailure(cause -> log.warn("Segment decompression failed, returning raw: {}",
                                                       cause.message()))
                          .or(data);
    }

    private static Compression compressionFromOrdinal(int ordinal) {
        var values = Compression.values();

        if (ordinal >= 0 && ordinal < values.length) {
            return values[ordinal];
        }

        return Compression.NONE;
    }

    private Promise<List<RawEvent>> continueWithAccumulation(String streamName,
                                                             int partition,
                                                             List<SegmentIndex.SegmentRef> refs,
                                                             int refIndex,
                                                             long fromOffset,
                                                             int remaining,
                                                             List<RawEvent> accumulated,
                                                             List<RawEvent> events) {
        accumulated.addAll(events);

        return readNextSegment(streamName,
                               partition,
                               refs,
                               refIndex + 1,
                               fromOffset,
                               remaining - events.size(),
                               accumulated);
    }

    private static String buildRefName(String streamName, int partition, SegmentIndex.SegmentRef ref) {
        return "streams/" + streamName + "/" + partition + "/" + ref.startOffset() + "-" + ref.endOffset();
    }

    /// Decode `[offset:8][timestamp:8][len:4][data:len]` records, keeping at most `maxEvents` whose offset
    /// is at or past `fromOffset`.
    ///
    /// #1265: the header is parsed first and a record below `fromOffset` is skipped by moving the buffer
    /// position — no payload is allocated or copied for it. Before, every skipped payload was allocated
    /// and copied, so a sequential consumer reading a segment m events at a time allocated the segment
    /// about S/(2m) times over.
    ///
    /// A `len` that is negative or exceeds the remaining bytes FAILS the decode with
    /// [SegmentError.CorruptRecord], before any allocation. Stopping and keeping the records already decoded
    /// would be a truncated success: the read would carry on into the next segment and the offsets between
    /// the corrupt record and that segment would vanish without anything failing. For the same reason,
    /// bytes left over when fewer than `maxEvents` were kept — too few for a record header, since a
    /// well-formed segment ends on a record boundary — fail it as a truncated header.
    static Result<List<RawEvent>> deserializeAndFilter(String segment,
                                                       byte[] serialized,
                                                       long fromOffset,
                                                       int maxEvents) {
        var buffer = ByteBuffer.wrap(serialized).order(ByteOrder.BIG_ENDIAN);
        var result = new ArrayList<RawEvent>();

        while (buffer.remaining() >= PER_EVENT_HEADER && result.size() < maxEvents) {
            var position = buffer.position();
            var offset = buffer.getLong();
            var timestamp = buffer.getLong();
            var len = buffer.getInt();

            if (len < 0 || len > buffer.remaining()) {
                return corruptRecord(segment, position, offset, len, buffer.remaining());
            }

            if (offset < fromOffset) {
                buffer.position(buffer.position() + len);
                continue;
            }

            result.add(readPayload(buffer, offset, timestamp, len));
        }

        return result.size() < maxEvents && buffer.hasRemaining()
               ? truncatedHeader(segment, buffer.position(), buffer.remaining())
               : Result.success(List.copyOf(result));
    }

    private static Result<List<RawEvent>> truncatedHeader(String segment, int position, int remaining) {
        log.error("Segment {} ends in a truncated record header at byte position {}: {} of {} header bytes present;"
                 + " failing the read",
                  segment,
                  position,
                  remaining,
                  PER_EVENT_HEADER);

        return SegmentError.CorruptRecord.TRUNCATED_HEADER.apply(segment, position, remaining).result();
    }

    private static Result<List<RawEvent>> corruptRecord(String segment,
                                                        int position,
                                                        long offset,
                                                        int len,
                                                        int remaining) {
        log.error("Segment {} has a corrupt record at byte position {} (offset field {}): declared payload length {}"
                 + " with {} bytes remaining; failing the read",
                  segment,
                  position,
                  offset,
                  len,
                  remaining);

        return SegmentError.CorruptRecord.FACTORY.apply(segment, position, len).result();
    }

    private static RawEvent readPayload(ByteBuffer buffer, long offset, long timestamp, int len) {
        var data = new byte[len];

        buffer.get(data);

        return RawEvent.rawEvent(offset, data, timestamp);
    }
}
