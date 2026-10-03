// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.AppendLog;
import org.pragmatica.storage.AppendLog.EpochStart;
import org.pragmatica.storage.BlockEncryptor;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.CompressionCodec;
import org.pragmatica.storage.EncryptionParams;
import org.pragmatica.storage.SnapshotManager;
import org.pragmatica.storage.StorageInstance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Unit.unit;


public final class StorageSegmentSink implements SegmentSink {
    private static final Logger log = LoggerFactory.getLogger(StorageSegmentSink.class);

    private final StorageInstance storage;
    private final SegmentIndex index;
    private final CompressionCodec compressionCodec;
    private final int compressionOrdinal;
    private final Option<BlockEncryptor> encryptor;
    private final RefDurability refDurability;

    private StorageSegmentSink(StorageInstance storage,
                               SegmentIndex index,
                               CompressionCodec compressionCodec,
                               int compressionOrdinal,
                               Option<BlockEncryptor> encryptor,
                               RefDurability refDurability) {
        this.storage = storage;
        this.index = index;
        this.compressionCodec = compressionCodec;
        this.compressionOrdinal = compressionOrdinal;
        this.encryptor = encryptor;
        this.refDurability = refDurability;
    }

    public static StorageSegmentSink storageSegmentSink(StorageInstance storage, SegmentIndex index) {
        return storageSegmentSink(storage, index, RefDurability.LIVE);
    }

    /// The production wiring (#1441): the storage instance's metadata reaches disk only through snapshots, so a
    /// seal without a log forces one through `refDurability` before it resolves.
    public static StorageSegmentSink storageSegmentSink(StorageInstance storage,
                                                        SegmentIndex index,
                                                        RefDurability refDurability) {
        return new StorageSegmentSink(storage, index, CompressionCodec.NONE, 0, none(), refDurability);
    }

    public static StorageSegmentSink storageSegmentSink(StorageInstance storage,
                                                        SegmentIndex index,
                                                        CompressionCodec compressionCodec,
                                                        int compressionOrdinal,
                                                        Option<BlockEncryptor> encryptor) {
        return new StorageSegmentSink(storage,
                                      index,
                                      compressionCodec,
                                      compressionOrdinal,
                                      encryptor,
                                      RefDurability.LIVE);
    }

    /// One write-and-ref call, never [StorageInstance#put] followed by
    /// [StorageInstance#createRef]: the pair credits the sealed block twice for the one segment ref,
    /// so it could never reach refCount 0 and the garbage collector could never collect a segment
    /// whose ref had been dropped (#812).
    ///
    /// The index is updated as a DEPENDENT step, before the returned promise succeeds (#1234): the sealer
    /// drops its retained copy on that success, so the index must already hold the segment — an
    /// independent `onSuccess` runs asynchronously and could land after the copy was gone, leaving a window
    /// in which a read found the offsets in neither place.
    ///
    /// With the partition's log, the block goes through [StorageInstance#seal] (#1567): durable on every
    /// durable tier, then the ref, then the log's seal bound -- so the log can never be truncated past a
    /// segment whose block is not on disk. Without one (a manager built with no WAL) there is nothing to
    /// truncate and [StorageInstance#putRef] stores it, durably on the same tiers, and then the ref is made
    /// durable through [RefDurability] (#1441): a restart rebuilds the sealed floor from the refs on disk, and
    /// with no log above that floor, a ref lost to a crash would let recovery re-assign the sealed offsets.
    ///
    /// With a log whose owner-epoch history is not empty, the block carries the slice of it covering
    /// `[base, endOffset]` in front of the events ([SegmentProvenance], #1596), read here at seal time: a node that
    /// later replays the segment from the tier installs it before appending the segment's records.
    @Override
    public Promise<Unit> seal(SealedSegment segment, Option<AppendLog> log) {
        var raw = withProvenance(segment, log);
        var originalSize = raw.length;
        var compressed = compressionCodec.compress(raw).or(raw);
        var processedData = applyEncryption(compressed);
        var incarnation = index.incarnationOf(segment.streamName());

        return store(segment,
                     incarnation,
                     log,
                     processedData.data()).map(_ -> updateIndex(segment,
                                                                incarnation,
                                                                originalSize,
                                                                processedData.encrypted()))
                    .onSuccess(_ -> logSealed(segment));
    }

    private static byte[] withProvenance(SealedSegment segment, Option<AppendLog> log) {
        var events = segment.serializedEvents();

        return log.map(wal -> sliceThrough(wal,
                                           segment.endOffset()))
                  .filter(slice -> !slice.isEmpty())
                  .map(slice -> SegmentProvenance.withSlice(slice, events))
                  .or(events);
    }

    private static List<EpochStart> sliceThrough(AppendLog wal, long endOffset) {
        return wal.epochHistory()
                  .stream()
                  .filter(start -> start.startOffset() <= endOffset)
                  .toList();
    }

    /// Stored under the life of the stream (`incarnation`) the seal started in (#1278 review): a seal still in flight
    /// when its stream is destroyed or recreated lands under the OLD life's name, which nothing reads again.
    private Promise<BlockId> store(SealedSegment segment, long incarnation, Option<AppendLog> log, byte[] block) {
        var refName = refName(segment, incarnation);

        return log.fold(() -> storeWithoutLog(refName, block),
                        wal -> storage.seal(wal, segment.startOffset(), segment.endOffset(), refName, block));
    }

    /// A failed [RefDurability#persist] fails the seal, so the segment is not indexed and the sealer retries it;
    /// re-storing the same block under the same name is a no-op on its count (see [StorageInstance#putRef]).
    private Promise<BlockId> storeWithoutLog(String refName, byte[] block) {
        return storage.putRef(refName, block)
                      .flatMap(id -> refDurability.persist()
                                                  .map(_ -> id)
                                                  .async());
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

    private Unit updateIndex(SealedSegment segment, long incarnation, int originalSize, boolean encrypted) {
        index.addSegment(segment.streamName(),
                         incarnation,
                         segment.partition(),
                         segment.startOffset(),
                         segment.endOffset(),
                         segment.maxTimestamp(),
                         compressionOrdinal,
                         encrypted,
                         originalSize);

        return unit();
    }

    private void logSealed(SealedSegment segment) {
        log.debug("Sealed segment {} partition={} offsets=[{}-{}] events={}",
                  segment.streamName(),
                  segment.partition(),
                  segment.startOffset(),
                  segment.endOffset(),
                  segment.eventCount());
    }

    /// The ref of `segment` in incarnation `0` (a stream with no cluster-minted life).
    static String refName(SealedSegment segment) {
        return refName(segment, 0L);
    }

    static String refName(SealedSegment segment, long incarnation) {
        return SegmentIndex.buildRefName(SegmentIndex.durableName(segment.streamName(), incarnation),
                                         segment.partition(),
                                         SegmentIndex.SegmentRef.segmentRef(segment.startOffset(), segment.endOffset()));
    }

    private record ProcessedData(byte[] data, boolean encrypted) {
        static ProcessedData unencrypted(byte[] data) {
            return new ProcessedData(data, false);
        }
    }
}
