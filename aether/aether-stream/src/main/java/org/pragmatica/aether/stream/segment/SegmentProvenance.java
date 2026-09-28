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
public sealed interface SegmentProvenance {
    long MAGIC = 0xAE7E_1596_5E61_0001L;

    /// A block's events and the slice in front of them, if any.
    record Split(Option<List<EpochStart>> slice, byte[] events) {}

    /// `events` behind a header holding `slice`.
    static byte[] withSlice(List<EpochStart> slice, byte[] events) {
        var tokens = slice.stream()
                          .map(start -> start.key()
                                             .token()
                                             .getBytes(StandardCharsets.UTF_8))
                          .toList();
        var headerSize = Long.BYTES + Integer.BYTES + tokens.stream()
                                                            .mapToInt(token -> Short.BYTES + token.length + Long.BYTES)
                                                            .sum();
        var buffer = ByteBuffer.allocate(headerSize + events.length)
                               .order(ByteOrder.BIG_ENDIAN);

        buffer.putLong(MAGIC)
              .putInt(slice.size());

        for (var i = 0; i < slice.size(); i++) {
            buffer.putShort((short) tokens.get(i).length)
                  .put(tokens.get(i))
                  .putLong(slice.get(i)
                                .startOffset());
        }

        return buffer.put(events)
                     .array();
    }

    /// A block split into its slice and its events. A block without the header is all events and no slice; a
    /// header that does not parse is a corrupt block.
    static Result<Split> split(byte[] block) {
        return block.length < Long.BYTES || ByteBuffer.wrap(block)
                                                      .order(ByteOrder.BIG_ENDIAN)
                                                      .getLong() != MAGIC
               ? Result.success(new Split(none(), block))
               : parse(block);
    }

    private static Result<Split> parse(byte[] block) {
        var buffer = ByteBuffer.wrap(block)
                               .order(ByteOrder.BIG_ENDIAN)
                               .position(Long.BYTES);

        return Result.lift(_ -> cause("Segment provenance header is corrupt"), () -> readEntries(buffer))
                     .flatMap(entries -> Result.allOf(entries))
                     .map(entries -> new Split(some(entries), remainder(block, buffer.position())));
    }

    private static List<Result<EpochStart>> readEntries(ByteBuffer buffer) {
        var count = buffer.getInt();
        var entries = new ArrayList<Result<EpochStart>>(count);

        for (var i = 0; i < count; i++) {
            var token = new byte[buffer.getShort()];

            buffer.get(token);
            var start = buffer.getLong();

            entries.add(EpochKey.epochKey(new String(token, StandardCharsets.UTF_8))
                                .map(key -> new EpochStart(key, start)));
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
