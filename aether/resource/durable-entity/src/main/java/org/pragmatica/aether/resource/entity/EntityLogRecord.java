// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


/// One mutation in an entity keyspace's fenced log (#345 I3). The entity's state for a key is the FOLD
/// of these records in offset order, so this is the only thing that is actually durable — the in-memory
/// map is a derived view that any node can rebuild by replaying.
///
/// ## Why a hand-rolled framing and not the slice codec
/// The slice [org.pragmatica.serialization.Serializer] encodes the application's STATE type (that is the
/// `state` field below, already encoded by the caller). Framing the envelope with it too would mean the
/// entity module minting a codec tag of its own, which collides with #582's tag-space problem, and would
/// put a runtime type registration in the path of every entity write. The envelope is three scalars and
/// a length-prefixed string; a fixed layout is smaller, allocation-free to parse, and owned entirely
/// here.
///
/// ## Layout
/// ```
/// [0]     format version, currently VERSION
/// [1]     operation ordinal — see Op
/// [2..5]  key length in bytes, big-endian
/// [6..]   key, UTF-8
/// [..]    encoded state; EMPTY for DELETE
/// ```
/// The version byte is the evolution seam: a reader that meets an unknown version FAILS rather than
/// guessing, because a misparsed record silently corrupts folded state rather than announcing itself.
///
/// @param op    what the record does to the key
/// @param key   the entity key, as rendered by the entity's `String.valueOf(K)`
/// @param state the encoded entity state after the operation; zero-length for [Op#DELETE]
public record EntityLogRecord(EntityLogRecord.Op op, String key, byte[] state) {
    /// The framing version this build writes. Bump ONLY alongside a reader that still accepts every
    /// older version — an entity log outlives the node that wrote it, so a version this build cannot
    /// read is unrecoverable state, not a compatibility inconvenience.
    public static final byte VERSION = 1;
    private static final int HEADER_BYTES = 6;
    private static final byte[] EMPTY_STATE = new byte[0];

    /// What a record does to its key when folded. Deliberately explicit rather than encoding a delete as
    /// a null/empty state: a legitimately empty encoded state and a tombstone must not be the same bytes.
    public enum Op {
        UPSERT,
        DELETE;
        static Result<Op> fromOrdinal(byte ordinal) {
            var values = values();

            return ordinal >= 0 && ordinal < values.length
                   ? success(values[ordinal])
                   : new EntityLogError.MalformedRecord("unknown operation ordinal " + ordinal).result();
        }
    }

    /// A create or update: the key now holds `state`.
    public static EntityLogRecord upsert(String key, byte[] state) {
        return new EntityLogRecord(Op.UPSERT, key, state);
    }

    /// A tombstone: the key is absent from the fold from this offset on. Kept as a record rather than an
    /// absence because the fold is replayed forward — a delete that wrote nothing would leave the key's
    /// prior value standing on every replay.
    public static EntityLogRecord delete(String key) {
        return new EntityLogRecord(Op.DELETE, key, EMPTY_STATE);
    }

    public byte[] encode() {
        var keyBytes = key.getBytes(StandardCharsets.UTF_8);

        return ByteBuffer.allocate(HEADER_BYTES + keyBytes.length + state.length)
                         .put(VERSION)
                         .put((byte) op.ordinal())
                         .putInt(keyBytes.length)
                         .put(keyBytes)
                         .put(state)
                         .array();
    }

    /// Parse one record. Every failure is typed rather than thrown: this runs inside the fold, where an
    /// escaping exception would abandon a partition mid-rebuild and leave it neither recovered nor
    /// visibly broken.
    public static Result<EntityLogRecord> decode(byte[] bytes) {
        if (bytes.length < HEADER_BYTES) {
            return new EntityLogError.MalformedRecord("record shorter than the " + HEADER_BYTES
                                                     + "-byte header: " + bytes.length).result();
        }

        var buffer = ByteBuffer.wrap(bytes);
        var version = buffer.get();

        if (version != VERSION) {
            return new EntityLogError.UnsupportedVersion(version, VERSION).result();
        }

        var opOrdinal = buffer.get();
        var keyLength = buffer.getInt();

        return validateKeyLength(keyLength, bytes.length).flatMap(_ -> Op.fromOrdinal(opOrdinal))
                                .map(op -> readBody(buffer, op, keyLength));
    }

    private static Result<Integer> validateKeyLength(int keyLength, int totalLength) {
        return keyLength >= 0 && HEADER_BYTES + keyLength <= totalLength
               ? success(keyLength)
               : new EntityLogError.MalformedRecord("key length " + keyLength
                                                   + " does not fit a " + totalLength
                                                   + "-byte record").result();
    }

    private static EntityLogRecord readBody(ByteBuffer buffer, Op op, int keyLength) {
        var keyBytes = new byte[keyLength];

        buffer.get(keyBytes);
        var state = new byte[buffer.remaining()];

        buffer.get(state);

        return new EntityLogRecord(op, new String(keyBytes, StandardCharsets.UTF_8), state);
    }
}
