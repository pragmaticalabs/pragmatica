// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    /// Bytes of each record's hash kept in an identity.
    private static final int HASH_BYTES = 16;
    private static final int EPOCH_BYTES = 3 * Long.BYTES;
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

    /// Whether `events` are a contiguous run ending at `offset` (the claim record included).
    static boolean endsAt(List<RawEvent> events, long offset) {
        var first = events.stream().mapToLong(RawEvent::offset).min().orElse(Long.MAX_VALUE);
        var last = events.stream().mapToLong(RawEvent::offset).max().orElse(-1L);

        return last == offset && events.size() == last - first + 1L;
    }

    /// The owner epoch, then for each record (oldest first) its offset and the first [#HASH_BYTES] bytes of the SHA-256 of its bytes. Per
    /// record, so two copies that retain different parts of the window can still be compared over the records both hold ([#agree]).
    static Option<byte[]> lineageOf(List<RawEvent> events, Epoch epoch) {
        return Result.lift(() -> MessageDigest.getInstance("SHA-256"))
                     .map(digest -> identityOf(digest, events, epoch))
                     .option();
    }

    private static byte[] identityOf(MessageDigest digest, List<RawEvent> events, Epoch epoch) {
        var buffer = ByteBuffer.allocate(EPOCH_BYTES + Integer.BYTES + events.size() * (Long.BYTES + HASH_BYTES));

        buffer.putLong(epoch.incarnation())
              .putLong(epoch.rabiaTerm())
              .putLong(epoch.localCounter())
              .putInt(events.size());
        events.forEach(event -> buffer.putLong(event.offset())
                                      .put(digest.digest(event.data()),
                                           0,
                                           HASH_BYTES));

        return buffer.array();
    }

    private record Identity(ByteBuffer epoch, long claim, Map<Long, ByteBuffer> hashes, int trailing) {}

    private static Option<Identity> parse(byte[] identity) {
        return Result.lift(() -> parseChecked(identity)).option();
    }

    private static Identity parseChecked(byte[] identity) {
        var buffer = ByteBuffer.wrap(identity);
        var epoch = ByteBuffer.wrap(identity, 0, EPOCH_BYTES).asReadOnlyBuffer();

        buffer.position(EPOCH_BYTES);
        var count = buffer.getInt();
        var hashes = new LinkedHashMap<Long, ByteBuffer>();
        var claim = -1L;

        for (var i = 0; i < count; i++) {
            claim = buffer.getLong();
            var hash = new byte[HASH_BYTES];

            buffer.get(hash);
            hashes.put(claim, ByteBuffer.wrap(hash));
        }

        return new Identity(epoch, claim, hashes, buffer.remaining());
    }

    /// Whether the identity a checkpoint recorded and the one this copy computes name the same lineage: the same owner epoch and claim, and
    /// every record BOTH hold has the same bytes. The copies may retain different parts of the window (one evicted its start); the claim record
    /// is in both by construction. An identity that does not parse, or an empty one ([org.pragmatica.aether.resource.entity.EntityLogSubstrate#UNVOUCHED]),
    /// agrees with nothing.
    static boolean agree(byte[] recorded, byte[] local) {
        return parse(recorded).flatMap(known -> parse(local).map(mine -> agree(known, mine)))
                    .or(false);
    }

    private static boolean agree(Identity recorded, Identity local) {
        return recorded.epoch()
                       .equals(local.epoch())
               && recorded.claim() == local.claim()
               && local.hashes()
                       .entrySet()
                       .stream()
                       .filter(held -> recorded.hashes()
                                               .containsKey(held.getKey()))
                       .allMatch(held -> recorded.hashes()
                                                 .get(held.getKey())
                                                 .equals(held.getValue()));
    }
}
