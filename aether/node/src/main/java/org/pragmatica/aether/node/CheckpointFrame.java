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
///
/// Since PR-3c every block written opens with an identity header ([#IDENTITY_MARKER], the keyspace, the partition and the claim) ahead of
/// that layout, so no two checkpoint pointers can name one block: content addressing gives byte-identical folds one id, and a writer that
/// deletes the block its pointer swap displaced would otherwise delete another pointer's block with it. A block without the header (written
/// before PR-3c) is read as before.
final class CheckpointFrame {
    /// Records the lineage digest covers: up to this many, ending at the checkpoint's claim.
    static final int LINEAGE_WINDOW = 8;
    /// Bytes of each record's hash kept in an identity.
    private static final int HASH_BYTES = 16;
    private static final int EPOCH_BYTES = 3 * Long.BYTES;
    private static final byte[] MARKER = "LNGCKPT1".getBytes(StandardCharsets.US_ASCII);
    /// Opens the identity header. Its first byte, `'C'`, is neither snapshot version (1, 2) nor the first byte of [#MARKER], so a build
    /// that predates the header reads the block as a fold with no lineage that does not decode: "no usable checkpoint", and the partition
    /// is rebuilt from its log (the block-layout flag day of PR-3c).
    private static final byte[] IDENTITY_MARKER = "CKPTID01".getBytes(StandardCharsets.US_ASCII);

    private CheckpointFrame() {}

    static byte[] frame(String keyspace, int partition, long throughOffset, byte[] snapshot, Option<byte[]> lineage) {
        var name = keyspace.getBytes(StandardCharsets.UTF_8);
        var body = withLineage(snapshot, lineage);

        return ByteBuffer.allocate(IDENTITY_MARKER.length + Integer.BYTES + name.length + Integer.BYTES + Long.BYTES + body.length)
                         .put(IDENTITY_MARKER)
                         .putInt(name.length)
                         .put(name)
                         .putInt(partition)
                         .putLong(throughOffset)
                         .put(body)
                         .array();
    }

    private static byte[] withLineage(byte[] snapshot, Option<byte[]> lineage) {
        return lineage.map(identity -> ByteBuffer.allocate(MARKER.length + Integer.BYTES + identity.length + snapshot.length)
                                                 .put(MARKER)
                                                 .putInt(identity.length)
                                                 .put(identity)
                                                 .put(snapshot)
                                                 .array())
                      .or(snapshot);
    }

    static EntityCheckpoint unframe(long throughOffset, byte[] block) {
        return unframeLineage(throughOffset, withoutIdentity(block));
    }

    /// `block` without its identity header; a block that has none (written before PR-3c), or whose header does not parse, whole.
    private static byte[] withoutIdentity(byte[] block) {
        if (block.length < IDENTITY_MARKER.length + Integer.BYTES || !Arrays.equals(block,
                                                                                    0,
                                                                                    IDENTITY_MARKER.length,
                                                                                    IDENTITY_MARKER,
                                                                                    0,
                                                                                    IDENTITY_MARKER.length)) {
            return block;
        }

        var nameLength = ByteBuffer.wrap(block, IDENTITY_MARKER.length, Integer.BYTES).getInt();
        var bodyStart = (long) IDENTITY_MARKER.length + Integer.BYTES + nameLength + Integer.BYTES + Long.BYTES;

        return nameLength < 0 || bodyStart > block.length
               ? block
               : Arrays.copyOfRange(block, (int) bodyStart, block.length);
    }

    private static EntityCheckpoint unframeLineage(long throughOffset, byte[] block) {
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

    /// An identity led by the sealed floor the writer saw when it took the checkpoint, then `body` (empty when the claim was at or below that
    /// floor and nothing needs comparing). The floor is recorded because a claim at or below the floor AT WRITE TIME can never be cut later (the
    /// floor only rises, and a cut is refused at or below it); a claim the floor merely reaches afterwards may already have been cut and rewritten.
    static byte[] withFloor(long floor, byte[] body) {
        return ByteBuffer.allocate(Long.BYTES + body.length)
                         .putLong(floor)
                         .put(body)
                         .array();
    }

    /// The sealed floor recorded in `identity`, none when it records none (an unvouched or older identity).
    static Option<Long> floorOf(byte[] identity) {
        return identity.length < Long.BYTES
               ? Option.none()
               : Option.some(ByteBuffer.wrap(identity).getLong());
    }

    /// `identity` without its leading floor.
    static byte[] bodyOf(byte[] identity) {
        return identity.length < Long.BYTES
               ? identity
               : Arrays.copyOfRange(identity, Long.BYTES, identity.length);
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
