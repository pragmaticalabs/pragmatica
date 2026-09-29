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
