// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.LongStream;

import org.pragmatica.aether.stream.forward.RawEventDto;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.forward.StreamForwardError;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1555 R1/R2: the promotion gate's remote reads of a peer, against a peer that serves only `oldest .. head` and
/// answers anything below with the remote form of `CursorExpired` (its message, which names the oldest offset).
class OwnerPeerReadsTest {
    private static final NodeId PEER = new NodeId("peer");
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final int PAGE = 4;

    private final List<Long> requestedFrom = new CopyOnWriteArrayList<>();

    /// A peer retaining `oldest .. head`.
    private OwnerPeerReads.PageRead retaining(long oldest, long head) {
        return (_, _, _, from, max) -> {
            requestedFrom.add(from);

            return from < oldest
                   ? new StreamForwardError.ReadForwardFailed(new StreamError.CursorExpired(from, oldest).message()).promise()
                   : Promise.success(StreamForwardClient.ReadForwardResult.readForwardResult(events(from, Math.min(head, from + max - 1)),
                                                                                            false));
        };
    }

    /// A peer retaining `0 .. head` whose byte cap fits `cap` events per page: a fuller page is cut and marked truncated.
    private OwnerPeerReads.PageRead byteCapped(long head, int cap) {
        return (_, _, _, from, max) -> {
            requestedFrom.add(from);
            var to = Math.min(head, from + max - 1);
            var cut = to - from + 1 > cap;

            return Promise.success(StreamForwardClient.ReadForwardResult.readForwardResult(events(from, cut ? from + cap - 1 : to),
                                                                                          cut));
        };
    }

    /// #1431 sibling: a byte-capped page is shorter than `page` but is not the peer's end. Read as the end, the gate's
    /// probe understates the peer's head and can promote a candidate below records the peer holds. Red under
    /// "ignore `truncated`": the probe reports 1 (the first cut page's end) for a peer whose head is 9.
    @Test
    void appendedWatermark_byteCappedPage_pagesOnToTheRealHead() {
        var head = OwnerPeerReads.appendedWatermark(byteCapped(9, 2), PEER, STREAM, PARTITION, PAGE).await();

        assertThat(head.unwrap()).isEqualTo(9L);
    }

    @Test
    void replicaWatermark_byteCappedPage_pagesOnToTheRealHead() {
        var head = OwnerPeerReads.replicaWatermark(byteCapped(9, 2), PEER, STREAM, PARTITION, PAGE).await();

        assertThat(head.unwrap()).isEqualTo(9L);
    }

    /// A cut page with no event cannot advance: it fails (the gate fails closed) instead of reading as "holds nothing
    /// from here" — which would understate the peer for the probe and drop the rest of the window for the range.
    @Test
    void cutPageWithNoEvent_failsTheProbeAndTheRange() {
        var oversized = byteCapped(9, 0);

        assertThat(OwnerPeerReads.appendedWatermark(oversized, PEER, STREAM, PARTITION, PAGE).await().isFailure()).isTrue();
        assertThat(OwnerPeerReads.appendedRange(oversized, PEER, STREAM, PARTITION, 0, 5, PAGE).await().isFailure()).isTrue();
    }

    private static List<RawEventDto> events(long from, long to) {
        return LongStream.rangeClosed(from, to)
                         .mapToObj(offset -> new RawEventDto(offset, 1L, ("rec-" + offset).getBytes(StandardCharsets.UTF_8)))
                         .toList();
    }

    /// R2: a healthy peer whose offset 0 has aged out is probed from its oldest offset, not reported unreachable.
    @Test
    void appendedWatermark_offsetZeroExpired_resumesAtOldestAndReportsTheHead() {
        var head = OwnerPeerReads.appendedWatermark(retaining(100, 109), PEER, STREAM, PARTITION, PAGE).await();

        assertThat(head.isSuccess()).as("probe of a healthy peer: %s", head).isTrue();
        assertThat(head.unwrap()).isEqualTo(109L);
        assertThat(requestedFrom.getFirst()).isZero();
        assertThat(requestedFrom.get(1)).isEqualTo(100L);
    }

    @Test
    void appendedWatermark_partitionNotHeld_isMinusOne() {
        OwnerPeerReads.PageRead notHeld = (_, _, _, _, _) -> new StreamForwardError.ReadForwardFailed(StreamError.General.PARTITION_NOT_LOCAL.message()).promise();

        assertThat(OwnerPeerReads.appendedWatermark(notHeld, PEER, STREAM, PARTITION, PAGE).await().unwrap()).isEqualTo(-1L);
    }

    private static OwnerPeerReads.PageRead refusing(Cause cause) {
        return (_, _, _, _, _) -> new StreamForwardError.ReadForwardFailed(cause.message()).promise();
    }

    /// A peer that HOLDS the partition but has not materialized it (paced, deferred) answers a gate probe with its
    /// durable watermark, not with the `-1` of a peer that holds nothing.
    @Test
    void appendedWatermark_heldNotMaterialized_reportsTheDurableWatermark() {
        var held = refusing(new StreamError.PartitionHeldNotMaterialized(STREAM, PARTITION, 41L));

        assertThat(OwnerPeerReads.appendedWatermark(held, PEER, STREAM, PARTITION, PAGE).await().unwrap()).isEqualTo(41L);
    }

    /// F1a: the backfill's probe reads a held-unmaterialized peer as REACHABLE, at its durable watermark.
    @Test
    void replicaWatermark_heldNotMaterialized_isReachableAtItsDurableWatermark() {
        var held = refusing(new StreamError.PartitionHeldNotMaterialized(STREAM, PARTITION, 41L));
        var empty = refusing(new StreamError.PartitionHeldNotMaterialized(STREAM, PARTITION, -1L));

        assertThat(OwnerPeerReads.replicaWatermark(held, PEER, STREAM, PARTITION, PAGE).await().unwrap()).isEqualTo(41L);
        assertThat(OwnerPeerReads.replicaWatermark(empty, PEER, STREAM, PARTITION, PAGE).await().unwrap()).isEqualTo(-1L);
    }

    /// A genuine non-holder stays "no information" for the backfill's probe: the owner must not promote past a peer
    /// it cannot read, unlike the gate's probe, which has the committed replica set to say the peer holds nothing.
    @Test
    void replicaWatermark_partitionNotLocal_staysAFailure() {
        var notHeld = refusing(StreamError.General.PARTITION_NOT_LOCAL);

        assertThat(OwnerPeerReads.replicaWatermark(notHeld, PEER, STREAM, PARTITION, PAGE).await().isFailure()).isTrue();
    }

    @Test
    void replicaWatermark_transportFailure_staysAFailure() {
        var timedOut = refusing(StreamForwardError.General.STREAM_FORWARD_UNAVAILABLE);

        assertThat(OwnerPeerReads.replicaWatermark(timedOut, PEER, STREAM, PARTITION, PAGE).await().isFailure()).isTrue();
    }

    /// The probe pages like the gate's: a peer whose offset 0 has aged out is probed from its oldest offset.
    @Test
    void replicaWatermark_offsetZeroExpired_resumesAtOldestAndReportsTheHead() {
        var head = OwnerPeerReads.replicaWatermark(retaining(100, 109), PEER, STREAM, PARTITION, PAGE).await();

        assertThat(head.unwrap()).isEqualTo(109L);
    }

    /// R1: a window whose start the peer has evicted is read from the peer's oldest offset — the records it still
    /// holds are compared, not dropped.
    @Test
    void appendedRange_startEvicted_readsTheHeldPartOfTheWindow() {
        var range = OwnerPeerReads.appendedRange(retaining(100, 109), PEER, STREAM, PARTITION, 90, 105, PAGE).await().unwrap();

        assertThat(range).extracting(OffHeapRingBuffer.RawEvent::offset).containsExactly(100L, 101L, 102L, 103L, 104L, 105L);
    }

    @Test
    void appendedRange_wholeWindowEvicted_isEmpty() {
        var range = OwnerPeerReads.appendedRange(retaining(100, 109), PEER, STREAM, PARTITION, 10, 20, PAGE).await().unwrap();

        assertThat(range).isEmpty();
    }

    /// Any other remote failure fails the read, so the gate fails closed on it rather than comparing nothing.
    @Test
    void appendedRange_otherFailure_propagates() {
        OwnerPeerReads.PageRead broken = (_, _, _, _, _) -> new StreamForwardError.ReadForwardFailed("connection reset").promise();

        assertThat(OwnerPeerReads.appendedRange(broken, PEER, STREAM, PARTITION, 0, 10, PAGE).await().isFailure()).isTrue();
        assertThat(OwnerPeerReads.appendedWatermark(broken, PEER, STREAM, PARTITION, PAGE).await().isFailure()).isTrue();
    }
}
