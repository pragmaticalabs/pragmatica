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


/// Encoding of one partition's folded state — every live key with its encoded state and its PENDING
/// TIMERS, as of a checkpoint offset (#345 I3, timers #345 I4).
///
/// ## Why the values stay encoded
/// The fold holds each key's state as the SAME bytes the log carries, never as a decoded object. Three
/// things fall out of that: the fold applies a log record without decoding it, a checkpoint writes the
/// bytes it already has, and a read costs exactly one decode — the same as before I3, when a read decoded
/// what the storage engine returned. Decoding into the fold would move that cost to every write and every
/// replayed record, which is the wrong direction: writes and replays are many, and replay is the thing
/// that has to be fast when a partition is recovering. A pending timer's command is held the same way.
///
/// ## Why timers belong IN the checkpoint
/// The checkpoint is the base a recovering node folds from, and a checkpoint pins retention: everything
/// at or below its offset MAY be reclaimed. A timer scheduled below that offset and not carried here
/// would therefore be lost exactly when the log that proves it existed is reclaimed — silently, and only
/// on the node that took over. Carrying them makes a fold's timer wheel as recoverable as its state.
///
/// ## Layout
/// ```
/// [0]     format version — VERSION_WITHOUT_TIMERS or VERSION, whichever #encode chose
/// [1..4]  entry count, big-endian
/// then per entry:
///   [4] key length, big-endian
///   [ ] key, UTF-8
///   [4] state length, big-endian
///   [ ] encoded state
/// then, VERSION 2 and later only:
///   [4] timer-bearing key count, big-endian
///   then per key:
///     [4] key length, big-endian
///     [ ] key, UTF-8
///     [4] timer count, big-endian
///     then per timer:
///       [4] token length, big-endian
///       [ ] token, UTF-8
///       [8] fireAtEpochMillis, big-endian
///       [4] command length, big-endian
///       [ ] encoded command
/// ```
/// The version written is the narrowest the content needs — a snapshot with no pending timers is emitted
/// as VERSION 1 and has no timer section at all. See [#VERSION].
///
/// Tombstones never appear: a delete removes the key from the fold, so a snapshot lists only live keys.
/// That is what keeps a checkpoint proportional to LIVE state rather than to the history that produced it
/// — the whole point of checkpointing rather than keeping the log forever. A key whose timers have all
/// fired or been cancelled likewise contributes nothing to the timer section.
final class EntityFoldSnapshot {
    /// The newest snapshot layout this build understands. Same rule as [EntityLogRecord#VERSION]: bump only
    /// with a reader that still accepts older versions, because a checkpoint this build cannot read is
    /// unrecoverable state.
    ///
    /// It is NOT unconditionally what gets written. [#encode] emits the NARROWEST layout the content needs,
    /// so a snapshot with no pending timers carries [#VERSION_WITHOUT_TIMERS] and a build one release older
    /// can still read it. A version bump is otherwise one-way for everyone, including the majority of
    /// keyspaces that never schedule a timer; this confines it to the snapshots that actually carry one.
    static final byte VERSION = 2;
    /// The pre-timer layout: state entries and nothing after them. Still WRITTEN, not merely tolerated —
    /// see [#VERSION].
    static final byte VERSION_WITHOUT_TIMERS = 1;
    private static final int HEADER_BYTES = 5;
    private static final int LENGTH_BYTES = 4;
    private static final int FIRE_AT_BYTES = 8;
    private static final int COUNT_BYTES = 4;

    private EntityFoldSnapshot() {}

    /// Write the NARROWEST layout the content needs: [#VERSION_WITHOUT_TIMERS] when there are no pending
    /// timers, [#VERSION] only when there are.
    ///
    /// Version bumps are usually one-way, and that is the cost this avoids. A keyspace that uses no timers
    /// — most of them — would otherwise start emitting checkpoints that a build one release older cannot
    /// read, for a section that carries nothing. The choice is made per SNAPSHOT, not once per keyspace:
    /// a keyspace whose pending timers have all fired or been cancelled emits v1 again on its next
    /// checkpoint, so v2 is confined to the snapshots that actually carry a timer. The reader accepts
    /// both either way, so nothing downstream branches on this.
    static byte[] encode(Map<String, byte[]> state, Map<String, Map<String, EntityFold.PendingTimer>> timers) {
        return timers.isEmpty()
               ? encodeWithoutTimers(state)
               : encodeWithTimers(state, timers);
    }

    private static byte[] encodeWithoutTimers(Map<String, byte[]> state) {
        var buffer = ByteBuffer.allocate(HEADER_BYTES + stateSectionSize(state));

        putStateSection(buffer, VERSION_WITHOUT_TIMERS, state);

        return buffer.array();
    }

    private static byte[] encodeWithTimers(Map<String, byte[]> state,
                                           Map<String, Map<String, EntityFold.PendingTimer>> timers) {
        var buffer = ByteBuffer.allocate(HEADER_BYTES + stateSectionSize(state) + timerSectionSize(timers));

        putStateSection(buffer, VERSION, state);
        buffer.putInt(timers.size());
        timers.forEach((key, pending) -> putTimers(buffer, key, pending));

        return buffer.array();
    }

    private static void putStateSection(ByteBuffer buffer, byte version, Map<String, byte[]> state) {
        buffer.put(version);
        buffer.putInt(state.size());
        state.forEach((key, value) -> putEntry(buffer, key, value));
    }

    private static void putEntry(ByteBuffer buffer, String key, byte[] value) {
        var keyBytes = key.getBytes(StandardCharsets.UTF_8);

        buffer.putInt(keyBytes.length);
        buffer.put(keyBytes);
        buffer.putInt(value.length);
        buffer.put(value);
    }

    private static void putTimers(ByteBuffer buffer, String key, Map<String, EntityFold.PendingTimer> pending) {
        var keyBytes = key.getBytes(StandardCharsets.UTF_8);

        buffer.putInt(keyBytes.length);
        buffer.put(keyBytes);
        buffer.putInt(pending.size());
        pending.forEach((token, timer) -> putTimer(buffer, token, timer));
    }

    private static void putTimer(ByteBuffer buffer, String token, EntityFold.PendingTimer timer) {
        var tokenBytes = token.getBytes(StandardCharsets.UTF_8);

        buffer.putInt(tokenBytes.length);
        buffer.put(tokenBytes);
        buffer.putLong(timer.fireAtEpochMillis());
        buffer.putInt(timer.command().length);
        buffer.put(timer.command());
    }

    /// The state entries alone, WITHOUT the leading version and count — those are [#HEADER_BYTES], which
    /// both layouts share.
    private static int stateSectionSize(Map<String, byte[]> state) {
        var size = 0;

        for (var entry : state.entrySet()) {
            size += LENGTH_BYTES + entry.getKey().getBytes(StandardCharsets.UTF_8).length + LENGTH_BYTES + entry.getValue().length;
        }

        return size;
    }

    /// The whole timer section including its own leading key count — the bytes a
    /// [#VERSION_WITHOUT_TIMERS] snapshot does not have at all.
    private static int timerSectionSize(Map<String, Map<String, EntityFold.PendingTimer>> timers) {
        var size = COUNT_BYTES;

        for (var entry : timers.entrySet()) {
            size += LENGTH_BYTES + entry.getKey().getBytes(StandardCharsets.UTF_8).length + COUNT_BYTES + timersSize(entry.getValue());
        }

        return size;
    }

    private static int timersSize(Map<String, EntityFold.PendingTimer> pending) {
        var size = 0;

        for (var entry : pending.entrySet()) {
            size += LENGTH_BYTES + entry.getKey().getBytes(StandardCharsets.UTF_8).length + FIRE_AT_BYTES + LENGTH_BYTES + entry.getValue()
                                                                                                                                .command().length;
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
    ///
    /// BOTH live versions are accepted. A [#VERSION_WITHOUT_TIMERS] checkpoint is not degraded data — it
    /// was written by a build with no timers at all, so "no pending timers" is its exact meaning, and
    /// refusing it would strand every keyspace checkpointed before I4.
    static Result<FoldedState> decode(byte[] bytes) {
        if (bytes.length < HEADER_BYTES) {
            return new EntityLogError.MalformedRecord("checkpoint shorter than the " + HEADER_BYTES
                                                     + "-byte header: " + bytes.length).result();
        }

        var buffer = ByteBuffer.wrap(bytes);
        var version = buffer.get();

        if (version != VERSION && version != VERSION_WITHOUT_TIMERS) {
            return new EntityLogError.UnsupportedVersion(version, VERSION).result();
        }

        var count = buffer.getInt();

        return count < 0
               ? new EntityLogError.MalformedRecord("checkpoint declares a negative entry count: " + count).result()
               : readEntries(buffer, count).flatMap(state -> readTimerSection(buffer, version, state));
    }

    private static Result<FoldedState> readTimerSection(ByteBuffer buffer, byte version, Map<String, byte[]> state) {
        return version == VERSION_WITHOUT_TIMERS
               ? success(new FoldedState(state, Map.of()))
               : readCount(buffer, "timer-bearing key").flatMap(keyCount -> readTimerKeys(buffer, keyCount))
                          .map(timers -> new FoldedState(state, timers));
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

    private static Result<Map<String, Map<String, EntityFold.PendingTimer>>> readTimerKeys(ByteBuffer buffer,
                                                                                           int keyCount) {
        Map<String, Map<String, EntityFold.PendingTimer>> timers = new LinkedHashMap<>();

        for (var i = 0; i < keyCount; i++) {
            var entry = readTimerKey(buffer, i);

            if (entry instanceof Result.Failure<Map.Entry<String, Map<String, EntityFold.PendingTimer>>>(var cause)) {
                return cause.result();
            }

            entry.onSuccess(pair -> timers.put(pair.getKey(), pair.getValue()));
        }

        return success(timers);
    }

    private static Result<Map.Entry<String, Map<String, EntityFold.PendingTimer>>> readTimerKey(ByteBuffer buffer,
                                                                                                int index) {
        return readChunk(buffer, "timer key", index).flatMap(keyBytes -> readTimersOf(buffer,
                                                                                      index,
                                                                                      new String(keyBytes,
                                                                                                 StandardCharsets.UTF_8)));
    }

    private static Result<Map.Entry<String, Map<String, EntityFold.PendingTimer>>> readTimersOf(ByteBuffer buffer,
                                                                                                int index,
                                                                                                String key) {
        return readCount(buffer, "timer").flatMap(count -> readTimers(buffer, index, count))
                        .map(pending -> Map.entry(key, pending));
    }

    private static Result<Map<String, EntityFold.PendingTimer>> readTimers(ByteBuffer buffer, int index, int count) {
        Map<String, EntityFold.PendingTimer> pending = new LinkedHashMap<>();

        for (var i = 0; i < count; i++) {
            var timer = readTimer(buffer, index);

            if (timer instanceof Result.Failure<Map.Entry<String, EntityFold.PendingTimer>>(var cause)) {
                return cause.result();
            }

            timer.onSuccess(pair -> pending.put(pair.getKey(), pair.getValue()));
        }

        return success(pending);
    }

    private static Result<Map.Entry<String, EntityFold.PendingTimer>> readTimer(ByteBuffer buffer, int index) {
        return readChunk(buffer, "timer token", index).flatMap(tokenBytes -> readFireAt(buffer, index).flatMap(fireAt -> readTimerBody(buffer,
                                                                                                                                       index,
                                                                                                                                       new String(tokenBytes,
                                                                                                                                                  StandardCharsets.UTF_8),
                                                                                                                                       fireAt)));
    }

    private static Result<Map.Entry<String, EntityFold.PendingTimer>> readTimerBody(ByteBuffer buffer,
                                                                                    int index,
                                                                                    String token,
                                                                                    long fireAt) {
        return readChunk(buffer, "timer command", index).map(command -> Map.entry(token,
                                                                                  new EntityFold.PendingTimer(fireAt,
                                                                                                              command)));
    }

    private static Result<Long> readFireAt(ByteBuffer buffer, int index) {
        return buffer.remaining() < FIRE_AT_BYTES
               ? new EntityLogError.MalformedRecord("checkpoint truncated before the fire time of timer entry " + index).result()
               : success(buffer.getLong());
    }

    /// A count is read with the same suspicion as a length: it drives a loop, so a corrupted one either
    /// spins on a truncated buffer or silently returns a short map.
    private static Result<Integer> readCount(ByteBuffer buffer, String what) {
        if (buffer.remaining() < COUNT_BYTES) {
            return new EntityLogError.MalformedRecord("checkpoint truncated before its " + what + " count").result();
        }

        var count = buffer.getInt();

        return count >= 0 && count <= buffer.remaining()
               ? success(count)
               : new EntityLogError.MalformedRecord("checkpoint declares " + what
                                                   + " count " + count
                                                   + " with " + buffer.remaining()
                                                   + " byte(s) remaining").result();
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

    /// One partition's checkpointed fold: its live keys and the timers still pending on them. A record
    /// rather than a bare map because the two halves are restored together — seeding state without its
    /// timers is exactly the silent loss the timer section exists to prevent.
    ///
    /// @param state  live key to encoded entity state
    /// @param timers key to token to the timer still pending under it; keys with no pending timer are
    ///               absent rather than present-and-empty
    record FoldedState(Map<String, byte[]> state, Map<String, Map<String, EntityFold.PendingTimer>> timers) {}
}
