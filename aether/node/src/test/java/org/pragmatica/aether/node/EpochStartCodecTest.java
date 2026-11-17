// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.EpochStart;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// #1873: `EpochStart.coversFrom` (the oldest epoch an entry stands for) crosses the consensus wire in the ownership record. A
/// null or ZERO `coversFrom` is read as the entry's own epoch, so a codec that dropped the field would decode every entry as
/// unfolded WITHOUT failing a round trip of unfolded entries: the folded entry's `coversFrom` is what must come back unchanged.
class EpochStartCodecTest {
    private static final Epoch OLDEST_DROPPED = Epoch.epoch(1L, 2L, 3L);
    private static final Epoch OWN = Epoch.epoch(1L, 2L, 9L);

    @Test
    void aFoldedEntry_keepsItsCoversFrom_throughTheNodeCodec() {
        var codec = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
        var folded = new EpochStart(OWN, 100L, OLDEST_DROPPED, Option.none());
        var unfolded = EpochStart.epochStart(Epoch.epoch(1L, 2L, 10L), 120L, Option.none());
        var value = new StreamPartitionOwnershipValue(NodeId.nodeId("node-1").unwrap(),
                                                      Epoch.epoch(1L, 2L, 10L),
                                                      10L,
                                                      HlcTimestamp.ZERO,
                                                      List.of(NodeId.nodeId("node-1").unwrap()),
                                                      1L,
                                                      false,
                                                      List.of(),
                                                      List.of(folded, unfolded));
        var put = new KVCommand.Put<AetherKey, AetherValue>(StreamPartitionOwnershipKey.streamPartitionOwnershipKey("orders", 0), value);
        var buf = Unpooled.buffer();

        codec.write(buf, put);
        KVCommand.Put<?, ?> decoded = codec.read(buf);
        var starts = ((StreamPartitionOwnershipValue) decoded.value()).epochStarts();

        assertThat(starts.getFirst().coversFrom()).as("the folded entry stands for an older epoch than its own").isEqualTo(OLDEST_DROPPED);
        assertThat(starts.getFirst().coversFrom()).isNotEqualTo(starts.getFirst().epoch());
        assertThat(starts.getLast().coversFrom()).as("an unfolded entry stands for its own epoch").isEqualTo(starts.getLast().epoch());
        assertThat(starts).isEqualTo(List.of(folded, unfolded));
    }

    @Test
    void aNullOrZeroCoversFrom_isReadAsTheEntrysOwnEpoch() {
        assertThat(new EpochStart(OWN, 5L, null, Option.none()).coversFrom()).isEqualTo(OWN);
        assertThat(new EpochStart(OWN, 5L, Epoch.ZERO, Option.none()).coversFrom()).isEqualTo(OWN);
        assertThat(EpochStart.epochStart(OWN, 5L, Option.none()).coversFrom()).isEqualTo(OWN);
    }

    /// #2981 step 3 (design §2.3 B1, §8): `owner` crosses the consensus wire, folded and unfolded, and an unknown owner stays
    /// unknown (the fence then waits the full deadline instead of probing a node the codec invented).
    @Test
    void epochStartOwner_roundTripsThroughTheNodeCodec_foldedUnfoldedAndUnknown() {
        var codec = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
        var owner = NodeId.nodeId("node-1").unwrap();
        var earlier = NodeId.nodeId("node-2").unwrap();
        var unknown = new EpochStart(Epoch.epoch(1L, 2L, 8L), 90L, OLDEST_DROPPED, Option.none());
        var folded = new EpochStart(OWN, 100L, OLDEST_DROPPED, Option.some(earlier));
        var unfolded = EpochStart.epochStart(Epoch.epoch(1L, 2L, 10L), 120L, Option.some(owner));
        var value = new StreamPartitionOwnershipValue(owner,
                                                      Epoch.epoch(1L, 2L, 10L),
                                                      10L,
                                                      HlcTimestamp.ZERO,
                                                      List.of(owner),
                                                      1L,
                                                      false,
                                                      List.of(),
                                                      List.of(unknown, folded, unfolded));
        var put = new KVCommand.Put<AetherKey, AetherValue>(StreamPartitionOwnershipKey.streamPartitionOwnershipKey("orders", 0), value);
        var buf = Unpooled.buffer();

        codec.write(buf, put);
        KVCommand.Put<?, ?> decoded = codec.read(buf);
        var starts = ((StreamPartitionOwnershipValue) decoded.value()).epochStarts();

        assertThat(starts.stream().map(EpochStart::owner).toList())
            .isEqualTo(List.of(Option.none(), Option.some(earlier), Option.some(owner)));
        assertThat(starts).isEqualTo(List.of(unknown, folded, unfolded));
    }

    /// #2981 step 3 compatibility: a record in the format BEFORE `owner` (the owner encoding absent from every start) is not
    /// readable by this codec, and the failure is LOUD. The reader takes the bytes after `coversFrom` as the owner's `Option`,
    /// so an old-format start either runs out of bytes (last start) or meets the next start's tag where an `Option` tag is
    /// expected (a middle start): never a record that silently names no owner. Pre-GA no old-format record is persisted or on
    /// a peer's wire, so a loud refusal is the acceptable outcome; this pins that it is not a silent one.
    @Test
    void anOldFormatStart_withoutTheOwnerEncoding_failsLoudly_neverDecodesSilently() {
        var codec = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
        var none = Unpooled.buffer();

        codec.write(none, Option.none());

        var ownerBytes = new byte[none.readableBytes()];

        none.readBytes(ownerBytes);

        var one = encoded(codec, List.of(EpochStart.epochStart(Epoch.epoch(1L, 2L, 5L), 10L, Option.none())));
        var two = encoded(codec,
                          List.of(EpochStart.epochStart(Epoch.epoch(1L, 2L, 5L), 10L, Option.none()),
                                  EpochStart.epochStart(Epoch.epoch(1L, 2L, 6L), 20L, Option.none())));
        var oneOldFormat = withoutOwnerEncodingAt(one, one.length - ownerBytes.length, ownerBytes);
        var twoOldFormat = withoutOwnerEncodingAt(withoutOwnerEncodingAt(two, two.length - ownerBytes.length, ownerBytes),
                                                  one.length - ownerBytes.length,
                                                  ownerBytes);

        assertThat(oneOldFormat).as("the instrument: the owner encoding was really removed").hasSize(one.length - ownerBytes.length);
        assertThat(twoOldFormat).as("the instrument: both owner encodings were really removed").hasSize(two.length - 2 * ownerBytes.length);
        assertThatThrownBy(() -> codec.read(Unpooled.wrappedBuffer(oneOldFormat))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> codec.read(Unpooled.wrappedBuffer(twoOldFormat))).isInstanceOf(RuntimeException.class);
    }

    /// v3186 (CTO, #2981 step 3b): the WRITE side of the owner, probed apart from the reader. The encoded bytes carry the start's
    /// owner id (a start owner no other field names), two starts differing only in their owner encode differently, and each
    /// decodes back to its own owner: an encoder that dropped or replaced the owner fails here even if a reader were lenient.
    @Test
    void epochStartOwner_isWrittenByTheEncoder_andSurvivesEncodeThenDecode() {
        var codec = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
        var first = NodeId.nodeId("fence-owner-a").unwrap();
        var second = NodeId.nodeId("fence-owner-b").unwrap();
        var withFirst = encoded(codec, List.of(EpochStart.epochStart(OWN, 100L, Option.some(first))));
        var withSecond = encoded(codec, List.of(EpochStart.epochStart(OWN, 100L, Option.some(second))));

        assertThat(contains(withFirst, first.id().getBytes(StandardCharsets.UTF_8))).as("the encoder wrote the owner's id").isTrue();
        assertThat(contains(withSecond, second.id().getBytes(StandardCharsets.UTF_8))).as("the encoder wrote the owner's id").isTrue();
        assertThat(withFirst).as("the owner is part of the encoding").isNotEqualTo(withSecond);
        assertThat(decodedStarts(codec, withFirst)).containsExactly(EpochStart.epochStart(OWN, 100L, Option.some(first)));
        assertThat(decodedStarts(codec, withSecond)).containsExactly(EpochStart.epochStart(OWN, 100L, Option.some(second)));
    }

    private static List<EpochStart> decodedStarts(SliceCodec codec, byte[] bytes) {
        KVCommand.Put<?, ?> decoded = codec.read(Unpooled.wrappedBuffer(bytes));

        return ((StreamPartitionOwnershipValue) decoded.value()).epochStarts();
    }

    private static boolean contains(byte[] bytes, byte[] part) {
        return IntStream.rangeClosed(0, bytes.length - part.length)
                        .anyMatch(at -> Arrays.equals(bytes, at, at + part.length, part, 0, part.length));
    }

    private static byte[] encoded(SliceCodec codec, List<EpochStart> starts) {
        var owner = NodeId.nodeId("node-1").unwrap();
        var value = new StreamPartitionOwnershipValue(owner,
                                                      Epoch.epoch(1L, 2L, 10L),
                                                      10L,
                                                      HlcTimestamp.ZERO,
                                                      List.of(owner),
                                                      1L,
                                                      false,
                                                      List.of(),
                                                      starts);
        var put = new KVCommand.Put<AetherKey, AetherValue>(StreamPartitionOwnershipKey.streamPartitionOwnershipKey("orders", 0), value);
        var buf = Unpooled.buffer();

        codec.write(buf, put);

        var bytes = new byte[buf.readableBytes()];

        buf.readBytes(bytes);
        return bytes;
    }

    private static byte[] withoutOwnerEncodingAt(byte[] bytes, int at, byte[] ownerBytes) {
        assertThat(Arrays.copyOfRange(bytes, at, at + ownerBytes.length)).as("the owner encoding sits at the cut").isEqualTo(ownerBytes);

        var out = new byte[bytes.length - ownerBytes.length];

        System.arraycopy(bytes, 0, out, 0, at);
        System.arraycopy(bytes, at + ownerBytes.length, out, at, bytes.length - at - ownerBytes.length);
        return out;
    }
}
