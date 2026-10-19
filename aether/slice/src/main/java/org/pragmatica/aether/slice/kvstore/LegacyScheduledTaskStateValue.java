// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.serialization.SliceCodec.TypeCodec;
import org.pragmatica.serialization.SliceCodec.TypeReader;
import org.pragmatica.serialization.SliceCodec.TypeWriter;

import io.netty.buffer.ByteBuf;


/// The wire shape of [AetherValue.ScheduledTaskStateValue] before it gained `boundHolder` (#1155): twelve components, under
/// tag 1634.
///
/// Records are encoded as a positional concatenation with no field count, so a reader of the new shape cannot read the old
/// bytes, and a snapshot decodes as one unit: ONE old-shape row would fail the whole restore and leave the node syncing. The
/// new shape therefore has its own tag (2168), and tag 1634 keeps decoding the old one, as the current type with no bound
/// holder (the pre-#1155 behaviour of a row: nothing refuses its write). Encoding never uses this tag for a live value; this
/// record exists so the old bytes can be named, pinned in the wire baseline and produced by a test.
///
/// Not a `@Codec` type: its codec is written by hand, because its reader answers the current type, not this one.
public record LegacyScheduledTaskStateValue(long lastExecutionAt,
                                            long nextFireAt,
                                            int consecutiveFailures,
                                            int totalExecutions,
                                            String lastFailureMessage,
                                            long updatedAt,
                                            int skippedOverlaps,
                                            String lastOutcome,
                                            int fireSeq,
                                            long newestFireAt,
                                            int completionTimeouts,
                                            int lateResolutions) {
    public static final int TAG = SliceCodec.deterministicTag("org.pragmatica.aether.slice.kvstore.LegacyScheduledTaskStateValue");

    public static final TypeCodec<LegacyScheduledTaskStateValue> CODEC = codec();

    /// Raw on purpose: the registry keys the codec by this class and by tag 1634, and the reader answers the type the old bytes
    /// mean now, so the reader's result type is not this record's.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static TypeCodec<LegacyScheduledTaskStateValue> codec() {
        TypeWriter<LegacyScheduledTaskStateValue> writer = LegacyScheduledTaskStateValue::writeBody;
        TypeReader<AetherValue.ScheduledTaskStateValue> reader = LegacyScheduledTaskStateValue::readBody;

        return new TypeCodec(LegacyScheduledTaskStateValue.class, TAG, writer, reader);
    }

    static void writeBody(SliceCodec codec, ByteBuf buf, LegacyScheduledTaskStateValue value) {
        buf.writeByte(SliceCodec.TAG_LONG);
        buf.writeLong(value.lastExecutionAt());
        buf.writeByte(SliceCodec.TAG_LONG);
        buf.writeLong(value.nextFireAt());
        buf.writeByte(SliceCodec.TAG_INT);
        buf.writeInt(value.consecutiveFailures());
        buf.writeByte(SliceCodec.TAG_INT);
        buf.writeInt(value.totalExecutions());
        buf.writeByte(SliceCodec.TAG_STRING);
        SliceCodec.writeString(buf, value.lastFailureMessage());
        buf.writeByte(SliceCodec.TAG_LONG);
        buf.writeLong(value.updatedAt());
        buf.writeByte(SliceCodec.TAG_INT);
        buf.writeInt(value.skippedOverlaps());
        buf.writeByte(SliceCodec.TAG_STRING);
        SliceCodec.writeString(buf, value.lastOutcome());
        buf.writeByte(SliceCodec.TAG_INT);
        buf.writeInt(value.fireSeq());
        buf.writeByte(SliceCodec.TAG_LONG);
        buf.writeLong(value.newestFireAt());
        buf.writeByte(SliceCodec.TAG_INT);
        buf.writeInt(value.completionTimeouts());
        buf.writeByte(SliceCodec.TAG_INT);
        buf.writeInt(value.lateResolutions());
    }

    static AetherValue.ScheduledTaskStateValue readBody(SliceCodec codec, ByteBuf buf) {
        SliceCodec.expectTag(buf, SliceCodec.TAG_LONG, "ScheduledTaskStateValue.lastExecutionAt");
        var lastExecutionAt = buf.readLong();

        SliceCodec.expectTag(buf, SliceCodec.TAG_LONG, "ScheduledTaskStateValue.nextFireAt");
        var nextFireAt = buf.readLong();

        SliceCodec.expectTag(buf, SliceCodec.TAG_INT, "ScheduledTaskStateValue.consecutiveFailures");
        var consecutiveFailures = buf.readInt();

        SliceCodec.expectTag(buf, SliceCodec.TAG_INT, "ScheduledTaskStateValue.totalExecutions");
        var totalExecutions = buf.readInt();

        SliceCodec.expectTag(buf, SliceCodec.TAG_STRING, "ScheduledTaskStateValue.lastFailureMessage");
        var lastFailureMessage = SliceCodec.readString(buf);

        SliceCodec.expectTag(buf, SliceCodec.TAG_LONG, "ScheduledTaskStateValue.updatedAt");
        var updatedAt = buf.readLong();

        SliceCodec.expectTag(buf, SliceCodec.TAG_INT, "ScheduledTaskStateValue.skippedOverlaps");
        var skippedOverlaps = buf.readInt();

        SliceCodec.expectTag(buf, SliceCodec.TAG_STRING, "ScheduledTaskStateValue.lastOutcome");
        var lastOutcome = SliceCodec.readString(buf);

        SliceCodec.expectTag(buf, SliceCodec.TAG_INT, "ScheduledTaskStateValue.fireSeq");
        var fireSeq = buf.readInt();

        SliceCodec.expectTag(buf, SliceCodec.TAG_LONG, "ScheduledTaskStateValue.newestFireAt");
        var newestFireAt = buf.readLong();

        SliceCodec.expectTag(buf, SliceCodec.TAG_INT, "ScheduledTaskStateValue.completionTimeouts");
        var completionTimeouts = buf.readInt();

        SliceCodec.expectTag(buf, SliceCodec.TAG_INT, "ScheduledTaskStateValue.lateResolutions");
        var lateResolutions = buf.readInt();

        return new AetherValue.ScheduledTaskStateValue(lastExecutionAt,
                                                       nextFireAt,
                                                       consecutiveFailures,
                                                       totalExecutions,
                                                       lastFailureMessage,
                                                       updatedAt,
                                                       skippedOverlaps,
                                                       lastOutcome,
                                                       fireSeq,
                                                       newestFireAt,
                                                       completionTimeouts,
                                                       lateResolutions);
    }
}
