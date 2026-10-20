// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.NodeIdCodec;
import org.pragmatica.lang.Contract;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.serialization.SliceCodec.TypeCodec;
import org.pragmatica.serialization.SliceCodec.TypeReader;
import org.pragmatica.serialization.SliceCodec.TypeWriter;

import io.netty.buffer.ByteBuf;


/// The wire shape of [AetherValue.TopicSubscriptionValue] before it gained `tier` (#1337): one component, `nodeId`, under
/// tag 1655.
///
/// Records are encoded as a positional concatenation with no field count, so a reader of the new shape cannot read the old
/// bytes, and a snapshot decodes as one unit: ONE old-shape row would fail the whole restore and leave the node syncing. The new
/// shape therefore has its own tag (2169), and tag 1655 keeps decoding the old one, as the current type with
/// [AetherValue.TopicTier#UNDECLARED] (the pre-#1337 meaning of a row: no requirement is recorded). Encoding never uses this tag
/// for a live value; this record exists so the old bytes can be named, pinned in the wire baseline and produced by a test.
///
/// Not a `@Codec` type: its codec is written by hand, because its reader answers the current type, not this one.
public record LegacyTopicSubscriptionValue(NodeId nodeId) {
    public static final int TAG = SliceCodec.deterministicTag("org.pragmatica.aether.slice.kvstore.LegacyTopicSubscriptionValue");

    public static final TypeCodec<LegacyTopicSubscriptionValue> CODEC = codec();

    /// Raw on purpose: the registry keys the codec by this class and by tag 1655, and the reader answers the type the old bytes
    /// mean now, so the reader's result type is not this record's.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static TypeCodec<LegacyTopicSubscriptionValue> codec() {
        TypeWriter<LegacyTopicSubscriptionValue> writer = LegacyTopicSubscriptionValue::writeBody;
        TypeReader<AetherValue.TopicSubscriptionValue> reader = LegacyTopicSubscriptionValue::readBody;

        return new TypeCodec(LegacyTopicSubscriptionValue.class, TAG, writer, reader);
    }

    /// The codec's writer callback: a `void` by the shape of `TypeWriter`.
    @Contract
    static void writeBody(SliceCodec codec, ByteBuf buf, LegacyTopicSubscriptionValue value) {
        SliceCodec.writeCompact(buf, NodeIdCodec.TAG);
        NodeIdCodec.writeBody(codec, buf, value.nodeId());
    }

    static AetherValue.TopicSubscriptionValue readBody(SliceCodec codec, ByteBuf buf) {
        SliceCodec.expectCompactTag(buf, NodeIdCodec.TAG, "TopicSubscriptionValue.nodeId");

        return AetherValue.TopicSubscriptionValue.topicSubscriptionValue(NodeIdCodec.readBody(codec, buf));
    }
}
