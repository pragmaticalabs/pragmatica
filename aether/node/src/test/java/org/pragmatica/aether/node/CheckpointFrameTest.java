// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.ByteBuffer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey.EntityCheckpointKey;
import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;

import static org.assertj.core.api.Assertions.assertThat;

/// #2087 B4-cut: two copies that retain different parts of a checkpoint's window compare over the records both hold.
class CheckpointFrameTest {
    private static final Epoch E1 = Epoch.epoch(1L, 1L, 1L);
    private static final Epoch E2 = Epoch.epoch(1L, 2L, 1L);

    private static RawEvent event(long offset, String data) {
        return new RawEvent(offset, data.getBytes(java.nio.charset.StandardCharsets.UTF_8), 0L);
    }

    private static byte[] identity(Epoch epoch, RawEvent... events) {
        return CheckpointFrame.lineageOf(List.of(events), epoch).unwrap();
    }

    @Test
    void identities_overDifferentPartsOfTheWindow_agreeWhenTheRecordsBothHoldAreTheSame() {
        var full = identity(E1, event(5, "a"), event(6, "b"), event(7, "c"));
        var tail = identity(E1, event(6, "b"), event(7, "c"));

        assertThat(CheckpointFrame.agree(full, tail)).isTrue();
        assertThat(CheckpointFrame.agree(tail, full)).isTrue();
    }

    @Test
    void aRecordBothHold_withDifferentBytes_isADifferentLineage_evenUnderTheSameEpoch() {
        var recorded = identity(E1, event(5, "a"), event(6, "b"), event(7, "c"));
        var local = identity(E1, event(6, "B"), event(7, "c"));

        assertThat(CheckpointFrame.agree(recorded, local)).isFalse();
    }

    @Test
    void theOwnerEpoch_andTheClaimOffset_areComparedToo() {
        var recorded = identity(E1, event(6, "b"), event(7, "c"));

        assertThat(CheckpointFrame.agree(recorded, identity(E2, event(6, "b"), event(7, "c")))).as("another owner epoch").isFalse();
        assertThat(CheckpointFrame.agree(recorded, identity(E1, event(5, "b"), event(6, "c")))).as("another claim").isFalse();
    }

    @Test
    void anEmptyOrMalformedIdentity_agreesWithNothing() {
        var held = identity(E1, event(7, "c"));

        assertThat(CheckpointFrame.agree(new byte[0], held)).isFalse();
        assertThat(CheckpointFrame.agree(held, new byte[0])).isFalse();
        assertThat(CheckpointFrame.agree(new byte[0], new byte[0])).isFalse();
        assertThat(CheckpointFrame.agree(held, java.util.Arrays.copyOf(held, held.length - 1))).isFalse();
    }

    /// PR-3c: the identity header is stripped on read, so a framed block reads back as its snapshot and lineage.
    @Test
    void aFramedBlock_readsBackAsItsSnapshotAndLineage() {
        var snapshot = bytes("the fold");
        var lineage = bytes("the lineage");
        var withLineage = CheckpointFrame.unframe(7L, CheckpointFrame.frame("orders", 3, 7L, snapshot, Option.some(lineage)));
        var withoutLineage = CheckpointFrame.unframe(7L, CheckpointFrame.frame("orders", 3, 7L, snapshot, Option.none()));

        assertThat(withLineage.snapshot()).isEqualTo(snapshot);
        assertThat(withLineage.lineage().map(Arrays::toString).or("none")).isEqualTo(Arrays.toString(lineage));
        assertThat(withoutLineage.snapshot()).isEqualTo(snapshot);
        assertThat(withoutLineage.lineage().isEmpty()).isTrue();
    }

    /// A block written before PR-3c (no header) reads as before.
    @Test
    void aBlockWithoutTheHeader_readsAsBefore() {
        var snapshot = bytes("an rc5 fold");

        assertThat(CheckpointFrame.unframe(7L, snapshot).snapshot()).isEqualTo(snapshot);
    }

    /// A block shorter than the header (an rc5 snapshot of an empty fold is 5 bytes) reads as before.
    @Test
    void aShortBlockWithoutTheHeader_readsAsBefore() {
        var snapshot = new byte[] {1, 0, 0, 0, 0};

        assertThat(CheckpointFrame.unframe(7L, snapshot).snapshot()).isEqualTo(snapshot);
    }

    /// A header whose keyspace length does not fit the block is not stripped: the block is returned whole (and fails to decode later).
    @Test
    void aHeaderThatDoesNotParse_leavesTheBlockWhole() {
        var tooLong = header(Integer.MAX_VALUE - 64);
        var negative = header(-5);

        assertThat(CheckpointFrame.unframe(7L, tooLong).snapshot()).isEqualTo(tooLong);
        assertThat(CheckpointFrame.unframe(7L, negative).snapshot()).isEqualTo(negative);
    }

    /// The header names the pointer a block was written for, so the delete-time check reads only that pointer (PR-3c); a block without
    /// the header, or whose header does not parse, names none, and every pointer is read.
    @Test
    void aFramedBlock_namesThePointerItWasWrittenFor_andABlockWithoutTheHeaderNamesNone() {
        assertThat(pointerOf(CheckpointFrame.frame("orders", 3, 7L, bytes("the fold"), Option.none()))).isEqualTo("entity-checkpoint/orders/3");
        assertThat(pointerOf(CheckpointFrame.frame("events", 12, 7L, bytes("the fold"), Option.some(bytes("the lineage"))))).isEqualTo("entity-checkpoint/events/12");
        assertThat(pointerOf(bytes("an rc5 fold"))).isEqualTo("none");
        assertThat(pointerOf(header(Integer.MAX_VALUE - 64))).isEqualTo("none");
        assertThat(pointerOf(header(-5))).isEqualTo("none");
    }

    /// #3303 A4a: a block opens with `CKPTID02` and a 16-byte nonce, and two frames of identical input differ within those first 24 bytes, so
    /// re-saving identical content never yields one block id.
    @Test
    void twoFramesOfIdenticalInput_differInTheirFirst24Bytes_andOpenWithTheV2Marker() {
        var first = CheckpointFrame.frame("orders", 3, 7L, bytes("the fold"), Option.none());
        var second = CheckpointFrame.frame("orders", 3, 7L, bytes("the fold"), Option.none());

        assertThat(new String(first, 0, 8, StandardCharsets.US_ASCII)).isEqualTo("CKPTID02");
        assertThat(Arrays.copyOf(first, 24)).isNotEqualTo(Arrays.copyOf(second, 24));
        assertThat(Arrays.copyOfRange(first, 24, first.length)).as("the rest is the same header and body").isEqualTo(Arrays.copyOfRange(second, 24, second.length));
    }

    /// A hand-built `CKPTID01` block (written before the nonce) still reads: snapshot, lineage and pointer.
    @Test
    void aHandBuiltV1Block_stillReads_snapshotLineageAndPointer() {
        var snapshot = bytes("the fold");
        var lineage = bytes("the lineage");
        var name = bytes("orders");
        var body = ByteBuffer.allocate(8 + Integer.BYTES + lineage.length + snapshot.length)
                             .put("LNGCKPT1".getBytes(StandardCharsets.US_ASCII))
                             .putInt(lineage.length)
                             .put(lineage)
                             .put(snapshot)
                             .array();
        var v1 = ByteBuffer.allocate(8 + Integer.BYTES + name.length + Integer.BYTES + Long.BYTES + body.length)
                           .put("CKPTID01".getBytes(StandardCharsets.US_ASCII))
                           .putInt(name.length)
                           .put(name)
                           .putInt(3)
                           .putLong(7L)
                           .put(body)
                           .array();
        var read = CheckpointFrame.unframe(7L, v1);

        assertThat(read.snapshot()).isEqualTo(snapshot);
        assertThat(read.lineage().map(Arrays::toString).or("none")).isEqualTo(Arrays.toString(lineage));
        assertThat(pointerOf(v1)).isEqualTo("entity-checkpoint/orders/3");
    }

    /// A `CKPTID02` header whose keyspace length does not fit the block is not stripped, as for `CKPTID01`.
    @Test
    void aV2HeaderThatDoesNotParse_leavesTheBlockWhole() {
        var block = ByteBuffer.allocate(8 + 16 + Integer.BYTES + 16)
                              .put("CKPTID02".getBytes(StandardCharsets.US_ASCII))
                              .put(new byte[16])
                              .putInt(Integer.MAX_VALUE - 64)
                              .put(new byte[16])
                              .array();

        assertThat(CheckpointFrame.unframe(7L, block).snapshot()).isEqualTo(block);
        assertThat(pointerOf(block)).isEqualTo("none");
    }

    /// T-A36 (#3303 A4a): `CheckpointFrame.frame` draws the per-put nonce, so a caller outside the checkpoint save path would write blocks
    /// the reclaim never accounted for. The main-source callers are exactly the listed ones; an unlisted caller fails here.
    @Test
    void checkpointFrameFrame_isCalledFromTheAllowedMainSourcesOnly() throws IOException {
        try (var files = Files.walk(Path.of("src/main/java"))) {
            var callers = files.filter(path -> path.toString().endsWith(".java"))
                               .filter(CheckpointFrameTest::callsFrame)
                               .map(path -> path.getFileName().toString())
                               .sorted()
                               .collect(Collectors.toList());

            assertThat(callers).containsExactly("StreamEntityLogSubstrate.java");
        }
    }

    private static boolean callsFrame(Path path) {
        try {
            return Files.readString(path).contains("CheckpointFrame.frame(");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String pointerOf(byte[] block) {
        return CheckpointFrame.pointerOf(block).map(EntityCheckpointKey::asString).or("none");
    }

    private static byte[] header(int nameLength) {
        return ByteBuffer.allocate(8 + Integer.BYTES + 16)
                         .put("CKPTID01".getBytes(StandardCharsets.US_ASCII))
                         .putInt(nameLength)
                         .put(new byte[16])
                         .array();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
