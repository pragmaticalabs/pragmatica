// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.control;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


/// The record framing of a control record (#2590 P1, a WIRE and STORAGE change): a stream record whose payload begins
/// with the control tag is a [ControlRecord], and any other payload is an ordinary event the control index skips. The
/// tag lives in the payload because the WAL frame (`AppendLog`: length, offset, timestamp, payload, CRC) has no field
/// for it; a control record is otherwise an ordinary stream record, replicated, fsynced and retained like any other.
///
/// Layout, big-endian: the 4-byte tag, a version byte (1), the kind name (u16 length, UTF-8), the key (i32 length,
/// UTF-8), the hlc (i64), the payload (i32 length, bytes). Pre-GA, nodes of different versions do not share a log.
public sealed interface ControlRecordCodec {
    byte[] TAG = {(byte) 0xC7, 'C', 'T', 'L'};

    byte VERSION = 1;

    static byte[] encode(ControlRecord record) {
        var kind = record.kind().name().getBytes(StandardCharsets.UTF_8);
        var key = record.key().getBytes(StandardCharsets.UTF_8);
        var payload = record.payload();
        var buffer = ByteBuffer.allocate(TAG.length + 1 + 2 + kind.length + 4 + key.length + 8 + 4 + payload.length);

        buffer.put(TAG)
              .put(VERSION)
              .putShort((short) kind.length)
              .put(kind)
              .putInt(key.length)
              .put(key)
              .putLong(record.hlc())
              .putInt(payload.length)
              .put(payload);

        return buffer.array();
    }

    /// [Option#none] for a payload without the control tag; a failure for a tagged payload that does not decode.
    static Result<Option<ControlRecord>> decode(byte[] payload) {
        return isTagged(payload)
               ? decodeTagged(ByteBuffer.wrap(payload, TAG.length, payload.length - TAG.length)).map(Option::some)
               : Result.success(Option.none());
    }

    private static boolean isTagged(byte[] payload) {
        return payload.length >= TAG.length && Arrays.equals(payload, 0, TAG.length, TAG, 0, TAG.length);
    }

    private static Result<ControlRecord> decodeTagged(ByteBuffer buffer) {
        if (buffer.remaining() < 1 + 2) {
            return ControlLogError.MalformedRecord.FACTORY.apply("truncated header").result();
        }

        var version = buffer.get();

        return version == VERSION
               ? decodeKind(buffer)
               : ControlLogError.MalformedRecord.FACTORY.apply("unknown framing version " + version).result();
    }

    private static Result<ControlRecord> decodeKind(ByteBuffer buffer) {
        var kindLength = Short.toUnsignedInt(buffer.getShort());

        if (buffer.remaining() < kindLength + 4) {
            return ControlLogError.MalformedRecord.FACTORY.apply("truncated kind").result();
        }

        return decodeKey(buffer, ControlKind.controlKind(utf8(buffer, kindLength)));
    }

    private static Result<ControlRecord> decodeKey(ByteBuffer buffer, ControlKind kind) {
        var keyLength = buffer.getInt();

        if (keyLength < 0 || buffer.remaining() < keyLength + 8 + 4) {
            return ControlLogError.MalformedRecord.FACTORY.apply("truncated key").result();
        }

        var key = utf8(buffer, keyLength);
        var hlc = buffer.getLong();

        return decodePayload(buffer, kind, key, hlc);
    }

    private static Result<ControlRecord> decodePayload(ByteBuffer buffer, ControlKind kind, String key, long hlc) {
        var payloadLength = buffer.getInt();

        if (payloadLength < 0 || buffer.remaining() != payloadLength) {
            return ControlLogError.MalformedRecord.FACTORY.apply("payload length " + payloadLength
                                                                + " does not match the " + buffer.remaining()
                                                                + " bytes left").result();
        }

        var payload = new byte[payloadLength];

        buffer.get(payload);

        return Result.success(ControlRecord.controlRecord(kind, key, payload, hlc));
    }

    private static String utf8(ByteBuffer buffer, int length) {
        var bytes = new byte[length];

        buffer.get(bytes);

        return new String(bytes, StandardCharsets.UTF_8);
    }

    record unused() implements ControlRecordCodec {}
}
