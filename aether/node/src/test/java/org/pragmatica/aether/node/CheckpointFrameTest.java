// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

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
