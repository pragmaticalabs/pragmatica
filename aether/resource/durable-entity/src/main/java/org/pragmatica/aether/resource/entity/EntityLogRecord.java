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
/// a length-prefixed string; a fixed layout is smaller, parses by fixed-offset reads rather than by a
/// registry lookup, and is owned entirely here.
///
/// ## Layout
/// ```
/// [0]     format version, currently VERSION
/// [1]     operation ordinal — see Op
/// [2..5]  key length in bytes, big-endian
/// [6..]   key, UTF-8
/// [..]    encoded state; EMPTY for DELETE; a TimerPayload for the three timer ops
/// ```
/// The version byte is the evolution seam: a reader that meets an unknown version FAILS rather than
/// guessing, because a misparsed record silently corrupts folded state rather than announcing itself.
///
/// ## Timers ride this same log (#345 I4)
/// A pending timer is not a side table — it is a record here, so it is fenced, replicated and
/// fsync-durable by exactly the machinery entity state already is, and a new owner rebuilds its timer
/// wheel by the same replay that rebuilds its state. The three timer ops put a versioned [TimerPayload]
/// in `state` rather than growing the envelope, which keeps [#VERSION] at 1: every build still finds the
/// op and the key of every record, and a later timer-payload change costs a payload bump alone.
///
/// @param op    what the record does to the key
/// @param key   the entity key, as rendered by the entity's `String.valueOf(K)`
/// @param state the encoded entity state after the operation; zero-length for [Op#DELETE]; an encoded
///              [TimerPayload] for [Op#TIMER_SCHEDULE], [Op#TIMER_CANCEL] and [Op#TIMER_FIRE]
public record EntityLogRecord(EntityLogRecord.Op op, String key, byte[] state) {
    /// The framing version this build writes. Bump ONLY alongside a reader that still accepts every
    /// older version — an entity log outlives the node that wrote it, so a version this build cannot
    /// read is unrecoverable state, not a compatibility inconvenience.
    public static final byte VERSION = 1;
    private static final int HEADER_BYTES = 6;
    private static final byte[] EMPTY_STATE = new byte[0];
    /// `fireAt` is meaningful only on [Op#TIMER_SCHEDULE]; cancel and fire carry this so the payload
    /// framing stays one fixed shape rather than three.
    private static final long NO_FIRE_AT = 0L;

    /// What a record does to its key when folded. Deliberately explicit rather than encoding a delete as
    /// a null/empty state: a legitimately empty encoded state and a tombstone must not be the same bytes.
    ///
    /// Ordinals are the wire form, so new variants are only ever APPENDED — inserting one would silently
    /// re-interpret every record already on disk as a different operation.
    public enum Op {
        UPSERT,
        DELETE,
        TIMER_SCHEDULE,
        TIMER_CANCEL,
        TIMER_FIRE;
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
    /// prior value standing on every replay. It also auto-cancels the key's pending timers (spec §5.1).
    public static EntityLogRecord delete(String key) {
        return new EntityLogRecord(Op.DELETE, key, EMPTY_STATE);
    }

    /// A one-shot timer registered for `key`: at `fireAtEpochMillis` the owner applies `command` to the
    /// key's state. `fireAtEpochMillis` is stamped by the committed OWNER's wall clock as this record is
    /// appended, and travels with the record — so a handover does not restart the delay, and the clock
    /// that minted the instant is the clock that finds it due until ownership moves. See
    /// [DurableEntity#scheduleTimer] for what that does and does not guarantee across a handover.
    public static EntityLogRecord timerSchedule(String key, String token, long fireAtEpochMillis, byte[] command) {
        return new EntityLogRecord(Op.TIMER_SCHEDULE,
                                   key,
                                   TimerPayload.timerPayload(token, fireAtEpochMillis, command).encode());
    }

    /// The timer `token` is no longer pending on `key`. Carries no state change, and applying it to a
    /// token that is not pending is a no-op — which is what makes cancel idempotent across replay, across
    /// a caller cancelling twice, and across the consume-on-failure path that fires it for a timer whose
    /// command could not be applied.
    public static EntityLogRecord timerCancel(String key, String token) {
        return new EntityLogRecord(Op.TIMER_CANCEL,
                                   key,
                                   TimerPayload.timerPayload(token, NO_FIRE_AT, EMPTY_STATE).encode());
    }

    /// The timer `token` fired: it leaves the pending set AND the key now holds `state`, in ONE record.
    /// Two records would leave a window in which a crash between them re-fires a timer whose command was
    /// already applied — the payload therefore carries the POST-FIRE STATE where a schedule carries the
    /// command.
    public static EntityLogRecord timerFire(String key, String token, byte[] state) {
        return new EntityLogRecord(Op.TIMER_FIRE,
                                   key,
                                   TimerPayload.timerPayload(token, NO_FIRE_AT, state).encode());
    }

    /// The timer payload carried in [#state], for the three timer ops only. Typed failure, never a throw,
    /// for the same reason [#decode] is: this runs inside the fold.
    public Result<TimerPayload> timerPayload() {
        return TimerPayload.decode(state);
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

    /// What the three timer ops put in [EntityLogRecord#state], with its OWN version byte.
    ///
    /// ## Why a second version and not a bump of the envelope's
    /// The envelope's [EntityLogRecord#VERSION] frames `(op, key, state)` and has not moved: every build,
    /// old or new, still finds the operation and the key of every record it meets. This version frames
    /// only what timer ops put INSIDE `state`, so a later timer change costs a payload bump and leaves
    /// every UPSERT and DELETE ever written readable by every build. One version per thing that can
    /// change independently.
    ///
    /// ## Layout
    /// ```
    /// [0]      payload version, currently PAYLOAD_VERSION
    /// [1..8]   fireAtEpochMillis, big-endian; meaningful only for TIMER_SCHEDULE
    /// [9..12]  token length in bytes, big-endian
    /// [13..]   token, UTF-8
    /// [..]     body — the encoded command for TIMER_SCHEDULE, the POST-FIRE STATE for TIMER_FIRE,
    ///          empty for TIMER_CANCEL
    /// ```
    ///
    /// @param token             the timer's identity within its key, as handed to the caller
    /// @param fireAtEpochMillis wall-clock instant the timer becomes due; `0` where the op does not use it
    /// @param body              command / post-fire state / empty, per the op
    public record TimerPayload(String token, long fireAtEpochMillis, byte[] body) {
        /// The payload version this build writes. Same rule as the envelope's: bump only alongside a
        /// reader that still accepts every older version.
        public static final byte PAYLOAD_VERSION = 1;
        private static final int PAYLOAD_HEADER_BYTES = 13;

        public static TimerPayload timerPayload(String token, long fireAtEpochMillis, byte[] body) {
            return new TimerPayload(token, fireAtEpochMillis, body);
        }

        public byte[] encode() {
            var tokenBytes = token.getBytes(StandardCharsets.UTF_8);

            return ByteBuffer.allocate(PAYLOAD_HEADER_BYTES + tokenBytes.length + body.length)
                             .put(PAYLOAD_VERSION)
                             .putLong(fireAtEpochMillis)
                             .putInt(tokenBytes.length)
                             .put(tokenBytes)
                             .put(body)
                             .array();
        }

        /// Parse one timer payload. Every failure is typed rather than thrown, for the same reason
        /// [EntityLogRecord#decode]'s are: this runs inside the fold, where an escaping exception would
        /// abandon a partition mid-rebuild and leave it neither recovered nor visibly broken.
        public static Result<TimerPayload> decode(byte[] bytes) {
            if (bytes.length < PAYLOAD_HEADER_BYTES) {
                return new EntityLogError.MalformedRecord("timer payload shorter than the " + PAYLOAD_HEADER_BYTES
                                                         + "-byte header: " + bytes.length).result();
            }

            var buffer = ByteBuffer.wrap(bytes);
            var version = buffer.get();

            if (version != PAYLOAD_VERSION) {
                return new EntityLogError.UnsupportedVersion(version, PAYLOAD_VERSION).result();
            }

            var fireAt = buffer.getLong();
            var tokenLength = buffer.getInt();

            return validateTokenLength(tokenLength, bytes.length).map(_ -> readPayloadBody(buffer, fireAt, tokenLength));
        }

        private static Result<Integer> validateTokenLength(int tokenLength, int totalLength) {
            return tokenLength >= 0 && PAYLOAD_HEADER_BYTES + tokenLength <= totalLength
                   ? success(tokenLength)
                   : new EntityLogError.MalformedRecord("timer token length " + tokenLength
                                                       + " does not fit a " + totalLength
                                                       + "-byte payload").result();
        }

        private static TimerPayload readPayloadBody(ByteBuffer buffer, long fireAt, int tokenLength) {
            var tokenBytes = new byte[tokenLength];

            buffer.get(tokenBytes);
            var body = new byte[buffer.remaining()];

            buffer.get(body);

            return new TimerPayload(new String(tokenBytes, StandardCharsets.UTF_8), fireAt, body);
        }
    }
}
