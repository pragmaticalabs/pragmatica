// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.concurrent.CountDownLatch;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;

/// #3858 round 2, invariant 1: the type is the enforcer, so the order between a record's producer and the DEAD edge's consumer is
/// pinned on the type itself, with real threads. A drain record that names a node must never leave a mark or a stamp behind once the
/// consumer settled that node.
class DepartureMarksTest {
    private static final int ROUNDS = 20_000;

    /// The producer checks the settled set and writes the mark in one section, and the consumer ends the mark and settles in one
    /// section. If either were split, a round where the producer checked first and the consumer ran before the write would leave a mark
    /// that nothing consumes (R1).
    @Test
    void recordRacingTheConsumer_neverLeavesAMarkOrAStampAfterTheSettle() throws InterruptedException {
        var marks = DepartureMarks.departureMarks(ROUNDS + 1);
        var ids = IntStream.range(0, ROUNDS)
                           .mapToObj(i -> new NodeId("raced-" + i))
                           .toList();
        var start = new CountDownLatch(1);
        var producer = new Thread(() -> runAfter(start, () -> ids.forEach(id -> marks.announceFromRecord(id, 1L, _ -> true))));
        var consumer = new Thread(() -> runAfter(start, () -> ids.forEach(marks::settle)));

        producer.start();
        consumer.start();
        start.countDown();
        producer.join();
        consumer.join();

        assertThat(ids.stream().filter(marks::isMarked)).as("a mark created after its consumer ran").isEmpty();
        assertThat(marks.trackedCount()).as("a mark left by a record after the settle").isZero();
    }

    /// A settled id is released once the FSM no longer holds the node DEAD (a later life), and a record then marks it again.
    @Test
    void settledId_isReleasedWhenTheFsmNoLongerHoldsTheNodeDead() {
        var marks = DepartureMarks.departureMarks(4);
        var id = new NodeId("later-life");

        marks.settle(id);
        marks.announceFromRecord(id, 1L, _ -> true);
        assertThat(marks.isMarked(id)).as("the life is over while the FSM holds it DEAD").isFalse();

        marks.announceFromRecord(id, 2L, _ -> false);
        assertThat(marks.isMarked(id)).as("a later life is marked again").isTrue();
    }

    /// The handshake ends a mark only on the caller's proof, read inside the same section that removes it.
    @Test
    void handshake_endsTheMarkOnlyOnProof() {
        var marks = DepartureMarks.departureMarks(4);
        var id = new NodeId("handshake");

        marks.announceFromRecord(id, 1L, _ -> false);
        marks.endOnHandshake(id, _ -> false);
        assertThat(marks.isMarked(id)).as("no proof: the mark stays").isTrue();

        marks.endOnHandshake(id, _ -> true);
        assertThat(marks.isMarked(id)).as("proof: the mark ends").isFalse();
    }

    private static final long GRACE_MS = 30_000L;

    /// The stamp-only state is unreachable: every operation that removes a mark removes its record stamp in the same monitor section
    /// (`settle`, `endOnHandshake`, the age-out's drop), so no id is left known without a mark except a settled one. This replaces the
    /// pin of the old state, where a stamp could outlive its mark.
    @Test
    void everyMarkRemoval_removesTheStampWithIt_soAStampOnlyStateIsUnreachable() {
        var marks = DepartureMarks.departureMarks(8);
        var settled = new NodeId("removed-by-settle");
        var handshaken = new NodeId("removed-by-handshake");
        var aged = new NodeId("removed-by-age-out");

        marks.announceFromRecord(settled, 1L, _ -> false);
        marks.announceFromRecord(handshaken, 1L, _ -> false);
        marks.announceFromRecord(aged, 1L, _ -> false);

        marks.settle(settled);
        marks.endOnHandshake(handshaken, _ -> true);
        marks.expire(aged, 1L + GRACE_MS, GRACE_MS, _ -> true, _ -> false);

        assertThat(marks.known()).as("a stamp survived the removal of its mark").containsExactly(settled);
    }

    /// A `DrainRequested` edge is raised by a live life, so it releases the id's settle: the record that follows stamps the mark and the
    /// omission ageing can end it. The FSM never holds a node `Dead` while it raises that edge; the predicate here stands for a record
    /// read that still says `Dead`.
    @Test
    void edgeAfterTheSettle_releasesTheId_soTheRecordStampsTheLiveLife() {
        var marks = DepartureMarks.departureMarks(4);
        var id = new NodeId("edge-after-settle");

        marks.settle(id);
        marks.announceFromEdge(id, 1L);
        marks.announceFromRecord(id, 2L, _ -> true);
        marks.expire(id, 2L + GRACE_MS, GRACE_MS, _ -> true, _ -> false);

        assertThat(marks.isMarked(id)).as("the stamped mark ended after the omission grace").isFalse();
    }

    /// The settle ends the record stamp together with the mark: a stamp left behind would let the omission ageing end a LATER life's
    /// mark that no drain record created.
    @Test
    void stampOfAnEndedLife_neverEndsALaterLifesEdgeMark() {
        var marks = DepartureMarks.departureMarks(4);
        var id = new NodeId("stale-stamp");

        marks.announceFromRecord(id, 1L, _ -> false);
        marks.settle(id);
        marks.announceFromEdge(id, 2L);
        marks.expire(id, 2L + GRACE_MS, GRACE_MS, _ -> true, _ -> false);

        assertThat(marks.isMarked(id)).as("the edge-created mark of the later life survives the old stamp").isTrue();
    }

    /// A settled id is state the observer must visit again to release: `known` names it, and the age-out releases it once the FSM no
    /// longer holds the node `Dead`, so the set cannot outgrow the FSM's own DEAD entries.
    @Test
    void settledId_isKnownUntilTheAgeOutReleasesIt() {
        var marks = DepartureMarks.departureMarks(4);
        var id = new NodeId("settled-known");

        marks.settle(id);
        assertThat(marks.known()).as("a settled id is visited by the age-out").contains(id);

        marks.expire(id, 1L, GRACE_MS, _ -> true, _ -> false);
        assertThat(marks.known()).as("released once the FSM does not hold the node Dead").doesNotContain(id);
    }

    /// At the cap a new mark is dropped, never an old one, and a mark already held is renewed.
    @Test
    void atTheCap_aNewMarkIsDropped_andAHeldMarkIsRenewed() {
        var marks = DepartureMarks.departureMarks(2);
        var first = new NodeId("cap-1");
        var second = new NodeId("cap-2");
        var third = new NodeId("cap-3");

        marks.announceFromEdge(first, 1L);
        marks.announceFromEdge(second, 1L);
        marks.announceFromEdge(third, 1L);
        marks.announceFromEdge(first, 2L);

        assertThat(marks.isMarked(third)).as("a new mark at the cap is dropped").isFalse();
        assertThat(marks.isMarked(first)).as("a held mark stays").isTrue();
        assertThat(marks.trackedCount()).isEqualTo(2);
    }

    private static void runAfter(CountDownLatch start, Runnable work) {
        try {
            start.await();
            work.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
