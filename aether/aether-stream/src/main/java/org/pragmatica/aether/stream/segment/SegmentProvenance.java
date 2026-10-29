// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.storage.AppendLog.EpochKey;
import org.pragmatica.storage.AppendLog.EpochStart;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.utils.Causes.cause;


/// The owner-epoch slice a sealed segment carries (#1596): the sealing log's history entries covering
/// `[base, endOffset]`, read at seal time, so a node that later takes the segment's records from the tier takes
/// their provenance with them.
///
/// It is a header in front of the serialized events, inside the block (so it is compressed, encrypted and
/// content-addressed with them):
///
/// ```
/// [ MAGIC:8 ][ count:4 ][ count × ( tokenLength:2 tokenUtf8 startOffset:8 ) ][ events... ]
/// ```
///
/// `MAGIC` is negative. The serialized events start with a record offset, which is never negative, so a block
/// sealed before this header existed -- or sealed without a log -- is told apart without a version field and reads
/// as carrying no slice.
///
/// Since #2258 (spec 3.2 item 5) every new segment block starts with the IDENTITY header instead:
///
/// ```
/// [ MAGIC2:8 ][ nameLength:2 durableNameUtf8 ][ partition:4 ][ startOffset:8 ][ endOffset:8 ]
/// [ count:4 ][ count × ( tokenLength:2 tokenUtf8 startOffset:8 ) ][ events... ]
/// ```
///
/// The durable name carries the stream's incarnation, so two refs name one content-addressed block only when they name
/// the same range of the same partition of the same life with the same bytes: the same segment, held by two nodes. That
/// is what lets a node delete a released segment's block from the shared tier without a cluster-wide reference count.
/// `count` is `-1` for a segment sealed without a slice (no log, or an empty one), so the slice reads exactly as before.
/// `MAGIC2` is negative too; the three forms are told apart by their first eight bytes.
public sealed interface SegmentProvenance {
    long MAGIC = 0xAE7E_1596_5E61_0001L;
    long MAGIC2 = 0xAE7E_1596_5E61_0002L;
    int NO_SLICE = -1;

    /// A block's events, the slice in front of them, if any, and whether the block carries the identity header.
    record Split(Option<List<EpochStart>> slice, byte[] events, boolean identified) {
        Split(Option<List<EpochStart>> slice, byte[] events) {
            this(slice, events, false);
        }
    }

    /// Whether `block`, as stored, starts with the identity header: the predicate the `streams` storage instance gives its
    /// collector for an orphan released without knowing (#2258). A block that does not -- written before the header
    /// existed, or encrypted, so its stored bytes are ciphertext -- is never deleted from the shared tier.
    static boolean isIdentified(byte[] block) {
        return block.length >= Long.BYTES && ByteBuffer.wrap(block)
                                                       .order(ByteOrder.BIG_ENDIAN)
                                                       .getLong() == MAGIC2;
    }

    /// `events` behind the identity header of segment `[startOffset, endOffset]` of `partition` of the life `durableName`,
    /// carrying `slice` if there is one.
    static byte[] withIdentity(String durableName,
                               int partition,
                               long startOffset,
                               long endOffset,
                               Option<List<EpochStart>> slice,
                               byte[] events) {
        var name = durableName.getBytes(StandardCharsets.UTF_8);
        var entries = slice.or(List.of());
        var tokens = entries.stream().map(start -> start.key()
                                                        .token()
                                                        .getBytes(StandardCharsets.UTF_8)).toList();
        var headerSize = Long.BYTES + Short.BYTES + name.length + Integer.BYTES + 2 * Long.BYTES + Integer.BYTES + tokens.stream()
                                                                                                                         .mapToInt(token -> Short.BYTES + token.length + Long.BYTES)
                                                                                                                         .sum();
        var buffer = ByteBuffer.allocate(headerSize + events.length).order(ByteOrder.BIG_ENDIAN);

        buffer.putLong(MAGIC2)
              .putShort((short) name.length)
              .put(name)
              .putInt(partition)
              .putLong(startOffset)
              .putLong(endOffset)
              .putInt(slice.isPresent()
                      ? entries.size()
                      : NO_SLICE);
        for (var i = 0; i < entries.size(); i++) {
            buffer.putShort((short) tokens.get(i).length).put(tokens.get(i)).putLong(entries.get(i).startOffset());
        }

        return buffer.put(events)
                     .array();
    }

    /// `events` behind a header holding `slice`.
    static byte[] withSlice(List<EpochStart> slice, byte[] events) {
        var tokens = slice.stream().map(start -> start.key()
                                                      .token()
                                                      .getBytes(StandardCharsets.UTF_8)).toList();
        var headerSize = Long.BYTES + Integer.BYTES + tokens.stream()
                                                            .mapToInt(token -> Short.BYTES + token.length + Long.BYTES)
                                                            .sum();
        var buffer = ByteBuffer.allocate(headerSize + events.length).order(ByteOrder.BIG_ENDIAN);

        buffer.putLong(MAGIC).putInt(slice.size());
        for (var i = 0; i < slice.size(); i++) {
            buffer.putShort((short) tokens.get(i).length).put(tokens.get(i)).putLong(slice.get(i).startOffset());
        }

        return buffer.put(events)
                     .array();
    }

    /// A block split into its slice and its events. Three forms: no header (all events, no slice), the provenance header
    /// (`MAGIC`) and the identity header (`MAGIC2`); a header that does not parse is a corrupt block.
    static Result<Split> split(byte[] block) {
        if (block.length < Long.BYTES) {
            return Result.success(new Split(none(), block));
        }

        var magic = ByteBuffer.wrap(block).order(ByteOrder.BIG_ENDIAN).getLong();

        if (magic == MAGIC2) {
            return parseIdentified(block);
        }

        return magic == MAGIC
               ? parse(block)
               : Result.success(new Split(none(), block));
    }

    private static Result<Split> parseIdentified(byte[] block) {
        var buffer = ByteBuffer.wrap(block).order(ByteOrder.BIG_ENDIAN).position(Long.BYTES);

        return Result.lift(_ -> cause("Segment identity header is corrupt"),
                           () -> skipIdentity(buffer))
                     .flatMap(count -> count == NO_SLICE
                                       ? Result.success(Option.<List<EpochStart>> none())
                                       : Result.lift(_ -> cause("Segment identity header is corrupt"),
                                                     () -> readEntries(buffer, count))
                                               .flatMap(entries -> Result.allOf(entries))
                                               .map(Option::some))
                     .map(slice -> new Split(slice,
                                             remainder(block,
                                                       buffer.position()),
                                             true));
    }

    /// Skips the durable name, partition and range, and answers the slice count that follows them.
    private static int skipIdentity(ByteBuffer buffer) {
        var nameLength = Short.toUnsignedInt(buffer.getShort());

        buffer.position(buffer.position() + nameLength + Integer.BYTES + 2 * Long.BYTES);

        return buffer.getInt();
    }

    private static Result<Split> parse(byte[] block) {
        var buffer = ByteBuffer.wrap(block).order(ByteOrder.BIG_ENDIAN).position(Long.BYTES);

        return Result.lift(_ -> cause("Segment provenance header is corrupt"),
                           () -> readEntries(buffer,
                                             buffer.getInt()))
                     .flatMap(entries -> Result.allOf(entries))
                     .map(entries -> new Split(some(entries),
                                               remainder(block,
                                                         buffer.position())));
    }

    private static List<Result<EpochStart>> readEntries(ByteBuffer buffer, int count) {
        var entries = new ArrayList<Result<EpochStart>>(count);

        for (var i = 0; i < count; i++) {
            var token = new byte[buffer.getShort()];

            buffer.get(token);
            var start = buffer.getLong();

            entries.add(EpochKey.epochKey(new String(token, StandardCharsets.UTF_8)).map(key -> new EpochStart(key,
                                                                                                               start)));
        }

        return entries;
    }

    private static byte[] remainder(byte[] block, int from) {
        var events = new byte[block.length - from];

        System.arraycopy(block, from, events, 0, events.length);

        return events;
    }

    record unused() implements SegmentProvenance {}
}
