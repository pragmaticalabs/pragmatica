// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.WorkflowRunValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVCommand.TransactionResult;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.workflow.WorkflowTestCluster.CORE_1;
import static org.pragmatica.aether.deployment.workflow.WorkflowTestCluster.CORE_2;
import static org.assertj.core.api.Assertions.assertThat;


/// rc6 §4.2/§4.3: the ledger's reads and the way it credits a consensus answer to ITS transaction. The cluster is a real applier;
/// where a test fakes the answer it says so.
class WorkflowLedgerTest {
    private WorkflowTestCluster cluster;

    @BeforeEach
    void setUp() {
        cluster = new WorkflowTestCluster();
    }

    private static WorkflowRunValue run(String id, long startedAt, String key) {
        return WorkflowRunValue.admitted(id, "k", Map.of(), List.of(key), startedAt, startedAt + 1_000L);
    }

    private static String idOf(List<KVCommand<AetherKey>> commands) {
        return ((KVCommand.LeaderTransaction<?, ?>) commands.getFirst()).transactionId();
    }

    @Test
    void runs_areOrderedByStartTime_oldestFirst() {
        var ledger = cluster.ledger(CORE_1);

        ledger.create(run("wf-a", 30L, "k:a")).await().unwrap();
        ledger.create(run("wf-c", 10L, "k:c")).await().unwrap();
        ledger.create(run("wf-b", 20L, "k:b")).await().unwrap();
        assertThat(ledger.runs()).as("by start time, not by id").extracting(WorkflowRunValue::id)
                  .containsExactly("wf-c", "wf-b", "wf-a");
    }

    @Test
    void runs_withEqualStartTime_areOrderedById() {
        var ledger = cluster.ledger(CORE_1);

        for (var n : List.of(7, 3, 9, 1, 5, 8, 2, 6, 4, 0)) {
            ledger.create(run("wf-" + n, 50L, "k:" + n)).await().unwrap();
        }
        assertThat(ledger.runs()).extracting(WorkflowRunValue::id)
                  .containsExactly("wf-0", "wf-1", "wf-2", "wf-3", "wf-4", "wf-5", "wf-6", "wf-7", "wf-8", "wf-9");
    }

    /// The store also holds the leader record and the claims: only runs are returned, and the leader record (not an AetherValue)
    /// does not break the read.
    @Test
    void runs_inAStoreHoldingOtherValues_returnsOnlyTheRuns() {
        var ledger = cluster.ledger(CORE_1);

        assertThat(ledger.runs()).isEmpty();
        ledger.create(run("wf-a", 10L, "k:a")).await().unwrap();
        assertThat(cluster.claim("k:a").isPresent()).isTrue();
        assertThat(ledger.runs()).extracting(WorkflowRunValue::id).containsExactly("wf-a");
    }

    @Test
    void createAndCommit_onANodeThatDoesNotLead_answerFalse_andSubmitNothing() {
        var submitted = new AtomicInteger();
        var follower = cluster.ledger(CORE_2,
                                      commands -> {
                                          submitted.incrementAndGet();

                                          return Promise.success(List.of());
                                      });
        var run = run("wf-a", 10L, "k:a");

        assertThat(follower.leads()).isFalse();
        assertThat(follower.create(run).await().unwrap()).isFalse();
        assertThat(follower.commit(run, run.issued("p", 11L)).await().unwrap()).isFalse();
        assertThat(submitted.get()).isZero();
    }

    @Test
    void submissions_carryDistinctTransactionIds() {
        var ids = new ArrayList<String>();
        var ledger = cluster.ledger(CORE_1,
                                    commands -> {
                                        ids.add(idOf(commands));

                                        return Promise.success(List.of());
                                    });
        var run = run("wf-a", 10L, "k:a");

        ledger.create(run).await();
        ledger.commit(run, run.issued("p", 11L)).await();
        ledger.commit(run, run.issued("p", 11L)).await();
        assertThat(ids).hasSize(3).doesNotHaveDuplicates();
    }

    private WorkflowLedger answering(Fn1<List<Object>, String> answer) {
        return cluster.ledger(CORE_1, commands -> Promise.success(answer.apply(idOf(commands))));
    }

    /// An accepted result for ANOTHER transaction is not an acceptance of ours.
    @Test
    void create_creditsOnlyItsOwnTransaction_aForeignAcceptedIdIsNotAnAcceptance() {
        var ledger = answering(id -> List.of(new TransactionResult("someone-else", true)));

        assertThat(ledger.create(run("wf-a", 10L, "k:a")).await().unwrap()).isFalse();
    }

    @Test
    void commit_withMixedResults_isJudgedByItsOwnResult() {
        var run = run("wf-a", 10L, "k:a");
        var next = run.issued("p", 11L);

        assertThat(answering(id -> List.of(new TransactionResult("someone-else", true),
                                           new TransactionResult(id, false))).commit(run, next).await().unwrap()).isFalse();
        assertThat(answering(id -> List.of(new TransactionResult("someone-else", false),
                                           new TransactionResult(id, true))).commit(run, next).await().unwrap()).isTrue();
        assertThat(answering(id -> List.of("not a transaction result")).commit(run, next).await().unwrap()).isFalse();
    }
}
