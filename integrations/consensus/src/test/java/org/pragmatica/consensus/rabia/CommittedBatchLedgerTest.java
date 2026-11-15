// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.consensus.rabia;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestCommand;

import static org.assertj.core.api.Assertions.assertThat;


/// #2011: the ledger decides whether a delivery is a stale copy of a committed submission or a new one.
class CommittedBatchLedgerTest {
    private static final CorrelationId C1 = new CorrelationId("c1");
    private static final CorrelationId C2 = new CorrelationId("c2");

    private static Batch<TestCommand> batch(String value, CorrelationId... cids) {
        var base = Batch.create(new RabiaEngineTest.TestStateMachine().serializer(), List.of(new TestCommand(value)));

        return new Batch<>(base.id(), List.of(cids), base.timestamp(), base.commands());
    }

    @Test
    void unseen_batchNeverCommitted_isAdmittedUnchanged() {
        var ledger = new CommittedBatchLedger(8);
        var incoming = batch("a", C1);

        assertThat(ledger.unseen(incoming).unwrap()).isSameAs(incoming);
    }

    @Test
    void unseen_everyCorrelationIdCommitted_isDropped() {
        var ledger = new CommittedBatchLedger(8);
        var committed = batch("a", C1);

        ledger.record(committed.id(), committed.correlationIds());

        assertThat(ledger.unseen(batch("a", C1)).isEmpty()).isTrue();
    }

    @Test
    void unseen_sameCommandsWithANewCorrelationId_isAdmittedWithoutTheCommittedOne() {
        var ledger = new CommittedBatchLedger(8);
        var committed = batch("a", C1);

        ledger.record(committed.id(), committed.correlationIds());
        var admitted = ledger.unseen(batch("a", C1, C2)).unwrap();

        assertThat(admitted.correlationIds()).containsExactly(C2);
        assertThat(admitted.id()).isEqualTo(committed.id());
    }

    /// #2045: what a voter answers a proposer with; only the ids actually committed under the batch id.
    @Test
    void committedOf_returnsOnlyTheCommittedCorrelationIds() {
        var ledger = new CommittedBatchLedger(8);
        var committed = batch("a", C1);

        ledger.record(committed.id(), committed.correlationIds());

        assertThat(ledger.committedOf(batch("a", C1, C2)).unwrap()).containsExactly(C1);
    }

    @Test
    void committedOf_isEmpty_whenNoIdOfTheBatchWasCommitted() {
        var ledger = new CommittedBatchLedger(8);
        var committed = batch("a", C1);

        ledger.record(committed.id(), committed.correlationIds());

        assertThat(ledger.committedOf(batch("a", C2)).isEmpty()).as("same batch id, another submission").isTrue();
        assertThat(ledger.committedOf(batch("b", C1)).isEmpty()).as("batch id never committed").isTrue();
    }

    @Test
    void record_beyondCapacity_evictsTheOldestFirst() {
        var ledger = new CommittedBatchLedger(2);
        var a = batch("a", C1);
        var b = batch("b", C1);
        var c = batch("c", C1);

        ledger.record(a.id(), a.correlationIds());
        ledger.record(b.id(), b.correlationIds());
        ledger.record(c.id(), c.correlationIds());

        assertThat(ledger.sizeForTesting()).isEqualTo(2);
        assertThat(ledger.unseen(a).isPresent()).as("oldest evicted: no longer recognised").isTrue();
        assertThat(ledger.unseen(b).isEmpty()).isTrue();
        assertThat(ledger.unseen(c).isEmpty()).isTrue();
    }

    /// #3284: an identical batch committed again and again (a periodic barrier) keeps the ledger bounded: only the
    /// correlation ids of the last `capacity` records are held, not every id the batch id was ever committed with.
    @Test
    void record_repeatedIdenticalBatch_keepsTotalSizeBounded() {
        var ledger = new CommittedBatchLedger(4);

        for (var i = 0; i < 1_000; i++) {
            var repeated = batch("barrier", new CorrelationId("r" + i));

            ledger.record(repeated.id(), repeated.correlationIds());
        }

        assertThat(ledger.sizeForTesting()).isEqualTo(1);
        assertThat(ledger.correlationCountForTesting()).as("ids of the last 4 records only").isEqualTo(4);
        assertThat(ledger.unseen(batch("barrier", new CorrelationId("r995"))).isPresent())
            .as("outside the window: no longer recognised")
            .isTrue();
    }

    /// #3284: a late copy of a repeated batch whose id is inside the window is still refused, and a copy carrying one id
    /// inside and one outside is trimmed to the one outside.
    @Test
    void unseen_lateRepeatedBatchWithinTheBound_isStillRefused() {
        var ledger = new CommittedBatchLedger(4);
        var first = new CorrelationId("r0");
        var second = new CorrelationId("r1");

        for (var i = 0; i < 5; i++) {
            var repeated = batch("barrier", new CorrelationId("r" + i));

            ledger.record(repeated.id(), repeated.correlationIds());
        }

        assertThat(ledger.unseen(batch("barrier", second)).isEmpty()).as("r1 is the oldest record in the window").isTrue();
        assertThat(ledger.committedOf(batch("barrier", second)).unwrap()).containsExactly(second);
        assertThat(ledger.unseen(batch("barrier", first, second)).unwrap().correlationIds()).containsExactly(first);
    }

    /// #3284 / #2045: the bound counts records, never time, so two ledgers fed the same records hold the same entries.
    @Test
    void record_sameRecordSequence_givesEqualLedgers() {
        var left = new CommittedBatchLedger(3);
        var right = new CommittedBatchLedger(3);
        var records = List.of(batch("barrier", C1), batch("x", C2), batch("barrier", new CorrelationId("c3")),
                              batch("barrier", C1, new CorrelationId("c4")), batch("y", new CorrelationId("c5")));

        records.forEach(b -> left.record(b.id(), b.correlationIds()));
        records.forEach(b -> right.record(b.id(), b.correlationIds()));

        assertThat(left.stateForTesting()).isEqualTo(right.stateForTesting());
    }

    /// #3284: a correlation id recorded again under the same batch id is held from its latest record; the expiry of
    /// its first record does not forget it.
    @Test
    void record_idRecordedAgainLater_survivesTheExpiryOfItsFirstRecord() {
        var ledger = new CommittedBatchLedger(2);
        var first = batch("barrier", C1);
        var other = batch("x", C2);
        var again = batch("barrier", C1);

        ledger.record(first.id(), first.correlationIds());
        ledger.record(other.id(), other.correlationIds());
        ledger.record(again.id(), again.correlationIds());

        assertThat(ledger.unseen(batch("barrier", C1)).isEmpty()).as("C1's latest record is inside the window").isTrue();
    }
}
