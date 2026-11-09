// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactCodec;
import org.pragmatica.aether.invoke.InvocationMessage.InvokeRequest;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.MethodNameCodec;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.NodeIdCodec;
import org.pragmatica.lang.Contract;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.serialization.SliceCodec.TypeCodec;
import org.pragmatica.serialization.SliceCodec.TypeReader;
import org.pragmatica.serialization.SliceCodec.TypeWriter;

import io.netty.buffer.ByteBuf;

import static org.pragmatica.lang.Option.none;

/// The wire shape of [InvokeRequest] before it gained `caller` (#2157): ten components, under tag 642.
///
/// Records are encoded as a positional concatenation with no field count, so a reader of the new shape cannot read the old
/// bytes: a node of the previous build sending an invocation during a rolling upgrade would make the receiver fail with an
/// untyped `IndexOutOfBoundsException`. The new shape therefore has its own tag (644), and tag 642 keeps decoding the old one,
/// as the current type with no caller: the identity of a caller whose sender could not state one is absent, so the callee's
/// `SecurityContext` parameter receives the anonymous context (fail-closed), never a stand-in identity. Encoding never uses
/// this tag for a live request; this record exists so the old bytes can be named, pinned in the wire baseline and produced
/// by a test.
///
/// Not a `@Codec` type: its codec is written by hand, because its reader answers the current type, not this one.
public record LegacyInvokeRequest(NodeId sender,
                                  String correlationId,
                                  String requestId,
                                  Artifact targetSlice,
                                  MethodName method,
                                  byte[] payload,
                                  boolean expectResponse,
                                  int depth,
                                  int hops,
                                  boolean sampled) {
    public static final int TAG = SliceCodec.deterministicTag("org.pragmatica.aether.invoke.LegacyInvokeRequest");

    public static final TypeCodec<LegacyInvokeRequest> CODEC = codec();

    /// Raw on purpose: the registry keys the codec by this class and by tag 642, and the reader answers the type the old bytes
    /// mean now, so the reader's result type is not this record's.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static TypeCodec<LegacyInvokeRequest> codec() {
        TypeWriter<LegacyInvokeRequest> writer = LegacyInvokeRequest::writeBody;
        TypeReader<InvokeRequest> reader = LegacyInvokeRequest::readBody;

        return new TypeCodec(LegacyInvokeRequest.class, TAG, writer, reader);
    }

    /// The codec's writer callback: a `void` by the shape of `TypeWriter`. Component by component what the generated codec of
    /// the ten-component shape wrote.
    @Contract
    static void writeBody(SliceCodec codec, ByteBuf buf, LegacyInvokeRequest value) {
        SliceCodec.writeCompact(buf, NodeIdCodec.TAG);
        NodeIdCodec.writeBody(codec, buf, value.sender());
        buf.writeByte(SliceCodec.TAG_STRING);
        SliceCodec.writeString(buf, value.correlationId());
        buf.writeByte(SliceCodec.TAG_STRING);
        SliceCodec.writeString(buf, value.requestId());
        SliceCodec.writeCompact(buf, ArtifactCodec.TAG);
        ArtifactCodec.writeBody(codec, buf, value.targetSlice());
        SliceCodec.writeCompact(buf, MethodNameCodec.TAG);
        MethodNameCodec.writeBody(codec, buf, value.method());
        codec.write(buf, value.payload());
        buf.writeByte(value.expectResponse()
                      ? SliceCodec.TAG_TRUE
                      : SliceCodec.TAG_FALSE);
        buf.writeByte(SliceCodec.TAG_INT);
        buf.writeInt(value.depth());
        buf.writeByte(SliceCodec.TAG_INT);
        buf.writeInt(value.hops());
        buf.writeByte(value.sampled()
                      ? SliceCodec.TAG_TRUE
                      : SliceCodec.TAG_FALSE);
    }

    static InvokeRequest readBody(SliceCodec codec, ByteBuf buf) {
        SliceCodec.expectCompactTag(buf, NodeIdCodec.TAG, "InvokeRequest.sender");
        var sender = NodeIdCodec.readBody(codec, buf);

        SliceCodec.expectTag(buf, SliceCodec.TAG_STRING, "InvokeRequest.correlationId");
        var correlationId = SliceCodec.readString(buf);

        SliceCodec.expectTag(buf, SliceCodec.TAG_STRING, "InvokeRequest.requestId");
        var requestId = SliceCodec.readString(buf);

        SliceCodec.expectCompactTag(buf, ArtifactCodec.TAG, "InvokeRequest.targetSlice");
        var targetSlice = ArtifactCodec.readBody(codec, buf);

        SliceCodec.expectCompactTag(buf, MethodNameCodec.TAG, "InvokeRequest.method");
        var method = MethodNameCodec.readBody(codec, buf);
        var payload = (byte[]) codec.read(buf);
        var expectResponse = buf.readByte() == SliceCodec.TAG_TRUE;

        SliceCodec.expectTag(buf, SliceCodec.TAG_INT, "InvokeRequest.depth");
        var depth = buf.readInt();

        SliceCodec.expectTag(buf, SliceCodec.TAG_INT, "InvokeRequest.hops");
        var hops = buf.readInt();
        var sampled = buf.readByte() == SliceCodec.TAG_TRUE;

        return InvokeRequest.invokeRequest(sender,
                                           correlationId,
                                           requestId,
                                           targetSlice,
                                           method,
                                           payload,
                                           expectResponse,
                                           depth,
                                           hops,
                                           sampled,
                                           none());
    }
}
