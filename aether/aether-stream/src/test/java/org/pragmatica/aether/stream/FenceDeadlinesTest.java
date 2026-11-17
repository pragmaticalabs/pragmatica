// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.replication.PartitionKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.FenceTargetTest.ownedBy;
import static org.pragmatica.aether.stream.FenceTargetTest.start;

/// #2981 step 3 (design §2.3 H2, NEW-4, §8): the fence deadline base is captured once per `(partition, ownershipTerm)` at
/// the first delivery of the record that makes this node owner, from the renewal of `L` delivered BEFORE that record.
class FenceDeadlinesTest {
    private static final NodeId SELF = NodeId.nodeId("node-3").unwrap();
    private static final NodeId L = NodeId.nodeId("node-2").unwrap();
    private static final NodeId OTHER = NodeId.nodeId("node-1").unwrap();
    private static final PartitionKey KEY = PartitionKey.partitionKey("orders", 0);
    private static final long W = 3_720_000_000L;

    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final Map<NodeId, Long> renewals = new HashMap<>();
    private FenceDeadlines deadlines;

    @BeforeEach
    void setUp() {
        deadlines = FenceDeadlines.fenceDeadlines(SELF,
                                                  node -> Option.option(renewals.get(node)),
                                                  now::get,
                                                  TimeSpan.timeSpan(W).nanos());
    }

    private static StreamPartitionOwnershipValue movedToSelf(long term) {
        return ownedBy(SELF, term, List.of(SELF, L), List.of(start(term - 1, Option.some(L))));
    }

    @Test
    void deadlineBase_isTheRenewalOfLDeliveredBeforeTheRecord_andALaterRenewalDoesNotMoveIt() {
        renewals.put(L, 500L);
        now.set(2_000L);
        deadlines.onOwnershipDelivered(KEY, movedToSelf(5L));

        renewals.put(L, 9_000L);
        now.set(10_000L);

        assertThat(deadlines.deadline(KEY, 5L)).as("base = renewalApplied[L] at the record's delivery, plus W")
                                               .isEqualTo(500L + W);
    }

    @Test
    void deadlineBase_isNotMovedByAnIsrOnlyRecordOfTheSameTerm_afterANewerRenewalOfL() {
        renewals.put(L, 500L);
        deadlines.onOwnershipDelivered(KEY, movedToSelf(5L));

        renewals.put(L, 9_000L);
        now.set(10_000L);
        deadlines.onOwnershipDelivered(KEY, movedToSelf(5L).withIsr(List.of(SELF, L, OTHER)));

        assertThat(deadlines.deadline(KEY, 5L)).isEqualTo(500L + W);
    }

    @Test
    void deadlineBase_recordOfANewTerm_capturesANewBase() {
        renewals.put(L, 500L);
        deadlines.onOwnershipDelivered(KEY, movedToSelf(5L));

        renewals.put(L, 9_000L);
        deadlines.onOwnershipDelivered(KEY, movedToSelf(7L));

        assertThat(deadlines.deadline(KEY, 7L)).isEqualTo(9_000L + W);
    }

    @Test
    void deadlineBase_noRenewalOfLDelivered_isTheRecordsDeliveryTime() {
        now.set(4_000L);
        deadlines.onOwnershipDelivered(KEY, movedToSelf(5L));
        now.set(8_000L);

        assertThat(deadlines.deadline(KEY, 5L)).isEqualTo(4_000L + W);
    }

    @Test
    void deadlineBase_unknownL_isTheRecordsDeliveryTime_notAnyRenewal() {
        renewals.put(L, 500L);
        now.set(4_000L);
        deadlines.onOwnershipDelivered(KEY, ownedBy(SELF, 5L, List.of(SELF, L), List.of(start(4L, Option.none()))));

        assertThat(deadlines.deadline(KEY, 5L)).isEqualTo(4_000L + W);
    }

    /// v3186 F1: a term with no delivered base (restart, snapshot) uses now at the FIRST read and keeps it, so a re-reading gate's
    /// deadline passes instead of moving with every read.
    @Test
    void deadline_noEntryForTheTerm_capturesNowAtTheFirstRead_andLaterReadsKeepIt() {
        renewals.put(L, 500L);
        deadlines.onOwnershipDelivered(KEY, movedToSelf(5L));
        now.set(7_000L);

        assertThat(deadlines.deadline(KEY, 6L)).as("a term with no captured base (restart, snapshot) uses now").isEqualTo(7_000L + W);
        assertThat(deadlines.deadline(PartitionKey.partitionKey("orders", 1), 5L)).isEqualTo(7_000L + W);

        now.set(7_000L + 2 * W);

        assertThat(deadlines.deadline(KEY, 6L)).as("the first read's base stands").isEqualTo(7_000L + W);
        assertThat(deadlines.deadline(PartitionKey.partitionKey("orders", 1), 5L)).isEqualTo(7_000L + W);
        assertThat(deadlines.nanosLeft(KEY, 6L)).as("so the deadline has passed").isEqualTo(-W);
    }

    /// A base captured by a gate's read is not moved by the record's later delivery for the same term (the listener may lag the
    /// applied view the gate read).
    @Test
    void deadline_capturedByAGateRead_isNotMovedByTheTermsLaterDelivery() {
        now.set(7_000L);
        deadlines.deadline(KEY, 5L);
        renewals.put(L, 9_000L);
        now.set(9_500L);
        deadlines.onOwnershipDelivered(KEY, movedToSelf(5L));

        assertThat(deadlines.deadline(KEY, 5L)).isEqualTo(7_000L + W);
    }

    /// A read for an OLDER term (a stale gate) neither replaces the newer term's base nor stores one of its own.
    @Test
    void deadline_readForAnOlderTerm_keepsTheNewerTermsBase() {
        renewals.put(L, 500L);
        deadlines.onOwnershipDelivered(KEY, movedToSelf(5L));
        now.set(7_000L);

        assertThat(deadlines.deadline(KEY, 4L)).as("a stale term gets now + W").isEqualTo(7_000L + W);
        now.set(8_000L);
        assertThat(deadlines.deadline(KEY, 4L)).as("and stores nothing").isEqualTo(8_000L + W);
        assertThat(deadlines.deadline(KEY, 5L)).as("the newer term's base stands").isEqualTo(500L + W);
    }

    /// With no entry (after another owner's record dropped it) an older-term read stores its own base at the first read; it is
    /// harmless: the entry is that older term's, a later term replaces it and there is at most one per partition.
    @Test
    void deadline_olderTermReadWithNoEntry_capturesItsOwnBase_andALaterTermReplacesIt() {
        now.set(7_000L);
        assertThat(deadlines.deadline(KEY, 4L)).isEqualTo(7_000L + W);
        now.set(8_000L);
        assertThat(deadlines.deadline(KEY, 4L)).as("the first read's base stands").isEqualTo(7_000L + W);
        now.set(9_000L);
        assertThat(deadlines.deadline(KEY, 5L)).as("a later term replaces it").isEqualTo(9_000L + W);
        now.set(9_500L);
        assertThat(deadlines.deadline(KEY, 5L)).isEqualTo(9_000L + W);
    }

    @Test
    void nanosLeft_isTheDeadlineLessNow() {
        renewals.put(L, 500L);
        deadlines.onOwnershipDelivered(KEY, movedToSelf(5L));
        now.set(1_500L);

        assertThat(deadlines.nanosLeft(KEY, 5L)).isEqualTo(W - 1_000L);
    }

    @Test
    void deadlineBase_isDroppedWhenAnotherNodeOwnsANewTerm() {
        renewals.put(L, 500L);
        deadlines.onOwnershipDelivered(KEY, movedToSelf(5L));
        deadlines.onOwnershipDelivered(KEY, ownedBy(L, 6L, List.of(L, SELF), List.of(start(5L, Option.some(SELF)))));
        now.set(7_000L);

        assertThat(deadlines.deadline(KEY, 5L)).as("the term ended, so its base is gone").isEqualTo(7_000L + W);
    }
}
