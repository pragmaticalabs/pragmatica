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
}
