// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;

import org.pragmatica.aether.resource.entity.EntityLogSubstrate.EntityCheckpoint;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


/// How an entity checkpoint block carries the lineage identity of the record it was taken at (#2087 B4-cut). A block is the encoded fold
/// alone, or -- when a lineage is recorded -- an 8-byte marker, the lineage's length, the lineage, and then the encoded fold. A block without
/// the marker is a fold with no lineage recorded and is read as before.
final class CheckpointFrame {
    /// Records the lineage digest covers: up to this many, ending at the checkpoint's claim.
    static final int LINEAGE_WINDOW = 8;
    private static final byte[] MARKER = "LNGCKPT1".getBytes(StandardCharsets.US_ASCII);

    private CheckpointFrame() {}

    static byte[] frame(byte[] snapshot, Option<byte[]> lineage) {
        return lineage.map(identity -> ByteBuffer.allocate(MARKER.length + Integer.BYTES + identity.length + snapshot.length)
                                                 .put(MARKER)
                                                 .putInt(identity.length)
                                                 .put(identity)
                                                 .put(snapshot)
                                                 .array())
                      .or(snapshot);
    }

    static EntityCheckpoint unframe(long throughOffset, byte[] block) {
        if (block.length < MARKER.length + Integer.BYTES || !Arrays.equals(block,
                                                                           0,
                                                                           MARKER.length,
                                                                           MARKER,
                                                                           0,
                                                                           MARKER.length)) {
            return EntityCheckpoint.entityCheckpoint(throughOffset, block);
        }

        var buffer = ByteBuffer.wrap(block, MARKER.length, block.length - MARKER.length);
        var length = buffer.getInt();

        if (length < 0 || length > buffer.remaining()) {
            return EntityCheckpoint.entityCheckpoint(throughOffset, block);
        }

        var identity = new byte[length];

        buffer.get(identity);
        var snapshot = new byte[buffer.remaining()];

        buffer.get(snapshot);

        return EntityCheckpoint.entityCheckpoint(throughOffset, snapshot, Option.some(identity));
    }

    /// Whether `events` are exactly the window `[from, offset]`: the right count, ending at `offset`.
    static boolean coversWindow(List<RawEvent> events, long from, long offset) {
        return events.size() == offset - from + 1L && events.stream()
                                                            .mapToLong(RawEvent::offset)
                                                            .max()
                                                            .orElse(-1L) == offset;
    }

    /// The owner epoch and the SHA-256 of the records' offsets and bytes, oldest first.
    static Option<byte[]> lineageOf(List<RawEvent> events, Epoch epoch) {
        return Result.lift(() -> MessageDigest.getInstance("SHA-256"))
                     .map(digest -> digestOf(digest, events, epoch))
                     .option();
    }

    private static byte[] digestOf(MessageDigest digest, List<RawEvent> events, Epoch epoch) {
        events.forEach(event -> {
            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(event.offset()).array());
            digest.update(event.data());
        });
        var identity = digest.digest();

        return ByteBuffer.allocate(3 * Long.BYTES + identity.length)
                         .putLong(epoch.incarnation())
                         .putLong(epoch.rabiaTerm())
                         .putLong(epoch.localCounter())
                         .put(identity)
                         .array();
    }
}
