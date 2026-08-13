// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


/// Encoding of one partition's folded state — every live key and its encoded state as of a checkpoint
/// offset (#345 I3).
///
/// ## Why the values stay encoded
/// The fold holds each key's state as the SAME bytes the log carries, never as a decoded object. Three
/// things fall out of that: the fold applies a log record without decoding it, a checkpoint writes the
/// bytes it already has, and a read costs exactly one decode — the same as before I3, when a read decoded
/// what the storage engine returned. Decoding into the fold would move that cost to every write and every
/// replayed record, which is the wrong direction: writes and replays are many, and replay is the thing
/// that has to be fast when a partition is recovering.
///
/// ## Layout
/// ```
/// [0]     format version, currently VERSION
/// [1..4]  entry count, big-endian
/// then per entry:
///   [4] key length, big-endian
///   [ ] key, UTF-8
///   [4] state length, big-endian
///   [ ] encoded state
/// ```
/// Tombstones never appear: a delete removes the key from the fold, so a snapshot lists only live keys.
/// That is what keeps a checkpoint proportional to LIVE state rather than to the history that produced it
/// — the whole point of checkpointing rather than keeping the log forever.
final class EntityFoldSnapshot {
    /// The snapshot version this build writes. Same rule as [EntityLogRecord#VERSION]: bump only with a
    /// reader that still accepts older versions, because a checkpoint this build cannot read is
    /// unrecoverable state.
    static final byte VERSION = 1;
    private static final int HEADER_BYTES = 5;
    private static final int LENGTH_BYTES = 4;

    private EntityFoldSnapshot() {}

    static byte[] encode(Map<String, byte[]> state) {
        var buffer = ByteBuffer.allocate(encodedSize(state));

        buffer.put(VERSION);
        buffer.putInt(state.size());
        state.forEach((key, value) -> putEntry(buffer, key, value));

        return buffer.array();
    }

    private static void putEntry(ByteBuffer buffer, String key, byte[] value) {
        var keyBytes = key.getBytes(StandardCharsets.UTF_8);

        buffer.putInt(keyBytes.length);
        buffer.put(keyBytes);
        buffer.putInt(value.length);
        buffer.put(value);
    }

    private static int encodedSize(Map<String, byte[]> state) {
        var size = HEADER_BYTES;

        for (var entry : state.entrySet()) {
            size += LENGTH_BYTES + entry.getKey().getBytes(StandardCharsets.UTF_8).length + LENGTH_BYTES + entry.getValue().length;
        }

        return size;
    }

    /// Parse a checkpoint. Failures are typed rather than thrown, for the same reason the log record's
    /// are: this runs while a partition is recovering, and an escaping exception would leave it neither
    /// recovered nor visibly broken.
    ///
    /// Every length is validated against what actually remains in the buffer before it is used. A
    /// checkpoint is read from shared storage and may have been written by another node, so its bytes are
    /// treated as input to be checked, not as a trusted local artifact.
    static Result<Map<String, byte[]>> decode(byte[] bytes) {
        if (bytes.length < HEADER_BYTES) {
            return new EntityLogError.MalformedRecord("checkpoint shorter than the " + HEADER_BYTES
                                                     + "-byte header: " + bytes.length).result();
        }

        var buffer = ByteBuffer.wrap(bytes);
        var version = buffer.get();

        if (version != VERSION) {
            return new EntityLogError.UnsupportedVersion(version, VERSION).result();
        }

        var count = buffer.getInt();

        return count < 0
               ? new EntityLogError.MalformedRecord("checkpoint declares a negative entry count: " + count).result()
               : readEntries(buffer, count);
    }

    private static Result<Map<String, byte[]>> readEntries(ByteBuffer buffer, int count) {
        Map<String, byte[]> state = new LinkedHashMap<>();

        for (var i = 0; i < count; i++) {
            var entry = readEntry(buffer, i);

            if (entry instanceof Result.Failure<Map.Entry<String, byte[]>>(var cause)) {
                return cause.result();
            }

            entry.onSuccess(pair -> state.put(pair.getKey(), pair.getValue()));
        }

        return success(state);
    }

    /// The key is read before the state and the state only if the key succeeded — `flatMap` is what makes
    /// that ordering explicit rather than incidental. Reading both eagerly would advance the buffer past a
    /// length this method had already decided was invalid.
    private static Result<Map.Entry<String, byte[]>> readEntry(ByteBuffer buffer, int index) {
        return readChunk(buffer, "key", index).flatMap(keyBytes -> readChunk(buffer, "state", index).map(valueBytes -> Map.entry(new String(keyBytes,
                                                                                                                                            StandardCharsets.UTF_8),
                                                                                                                                 valueBytes)));
    }

    private static Result<byte[]> readChunk(ByteBuffer buffer, String what, int index) {
        if (buffer.remaining() < LENGTH_BYTES) {
            return new EntityLogError.MalformedRecord("checkpoint truncated before " + what
                                                     + " length of entry " + index).result();
        }

        var length = buffer.getInt();

        if (length < 0 || length > buffer.remaining()) {
            return new EntityLogError.MalformedRecord("checkpoint entry " + index
                                                     + " declares " + what
                                                     + " length " + length
                                                     + " with " + buffer.remaining()
                                                     + " byte(s) remaining").result();
        }

        var chunk = new byte[length];

        buffer.get(chunk);

        return success(chunk);
    }
}
