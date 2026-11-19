// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.BlueprintIdCodec;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaReach;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
import org.pragmatica.lang.Contract;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.serialization.SliceCodec.TypeCodec;
import org.pragmatica.serialization.SliceCodec.TypeReader;
import org.pragmatica.serialization.SliceCodec.TypeWriter;

import io.netty.buffer.ByteBuf;


/// The wire shape of [SchemaVersionValue] before it gained `reachedBy` (#834): eight components, under tag 1638.
///
/// Records are encoded as a positional concatenation with no field count, so a reader of the new shape cannot read the old
/// bytes, and a snapshot decodes as one unit: ONE old-shape row would fail the whole restore and leave the node syncing. The
/// new shape therefore has its own tag (2984), and tag 1638 keeps decoding the old one, as the current type with
/// `reachedBy = MIGRATED` (the pre-#834 behaviour of a row: nothing guards a republish over it, because nothing recorded
/// that an operator undid or baselined it). Encoding never uses this tag for a live value; this record exists so the old
/// bytes can be named, pinned in the wire baseline and produced by a test.
///
/// Not a `@Codec` type: its codec is written by hand, because its reader answers the current type, not this one.
public record LegacySchemaVersionValue(String datasourceName,
                                       int currentVersion,
                                       String lastMigration,
                                       SchemaStatus status,
                                       String artifactCoords,
                                       BlueprintId owningBlueprint,
                                       int attemptCount,
                                       long updatedAt) {
    public static final int TAG = SliceCodec.deterministicTag("org.pragmatica.aether.slice.kvstore.LegacySchemaVersionValue");

    public static final TypeCodec<LegacySchemaVersionValue> CODEC = codec();

    /// Raw on purpose: the registry keys the codec by this class and by tag 1638, and the reader answers the type the old bytes
    /// mean now, so the reader's result type is not this record's.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static TypeCodec<LegacySchemaVersionValue> codec() {
        TypeWriter<LegacySchemaVersionValue> writer = LegacySchemaVersionValue::writeBody;
        TypeReader<SchemaVersionValue> reader = LegacySchemaVersionValue::readBody;

        return new TypeCodec(LegacySchemaVersionValue.class, TAG, writer, reader);
    }

    /// The codec's writer callback: a `void` by the shape of `TypeWriter`.
    @Contract
    static void writeBody(SliceCodec codec, ByteBuf buf, LegacySchemaVersionValue value) {
        buf.writeByte(SliceCodec.TAG_STRING);
        SliceCodec.writeString(buf, value.datasourceName());
        buf.writeByte(SliceCodec.TAG_INT);
        buf.writeInt(value.currentVersion());
        buf.writeByte(SliceCodec.TAG_STRING);
        SliceCodec.writeString(buf, value.lastMigration());
        SliceCodec.writeCompact(buf, AetherValue_SchemaStatusCodec.TAG);
        AetherValue_SchemaStatusCodec.writeBody(codec, buf, value.status());
        buf.writeByte(SliceCodec.TAG_STRING);
        SliceCodec.writeString(buf, value.artifactCoords());
        SliceCodec.writeCompact(buf, BlueprintIdCodec.TAG);
        BlueprintIdCodec.writeBody(codec, buf, value.owningBlueprint());
        buf.writeByte(SliceCodec.TAG_INT);
        buf.writeInt(value.attemptCount());
        buf.writeByte(SliceCodec.TAG_LONG);
        buf.writeLong(value.updatedAt());
    }

    static SchemaVersionValue readBody(SliceCodec codec, ByteBuf buf) {
        SliceCodec.expectTag(buf, SliceCodec.TAG_STRING, "SchemaVersionValue.datasourceName");
        var datasourceName = SliceCodec.readString(buf);

        SliceCodec.expectTag(buf, SliceCodec.TAG_INT, "SchemaVersionValue.currentVersion");
        var currentVersion = buf.readInt();

        SliceCodec.expectTag(buf, SliceCodec.TAG_STRING, "SchemaVersionValue.lastMigration");
        var lastMigration = SliceCodec.readString(buf);

        SliceCodec.readCompact(buf);
        var status = AetherValue_SchemaStatusCodec.readBody(codec, buf);

        SliceCodec.expectTag(buf, SliceCodec.TAG_STRING, "SchemaVersionValue.artifactCoords");
        var artifactCoords = SliceCodec.readString(buf);

        SliceCodec.readCompact(buf);
        var owningBlueprint = BlueprintIdCodec.readBody(codec, buf);

        SliceCodec.expectTag(buf, SliceCodec.TAG_INT, "SchemaVersionValue.attemptCount");
        var attemptCount = buf.readInt();

        SliceCodec.expectTag(buf, SliceCodec.TAG_LONG, "SchemaVersionValue.updatedAt");
        var updatedAt = buf.readLong();

        return new SchemaVersionValue(datasourceName,
                                      currentVersion,
                                      lastMigration,
                                      status,
                                      artifactCoords,
                                      owningBlueprint,
                                      attemptCount,
                                      updatedAt,
                                      SchemaReach.MIGRATED);
    }
}
